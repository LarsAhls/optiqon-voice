# FS-G runbook — Feedback go-live

> **This runbook grants no approval.** Writing it, reading it or merging it authorises nothing.
> Every section below needs FS-G's own explicit approval from Lars before any of it runs. Each
> section that writes to the provider or to production data (B, C, E, F, G, H) needs its own
> go-ahead, given in the FS-G thread. Anything that does not match what this runbook expects is a
> STOP, not something to work around.

Target: project `optiqon-voice-47498`, region `europe-north2`, bucket
`gs://optiqon-voice-47498-eun2`. The intended provider shape is in
[FS_G_PROVIDER_CONFIG.md](FS_G_PROVIDER_CONFIG.md) and `deploy/fs-g/`. The product contracts are
in [BACKEND_OPEN_CONTRACTS.md](BACKEND_OPEN_CONTRACTS.md), § FS-S12, FS-S34 and FS-S468.

**Before FS-G starts:**
- Production Storage is deny-all.
- Feedback rules are not published.
- The server does not exist on the provider.
- The app builds with `FEEDBACK_REMOTE_ENABLED=false`.

## A — Preflight (read-only)

1. **Checkout.** `C:\Users\ahlst\Projects\optiqon-voice` is on the merged `main` commit that FS-G
   names. The working tree is clean.
2. **Suites green on that commit.**
   - JVM: 458+ tests, 0 failures, 2 skipped.
   - `npm run test:rules`
   - `npm run test:storage-rules`
   - `npm run test:deploy-guard`
   - `sh scripts/verify-fast.sh --all`
   - CI green on the merge commit.
3. **Identity.** `firebase login:list` shows `lars@optiqon.se`. `gcloud config get-value account`
   and `gcloud config get-value project` show that account and `optiqon-voice-47498`.
   No service-account key is present, or used, anywhere.
4. **Billing and APIs.** Read the billing state. List which of the APIs in
   FS_G_PROVIDER_CONFIG.md § APIs are enabled. Enable nothing yet.
5. **Region availability.** Confirm the following in `europe-north2`:
   - Cloud Run
   - Eventarc Firestore triggers
   - Cloud Scheduler
   - Artifact Registry

   If Scheduler is missing, STOP and let FS-G decide on `europe-north1` (see the config doc). Any
   other missing service is a STOP too.
6. **Baseline snapshot.** Record, for rollback:
   - the current Firestore rules release
   - the current Storage rules release (deny-all)
   - the current index list
   - project IAM policy
   - bucket IAM policy
   - service accounts
   - `config/counters`
   - the list of `users/`, with `status` and whether `approvalGeneration` is present
7. **Unchanged guarantees.** Storage rules still use at most two Firestore documents per
   evaluation, per the F-a verification.

Output: a preflight record. Any deviation → STOP.

## B — Infrastructure, APIs, IAM

Each step is additive and recorded. No broad role is granted, and no key is created.

1. Enable the missing APIs from A.4.
2. Create the three service accounts from `deploy/fs-g/targets.json`, with no keys.
3. Grant exactly the roles in the IAM table:
   - project-level roles on the project
   - `storage.objectAdmin` on the bucket only
   - `run.invoker` on the service only, once the service exists in C
4. If A found that the Pub/Sub service agent needs `iam.serviceAccountTokenCreator`, grant exactly
   that.
5. Create an Artifact Registry Docker repository in `europe-north2`.
6. Read the IAM delta back. It must equal the table and nothing else.

## C — Rules, indexes, server deploy

1. **Indexes first.** Create the indexes and field overrides from
   `deploy/fs-g/firestore.indexes.json`, for example with `gcloud firestore indexes composite create`
   and `gcloud firestore indexes fields update`.

   Do **not** add the file to `firebase.json`.

   Wait until every index is `READY`.
2. **Firestore rules.** Publish `firestore.rules` from the merged commit through the guarded deploy
   path. Read the release back; it must match the repository file byte for byte.
3. **Storage rules.** Publish `storage.rules` through its guard. The deploy guard's tests hold the
   two-document budget. Read it back.
4. **approvalGeneration backfill.**
   ```
   node C:\Users\ahlst\Projects\optiqon-voice\scripts\admin\voice-admin.mjs backfill-generation --project optiqon-voice-47498
   ```
   Run it as a dry run first and review the plan. Then repeat with `--apply`, and run it again to
   see "nothing to do".

   Existing values are never changed. A malformed value is reported, not fixed; if one appears,
   STOP.
5. **Image.** Build `deploy/fs-g/Dockerfile` with the repository root as context. Push it to the
   Artifact Registry repository and record the digest.
6. **Service.** Deploy `voice-feedback-server` with exactly the settings in `targets.json`,
   including `APPLY=false`, no unauthenticated access, and the server service account. Then grant
   the trigger and scheduler accounts `run.invoker` on it.
7. **Triggers.** Create the three Eventarc triggers from `targets.json`. They must use exactly
   those patterns and event types.
8. **Scheduler.** Create the job from `targets.json`, **paused**.

## D — Production negative probes (no positive data)

Every probe has to be refused, or has to do nothing. Record each result.

1. **Unauthenticated service calls.** A request to any route without a token gets 401/403 from
   Cloud Run.
