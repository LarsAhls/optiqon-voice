// Proves WHICH targets the predeploy guard is attached to -- without touching the provider.
//
// The first attempt at this proof used `firebase deploy --dry-run`, and that is not safe.
// `--dry-run` skips release but still runs the prepare phase, and prepare enables missing APIs
// for real: one such run enabled `firebasestorage.googleapis.com` on the production project.
// Scope is therefore established here, against firebase-tools' own code, with no network at all.
//
// The seam matters. `lifecycleHooks('storage', 'predeploy')` is NOT itself scope-aware in the
// way one would expect: its internal getReleventConfigs returns a single unnamed config whatever
// `--only` says, because the filter it applies (`!config.target`) is about hosting's named
// deploy targets, not about which product was selected. What actually decides whether the
// Storage hook is ever added to the chain is `filterTargets`, upstream in the deploy command --
// deploy/index.js pushes one predeploy hook per *selected* target name. So the scope assertions
// below are made where the decision is really taken, and the hook-fires assertions are made
// against the hook itself.
//
// No `firebase deploy` command path -- with or without `--dry-run` -- takes part in verifying
// this repository. That is not a preference to be re-litigated by a later session that finds
// a CLI run more convincing: the premise that `--dry-run` is provider-free has been falsified
// against production, and the CLI offers no mode that runs predeploy and stops there. The
// network trap below makes the rule structural rather than a matter of discipline.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

// ---------------------------------------------------------------- the network trap
//
// Armed before firebase-tools is loaded, so nothing this file reaches can open a socket. The
// point is not to catch a mistake I expect to make; it is that "this proof cannot touch the
// provider" should be enforced by the file rather than asserted in its comments. If a future
// change here starts down a code path that resolves a name or opens a connection -- a prepare
// phase, an API enable, a token refresh -- the run dies with SENTINEL instead of succeeding
// quietly against production.
const SENTINEL = 'predeploy-scope: network access is forbidden in this test';
const forbid = () => {
  throw new Error(SENTINEL);
};

const net = require('node:net');
const tls = require('node:tls');
const http = require('node:http');
const https = require('node:https');
const dns = require('node:dns');

net.Socket.prototype.connect = forbid;
net.connect = forbid;
net.createConnection = forbid;
tls.connect = forbid;
http.request = forbid;
http.get = forbid;
https.request = forbid;
https.get = forbid;
dns.lookup = forbid;
dns.promises.lookup = forbid;
globalThis.fetch = forbid;

const { lifecycleHooks } = require('firebase-tools/lib/deploy/lifecycleHooks');
const { filterTargets } = require('firebase-tools/lib/filterTargets');
const { VALID_DEPLOY_TARGETS } = require('firebase-tools/lib/commands/deploy');
const { Config } = require('firebase-tools/lib/config');

const PROD = 'optiqon-voice-47498';

// Inherited state would decide the result: an operator who exports the allow variable would see
// every case pass. Remove it for the whole file.
delete process.env.OPTIQON_ALLOW_DEPLOY;

const config = Config.load({ cwd: REPO, configPath: path.join(REPO, 'firebase.json') });

// `only` is the raw string firebase-tools receives from the --only flag; undefined means the
// flag was absent and every configured target is selected.
const selected = (only) => filterTargets({ config, only }, VALID_DEPLOY_TARGETS);

test('firebase.json attaches a predeploy hook to storage and to nothing else', () => {
  assert.deepEqual(config.get('storage').predeploy, ['node scripts/deploy/storage-guard.mjs']);
  assert.equal(config.get('firestore').predeploy, undefined);
  assert.equal(config.get('hosting').predeploy, undefined);
});

test('an unscoped deploy selects storage, so the guard is in the chain', () => {
  assert.deepEqual(selected(undefined).sort(), ['firestore', 'hosting', 'storage']);
});

test('--only storage selects storage', () => {
  assert.deepEqual(selected('storage'), ['storage']);
});

// The two non-regression cases. A correctly scoped Firestore or Hosting deploy is exactly as
// possible after this change as it was before it: storage is never selected, so its hook is
// never added to the chain, and no new environment variable is required of either.
test('--only firestore never selects storage', () => {
  assert.deepEqual(selected('firestore'), ['firestore']);
});

test('--only hosting never selects storage', () => {
  assert.deepEqual(selected('hosting'), ['hosting']);
});

// And when it IS selected, it refuses. The environment names no OPTIQON_ALLOW_DEPLOY, so the
// guard exits non-zero and firebase-tools turns that into a rejected predeploy -- which is what
// aborts a deploy before its first release.
test('the storage hook refuses when the allow variable is absent', async () => {
  const options = { project: PROD, projectId: PROD, projectRoot: REPO, config, only: 'storage' };
  await assert.rejects(
    lifecycleHooks('storage', 'predeploy')({}, options),
    /storage predeploy error/,
  );
});

// The trap is only worth having if it is armed. Prove it rather than trusting the assignments
// above -- a future node release that makes one of these properties read-only would otherwise
// leave a silently disarmed trap behind, and every test in this file would still be green.
test('the network trap is armed', () => {
  assert.throws(() => https.request('https://firebasestorage.googleapis.com/'), new RegExp(SENTINEL));
  assert.throws(() => net.connect(443, 'firebasestorage.googleapis.com'), new RegExp(SENTINEL));
  assert.throws(() => globalThis.fetch('https://firebasestorage.googleapis.com/'), new RegExp(SENTINEL));
});
