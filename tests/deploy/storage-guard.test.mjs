// Regression test for scripts/deploy/storage-guard.mjs.
//
// The guard is only worth having if it refuses AND if it lets the one correct invocation
// through. A guard that always fails is indistinguishable from a broken build script, and it
// gets removed the first time it is in the way -- so both directions are asserted here.
//
// The script is run as a real child process rather than imported, because what the predeploy
// hook consumes is the exit code of a process, not a return value.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const GUARD = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
  'scripts',
  'deploy',
  'storage-guard.mjs',
);

const PROD = 'optiqon-voice-47498';

// The environment is rebuilt from scratch for each case: inheriting the developer's shell would
// make the result depend on whether OPTIQON_ALLOW_DEPLOY happens to be exported there, which is
// exactly the confusion the guard exists to prevent.
function run(env) {
  return spawnSync(process.execPath, [GUARD], {
    env: { PATH: process.env.PATH ?? '', SystemRoot: process.env.SystemRoot ?? '', ...env },
    encoding: 'utf8',
  });
}

// Exactly 2, not merely non-zero. Under firebase-tools' cross-env-shell wrapper an exit status
// of 1 makes cross-spawn raise a spurious ENOENT that hides the guard's own message behind a
// stack trace; see the comment in storage-guard.mjs. Pinning the code keeps that fix from being
// undone by someone tidying it back to the conventional 1.
test('refuses when OPTIQON_ALLOW_DEPLOY is absent', () => {
  const r = run({ GCLOUD_PROJECT: PROD });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /OPTIQON_ALLOW_DEPLOY does not name 'storage'/);
});

test('refuses when OPTIQON_ALLOW_DEPLOY names another target', () => {
  const r = run({ OPTIQON_ALLOW_DEPLOY: 'firestore', GCLOUD_PROJECT: PROD });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /OPTIQON_ALLOW_DEPLOY does not name 'storage'/);
});

// The misspelled older project really exists and is still reachable with these credentials.
test('refuses the right allow against the wrong project', () => {
  const r = run({ OPTIQON_ALLOW_DEPLOY: 'storage', GCLOUD_PROJECT: 'optioqon-voice' });
  assert.equal(r.status, 2);
  assert.match(r.stderr, /GCLOUD_PROJECT is 'optioqon-voice'/);
});

test('allows the one correct invocation', () => {
  const r = run({ OPTIQON_ALLOW_DEPLOY: 'storage', GCLOUD_PROJECT: PROD });
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /storage-guard: ok/);
});
