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

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const require = createRequire(import.meta.url);
const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

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
