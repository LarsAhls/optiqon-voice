#!/usr/bin/env node
// Predeploy guard for Cloud Storage rules. See guard.mjs for why guards exist and what they check.
//
// Storage is declared in firebase.json as the named deploy target `feedback`, which .firebaserc
// maps to gs://optiqon-voice-47498-eun2 -- the one bucket there is. There is no default bucket
// in this project, and a target-less storage entry would make firebase-tools go looking for one
// over the network. The permitted form therefore names the target, not merely the product.

import { EXPECTED_PROJECT, runGuard } from './guard.mjs';

runGuard({
  product: 'storage',
  permitted: `OPTIQON_ALLOW_DEPLOY=storage firebase deploy --only storage:feedback --project ${EXPECTED_PROJECT}`,
});
