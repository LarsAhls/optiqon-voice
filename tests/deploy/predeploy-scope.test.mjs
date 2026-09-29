// Proves WHICH targets the predeploy guards are attached to, and WHERE a storage deploy would
// release -- without touching the provider.
//
// The first attempt at this proof used `firebase deploy --dry-run`, and that is not safe.
// `--dry-run` skips release but still runs the prepare phase, and prepare enables missing APIs
// for real: one such run enabled `firebasestorage.googleapis.com` on the production project.
// Scope is therefore established here, against firebase-tools' own code, with no network at all.
//
// The seam matters. `lifecycleHooks(product, 'predeploy')` is NOT itself scope-aware in the way
// one would expect: its internal getReleventConfigs filters on named deploy TARGETS, not on the
// product that was selected. What actually decides whether a product's hook is ever added to the
// chain is `filterTargets`, upstream in the deploy command -- deploy/index.js pushes one
// predeploy hook per *selected* product. So the scope assertions below are made where the
// decision is really taken, and the hook-fires assertions are made against the hook itself.
//
// Where the bytes would go is proven one layer further in. Storage is the named deploy target
// `feedback`, and .firebaserc maps that target to exactly one bucket. The storage deploy's own
// prepare and release functions are driven here with that configuration: prepare until its first
// network call, which the trap turns into a rejection, and release with a recording stand-in for
// the rules API. Nothing is sent; what is asserted is what WOULD have been.
//
// Both Firestore and Storage are guarded. FS-1 made the repo's firestore.rules run ahead of the
// live ruleset, so a Firestore deploy is a provider Gate too. Hosting stays unguarded.
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
import { readFileSync } from 'node:fs';

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
const { RC } = require('firebase-tools/lib/rc');
const storagePrepare = require('firebase-tools/lib/deploy/storage/prepare').default;
const storageRelease = require('firebase-tools/lib/deploy/storage/release').default;

const PROD = 'optiqon-voice-47498';
const BUCKET = 'optiqon-voice-47498-eun2';

// Inherited state would decide the result: an operator who exports the allow variable would see
// every case pass. Remove it for the whole file.
delete process.env.OPTIQON_ALLOW_DEPLOY;

const config = Config.load({ cwd: REPO, configPath: path.join(REPO, 'firebase.json') });
const rc = RC.loadFile(path.join(REPO, '.firebaserc'));

// `only` is the raw string firebase-tools receives from the --only flag; undefined means the
// flag was absent and every configured target is selected.
const selected = (only) => filterTargets({ config, only }, VALID_DEPLOY_TARGETS);

// ------------------------------------------------------------------ configuration

test('firebase.json guards storage and firestore, and leaves hosting alone', () => {
  assert.deepEqual(config.get('storage'), [
    {
      target: 'feedback',
      rules: 'storage.rules',
      predeploy: ['node scripts/deploy/storage-guard.mjs'],
    },
  ]);
  assert.deepEqual(config.get('firestore').predeploy, ['node scripts/deploy/firestore-guard.mjs']);
  assert.equal(config.get('hosting').predeploy, undefined);
});

// A storage entry with neither target nor bucket is resolved to the project's DEFAULT bucket,
// over the network -- and this project has no default bucket. A `bucket` property would bypass
// .firebaserc. Every entry names a target, and only a target.
test('every storage entry names a deploy target and no bucket', () => {
  for (const entry of config.get('storage')) {
    assert.equal(typeof entry.target, 'string');
    assert.equal(entry.bucket, undefined);
  }
});

test('.firebaserc maps storage:feedback to exactly the eun2 bucket, and nothing else', () => {
  assert.deepEqual(rc.target(PROD, 'storage', 'feedback'), [BUCKET]);
  assert.deepEqual(Object.keys(rc.data.targets), [PROD]);
  assert.deepEqual(Object.keys(rc.data.targets[PROD]), ['storage']);
  assert.deepEqual(Object.keys(rc.data.targets[PROD].storage), ['feedback']);
  // No default project: a deploy has to name --project, and the guards check what it named.
  // Read raw, because RC fills in an empty `projects` map on load.
  const raw = JSON.parse(readFileSync(path.join(REPO, '.firebaserc'), 'utf8'));
  assert.deepEqual(Object.keys(raw), ['targets']);
  assert.equal(rc.hasProjects, false);
});

// ------------------------------------------------------------------ scope

test('an unscoped deploy selects storage and firestore, so both guards are in the chain', () => {
  assert.deepEqual(selected(undefined).sort(), ['firestore', 'hosting', 'storage']);
});

test('--only storage and --only storage:feedback select storage alone', () => {
  assert.deepEqual(selected('storage'), ['storage']);
  assert.deepEqual(selected('storage:feedback'), ['storage']);
});

