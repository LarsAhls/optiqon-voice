#!/usr/bin/env node
// Predeploy guard for the Storage target, and for that target only.
//
// Why this exists: `firebase deploy` with no `--only` selects every configured target, and a
// Storage ruleset published against the wrong project -- `optioqon-voice`, the misspelled older
// project, is one keystroke away -- is a live change to a live surface. The guard is a predeploy
// hook because the whole predeploy chain runs before the first prepare/deploy/release
// (firebase-tools lib/deploy/index.js), so a refusal here stops an unscoped deploy before
// anything at all is published, not merely the Storage part of it.
//
// Firestore and Hosting are deliberately left alone: they carry no hook, need no new environment
// variable, and `firebase deploy --only firestore` behaves exactly as it did before this file
// existed. This Mission is not the place to put a new gate in front of them.
//
// Two conditions, both required:
//   OPTIQON_ALLOW_DEPLOY names `storage`   -- the deploy was meant, not inherited from a default
//   GCLOUD_PROJECT is exactly the prod id  -- the deploy is aimed at the right project
//
// firebase-tools sets GCLOUD_PROJECT for lifecycle hooks (lib/deploy/lifecycleHooks.js), so the
// second condition reads the project the CLI actually resolved, not a flag this script parsed.

const EXPECTED_PROJECT = 'optiqon-voice-47498';

const allowed = (process.env.OPTIQON_ALLOW_DEPLOY ?? '')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

const project = process.env.GCLOUD_PROJECT ?? '';

const problems = [];

if (!allowed.includes('storage')) {
  problems.push(
    `OPTIQON_ALLOW_DEPLOY does not name 'storage' (got: ${JSON.stringify(process.env.OPTIQON_ALLOW_DEPLOY ?? null)}).`,
  );
}

if (project !== EXPECTED_PROJECT) {
  problems.push(`GCLOUD_PROJECT is '${project}', expected '${EXPECTED_PROJECT}'.`);
}

if (problems.length > 0) {
  console.error('storage-guard: refusing to deploy Cloud Storage rules.');
  for (const p of problems) console.error(`storage-guard:   - ${p}`);
  console.error('storage-guard: the only permitted form is');
  console.error(
    `storage-guard:   OPTIQON_ALLOW_DEPLOY=storage firebase deploy --only storage --project ${EXPECTED_PROJECT}`,
  );
  // Exit 2, not 1, and the reason is specific to this toolchain. firebase-tools runs the hook
  // through cross-env-shell, which spawns with cross-spawn under `shell: true`. In that mode
  // cross-spawn cannot resolve the command file, so its verifyENOENT heuristic treats *any*
  // exit status of 1 as "the command did not exist" and raises a spurious ENOENT that buries
  // the message below under a stack trace (cross-spawn 7.0.6, lib/enoent.js). The heuristic
  // only fires on status 1. Any other non-zero code aborts the deploy just as hard and reaches
  // the operator intact.
  process.exit(2);
}

console.log(`storage-guard: ok -- storage deploy allowed for ${EXPECTED_PROJECT}.`);