2. **Wrong event types.** A route called with the wrong `ce-type` answers 400 (through an
   authorised invoker only).
3. **Anonymous Firestore and Storage access.** Reads and writes of `cases/`, `users/*/caseReads`,
   `users/*/notificationTokens` and `case-attachments/` are denied.
4. **Unapproved accounts.** A signed-in, unapproved or revoked account is refused:
   - case create
   - reservation
   - upload
   - read-marker write
   - token registration

   It can still write a withdrawal intent and a tombstone for its own documents (M3).
5. **Admin checks.** An admin claim without a live `admins/` mirror is refused, both in the rules
   and in `voice-admin` support commands (`NOT_WRITER`).
6. **Dry-run service.** Service logs for the probes show dry-run decisions and no writes.

## E — Narrowly authorised positive test data

Only with explicitly approved test accounts, and only data that G removes again.

1. **Approve one test account.** Use the existing approval path, then check that
   `approvalGeneration` is +1.
2. **Create a case with one screenshot.**
   - Before this step, build the app with `-Pfeedback.remote=true` for this device only.
   - Check the upload, finalize, and the attachment document.
3. **Support operations.** Run each through `voice-admin`: dry run first, then `--apply`, then a
   re-run that must report `already_done`.
   - `reply`: `publicRev` and `activityRev` move +1 each. The case becomes unread for the owner.
     No second bump on retry.
   - `note`: no bump, not visible to the owner.
   - `set-status`: along an allowed transition only.
   - `close`: sets `closedAt`, and leaves `statusCache` unchanged.
4. **Notification.** With the service switched to `APPLY=true` (a separate go-ahead), a public
   reply sends exactly one data-only message (`kind: feedback_reply`, no case id, no text) to the
   approved owner's registered token, and the device shows only its fixed neutral text.
   - A note sends nothing.
   - A status change sends nothing.
   - A revoked owner gets nothing.
5. **M5.** The owner deletes the screenshot.
   - The object is unreadable at once, by rules, before any purge runs.
   - The event-driven purge deletes it, and `purgedAt` is recorded without any fingerprint.
   - The attachment then reaches `purgeFinalAt`.
6. **M6 on a device.** Offline delete, restart, then reconnect:
   - the screenshot stays hidden throughout;
   - the tombstone lands once;
   - the UI never said "deleted on the server" before the acknowledgement.
7. **M4.** Run `voice-admin delete-account <test account>` as a dry run. Review the inventory, then
   apply it and re-run it.
   - Everything in the inventory is gone: Auth user, `users/{uid}` tree, owned cases, and the owner
     prefix in the bucket.
   - The re-run reports nothing left.

## F — Concurrency and race probes (live latency)

With test data from E only:

- **Withdrawal vs first send.** A discard racing a first send ends `withdrawn`,
  `ignored_accepted` or `absent`, with no residue.
- **Late upload.** A late upload after a tombstone is refused by the rules. An in-flight one is
  purged again by the backstop until `purgeFinalAt`.
- **Anchor move.** A reply racing the retention decision wins, and nothing is deleted. Use a test
  case whose deadline is forced through the retention test hook, or skip this probe if no approved
  hook exists.
- **Duplicate support operations.** A support operation re-run in parallel under the same
  `--event` bumps once.
- **Scheduler backstop.** Unpause the job for one run and check that it:
  - reconciles pending intents
  - sweeps nothing young
  - purges any tombstone the triggers missed
  - reports nothing past 24 h

  Then decide in H whether it stays on.

## G — Cleanup and rollback

**Cleanup.** Delete all E/F test data with `voice-admin delete-account` (applied). Revoke the test
approvals. Remove device builds with `feedback.remote=true`.

**Rollback**, in reverse order of C and B, each step recorded:

1. Pause the Scheduler job, then delete it.
2. Delete the triggers.
3. Set the service to `APPLY=false`, or delete it.
4. Restore the Firestore and Storage rules releases recorded in A.6 (Storage back to deny-all).
5. Leave the indexes in place; they are inert. Deleting them is optional.
6. Remove the role bindings and service accounts created in B.
7. Leave the APIs enabled unless FS-G decides otherwise.

**The backfill is not rolled back.** It is inert: `approvalGeneration: 0` is what the rules read
for a missing field anyway.

A rollback never deletes production user data. Data written under E is test data, and cleanup
handles it.

## H — Operational acceptance

FS-G is accepted only when all of these hold:

- A–G results are recorded, with every negative probe refused.
- The IAM delta equals the inventory.
- No key exists.
- The service runs with `APPLY=true` only after an explicit decision.
- The Scheduler job is on, and its first unattended run is clean.
- Nothing is past 24 h in the purge backstop.
- A decision is recorded on turning `FEEDBACK_REMOTE_ENABLED` on for the beta build. That is a
  separate release step, not part of this runbook.
- The support@optiqon.se fallback is still shown while remote Feedback is off.
- Live FCM delivery is proven end to end: a public reply reaches a device registered by the
  wired client (repo-side since FS-S468) as a data-only message, and the device shows only the
  fixed neutral text.
- A follow-up owner is named for self-service account deletion (product intent, not built).