// Scoping one product never drags in the other's hook.
test('--only firestore never selects storage', () => {
  assert.deepEqual(selected('firestore'), ['firestore']);
  assert.deepEqual(selected('firestore:rules'), ['firestore']);
});

// Hosting stays exactly as deployable as before: no guard is selected, no variable required.
test('--only hosting selects neither guarded product', () => {
  assert.deepEqual(selected('hosting'), ['hosting']);
});

// ------------------------------------------------------------------ the hooks fire

// When a guarded product IS selected, its hook refuses. The environment names no
// OPTIQON_ALLOW_DEPLOY, so the guard exits non-zero and firebase-tools turns that into a
// rejected predeploy -- which is what aborts a deploy before its first release.
test('the storage hook refuses when the allow variable is absent', async () => {
  for (const only of ['storage', 'storage:feedback']) {
    const options = { project: PROD, projectId: PROD, projectRoot: REPO, config, rc, only };
    await assert.rejects(
      lifecycleHooks('storage', 'predeploy')({}, options),
      // The hook runs per named target, and says so.
      /storage\[feedback\] predeploy error/,
      only,
    );
  }
});

test('the firestore hook refuses when the allow variable is absent', async () => {
  for (const only of ['firestore', 'firestore:rules']) {
    const options = { project: PROD, projectId: PROD, projectRoot: REPO, config, rc, only };
    await assert.rejects(
      lifecycleHooks('firestore', 'predeploy')({}, options),
      /firestore predeploy error/,
      only,
    );
  }
});

// ------------------------------------------------------------------ where the bytes would go

// firebase-tools bolds names in its messages with ANSI escapes; compare against the plain text.
const plain = (re) => (err) => {
  assert.match(String(err.message).replace(/\[[0-9;]*m/g, ''), re);
  return true;
};

const prepareOptions = (only, project = PROD) => ({
  project,
  projectId: project,
  projectRoot: REPO,
  cwd: REPO,
  config,
  rc,
  only,
});

test('prepare refuses a storage target that is not configured, before any network call', async () => {
  await assert.rejects(
    storagePrepare({ projectId: PROD }, prepareOptions('storage:other')),
    plain(/Could not find rules for the following storage targets: other/),
  );
});

// The misspelled older project exists and is reachable with these credentials, but it has no
// target mapping -- so a deploy aimed at it cannot resolve a bucket at all and fails locally.
test('prepare refuses a project with no target mapping, before any network call', async () => {
  await assert.rejects(
    storagePrepare({ projectId: 'optioqon-voice' }, prepareOptions('storage:feedback', 'optioqon-voice')),
    plain(/Deploy target feedback not configured for project optioqon-voice/),
  );
});

// prepare records what it means to release, then compiles the ruleset remotely. The compile is
// its first provider call, and it never gets out: with no credentials it fails on the auth
// refresh, and with credentials the trap stops the request. Either way the rejection comes from
// inside compileRuleset, and what was recorded before it is the evidence.
test('prepare selects exactly the feedback entry, and stops at its first provider call', async () => {
  for (const only of ['storage:feedback', 'storage']) {
    const context = { projectId: PROD };
    await assert.rejects(
      storagePrepare(context, prepareOptions(only)),
      (err) => {
        assert.match(err.message, new RegExp(`${SENTINEL}|Unable to refresh auth`));
        assert.match(err.stack, /compileRuleset/);
        return true;
      },
      only,
    );
    assert.deepEqual(
      context.storage.rulesConfigsToDeploy.map((c) => ({ target: c.target, rules: c.rules })),
      [{ target: 'feedback', rules: 'storage.rules' }],
      only,
    );
  }
});

test('release would publish storage.rules to the eun2 bucket and nowhere else', async () => {
  const released = [];
  const context = {
    storage: {
      rulesConfigsToDeploy: config.get('storage'),
      rulesDeploy: {
        release: async (rules, service, bucket) => {
          released.push({ rules, service, bucket });
        },
      },
    },
  };
  const buckets = await storageRelease(context, { project: PROD, rc });
  assert.deepEqual(buckets, [BUCKET]);
  assert.deepEqual(released, [{ rules: 'storage.rules', service: 'firebase.storage', bucket: BUCKET }]);
});

// ------------------------------------------------------------------ the trap itself

// The trap is only worth having if it is armed. Prove it rather than trusting the assignments
// above -- a future node release that makes one of these properties read-only would otherwise
// leave a silently disarmed trap behind, and every test in this file would still be green.
test('the network trap is armed', () => {
  assert.throws(() => https.request('https://firebasestorage.googleapis.com/'), new RegExp(SENTINEL));
  assert.throws(() => net.connect(443, 'firebasestorage.googleapis.com'), new RegExp(SENTINEL));
  assert.throws(() => globalThis.fetch('https://firebasestorage.googleapis.com/'), new RegExp(SENTINEL));
});
