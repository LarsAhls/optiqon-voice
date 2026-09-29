#!/usr/bin/env node
// Predeploy guard for Firestore rules. See guard.mjs for why guards exist and what they check.
//
// Until FS-1 a Firestore deploy needed no gate: the repo's firestore.rules WAS the live ruleset,
// so an accidental deploy republished what was already there. FS-1 broke that equality on
// purpose -- the repo now carries the feedback-case rules and production still runs the
// Mission 1 set -- so from here an unscoped `firebase deploy` would open live paths. Publishing
// them is a provider Gate, and this makes it one.

import { EXPECTED_PROJECT, runGuard } from './guard.mjs';

runGuard({
  product: 'firestore',
  permitted: `OPTIQON_ALLOW_DEPLOY=firestore firebase deploy --only firestore:rules --project ${EXPECTED_PROJECT}`,
});
