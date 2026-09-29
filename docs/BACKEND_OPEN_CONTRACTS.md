# Backend V1 — open contracts

Durable record of what is **not** settled. Written during Mission 1 (F0–F2) so the
later phases do not have to rediscover it. Nothing here is approved work; each item is
a dependency or an explicit stop.

## F3 — attachments (Worker + R2): SUPERSEDED by FS-1 (Cloud Storage)

**Updated 2026-09-28.** Attachments no longer go through a Worker and R2. They are private
screenshots in `gs://optiqon-voice-47498-eun2` (europe-north2), guarded by `storage.rules` and
the Firestore attachment document -- see *FS-1 — Feedback attachments on Cloud Storage* below.
The four points are kept as the record of why the R2 route stopped; none of them is open work.


1. **Resurrection after delete is not solved.** A conditional create-only PUT
   (`onlyIf: { etagDoesNotMatch: "*" }`) does not prevent an in-flight PUT from landing
   *after* a delete-intent was recorded and the R2 object was removed: create-only sees
   no existing etag and writes. Reservation, PUT, delete and retry need a tested
   serialisation / tombstone / version contract before any deploy.
2. **R2 behaviour is documented, not verified.** Conditional semantics, server-side
   `sha256` verification and concurrency must be exercised against the live service.
3. **There is no monetary hard cap.** The proven ceiling is an application ceiling
   (reservations per user, bytes per user, attachments per case, approved users). The
   wrangler deploy token and any future R2 S3 token bypass all of it.
4. Worker CPU against the Workers Free 10 ms limit is unmeasured.

The Firestore rules tests in F1 prove none of the above.

## F4 — admin and Claude automation

- `Levererat` requires evidence that the rules can bind but cannot judge. A writer going
  around the tool can record a formally valid but false evidence block; it is logged and
  attributable, not prevented. Decide whether that residual risk is accepted.
- Quota reset genuinely lifts a user's lifetime cap. It is audited, not blocked.

## F5 — platform

- Email-link sign-in is wired end to end in code: the app sends the link with the default
  Hosting continue-URL `https://<project_id>.web.app/signin` (derived from the same
  `project_id` as `firebaseAuthHost`; no `setLinkDomain`), the manifest declares one
  `autoVerify` App Links filter for `https://<project_id>.firebaseapp.com/__/auth/links`,
  and `MainActivity` hands the incoming link to the account screen. The earlier
  `voice.optiqon.se` filter is removed (plan rev. 4, D4). The repo now holds
  `public/.well-known/assetlinks.json` (debug key SHA-256 only) and `public/signin/` with a
  `hosting` block in `firebase.json`. **Updated 2026-09-10:** G1+G2 were run on 2026-09-09
  (evidence in `gate-m1/GATE_2_READBACK.md`), so the two blockers this paragraph used to name
  are gone — Authentication **is** initialised in `optiqon-voice-47498` with Google (primary)
  and Email/Password + email link (secondary), and Hosting has exactly one release on `live`
  serving `public/`. The Digital Asset Links API lists `se.optiqon.voice`.
  **Still unproven, and this is what G3 exists to prove:** that the link Firebase actually
  sends opens the *app* rather than a browser on device `c1f9837c` — App Links verification
  against `optiqon-voice-47498.firebaseapp.com`, the tester receiving the mail, and the app
  completing sign-in from it. Nothing about that route has run on hardware.
  One known deviation from the runbook: `/signin` answers 301 → `/signin/` (Hosting's
  directory-index redirect, query preserved) rather than 200 directly.
- Google sign-in is shown as primary and e-mail link as secondary by the account screen.
  **Updated 2026-09-10:** the local `app/google-services.json` from G1 now carries 2 OAuth
  clients (1 web) and the build generates `default_web_client_id`, so the "0 OAuth clients"
  blocker is closed (checked by `scripts/gate/check-google-services.sh`). That the Google
  flow completes on the device is a G3 step, not a settled fact.
- The address used to complete a link is always the one stored on the device, or one the
  tester types in. It is never read from the link, because a forwarded link would
  otherwise sign the wrong person in.
- Signing inventory: the installed build on the one device is signed with the committed
  debug key (P4 in `gate-m1/PREFLIGHT.md`); no beta key exists. What a key rotation does
  per Android version, and that the app's stored data survives one, is proven on emulators
  only — see `docs/BETA_SIGNING_AND_DISTRIBUTION.md`. The method for the existing
  installation (D2) is not decided; any SHA or signing-identity change remains a separate
  approved action (G4).
- App Check is abuse protection, not authorisation.

## F6 — retention, deletion, privacy

- Retention for attachments: `lastActivityAt + 12 months` while a case is open,
  `closedAt + 30 days` once closed, whichever is earlier. Enforced by the server actor, which
  does not exist yet (FS-SA).
- Account deletion must cover Auth, Firestore, the `case-attachments/{uid}/` prefix in the
  feedback bucket, and dependent documents. No live deletion without exact approval.
- **Deletion SLA — candidate only, not locked.** The recommended HOW is an event-driven purge on
  tombstone plus a daily backstop sweep, which would allow the promise *"physically deleted
  within 24 hours"*. It is preferred because it keeps the download-token window short (see
  FS-1, *Download tokens*) and a missed event is still caught within a day. The user-facing SLA
  is fixed only after FS-SA has verified the actor's provider, mechanism, region and cost. No
  privacy text may state 24 hours before then.
- The compute region of the server actor is open. If it is not europe-north2, the privacy text
  must say where processing happens; that is a material trigger for FS-SA, not a detail.
- The Worker/edge note that used to stand here belonged to the R2 route and no longer applies.

## Undecided policy values

- Local offline grace: **decided 2026-09-09 — D6 = 72 h**, as beta policy for ≤10 known
  testers. Not a V1 policy, and no longer an open item; see the D6 section in
  `GATE_M1_HANDOFF.md` for what it governs (how long an already-approved device may keep
  dictating offline) and what it does not (cloud access — `firestore.rules` has no grace).
  The value lives in configuration, not at the call sites that enforce it.
- Monotonic quota without refund: proposed for V1.

## Two rule sets: what is deployed, and what is merely written

**Updated 2026-09-28 (FS-1).** The repo's `firestore.rules` now runs **ahead** of the deployed
ruleset: it opens `cases`, their `events` and `attachments`, `users/{uid}/uploads` and
`users/{uid}/quota` for the Feedback case model. The paragraphs below describe the Mission 1 set,
which is still what production runs. Because the repo copy is no longer the live copy, a
Firestore deploy is now a provider Gate: `firebase.json` carries a predeploy
`scripts/deploy/firestore-guard.mjs` that refuses unless `OPTIQON_ALLOW_DEPLOY` names
`firestore` and the project is `optiqon-voice-47498`. `sync`, `reads`, `news`, `invites` and
`deletionRequests` stay shut.

`firestore.rules` is the **Mission 1** set and the only file `firebase.json` names, so an
ordinary `firebase deploy --only firestore:rules` cannot pick up anything else. It opens
exactly what the Mission 1 chain needs — registration, an account reading its own status,
an admin approving and revoking — and shuts every path belonging to a feature that does not
ship yet: `cases`, their events and attachments, `uploads`, `quota`, the private `sync` and
`reads` subtrees, `news`, `invites` and `deletionRequests`. A rule that is live before the
feature it guards is a promise nobody has tested.

`firestore.future.rules` is the full F1 set, preserved verbatim with its original suite. It
is not deployed. Opening one of those paths means moving it into `firestore.rules`, which is
a reviewable edit rather than a silent inheritance.

`deletionRequests` being shut is not a gap in the deployed set. In Mission 1 the revoked
screen shows `support@optiqon.se` and opens the device's own mail composer; the app transmits
nothing itself. A revoked account's route to support and to requesting deletion is therefore
email, which no Firestore rule can withdraw.

## A correction: revocation used to stop at the screen

An earlier version of this document stated that "a pending or revoked account can still read
its own record but cannot spend storage on the project", and described that as deliberate.
The second half was true; the first was a security hole wearing a rationale.

Every **write** path in F1 required `isApproved()`. The **read** paths did not: `cases` and
its events and attachments, `uploads`, `quota`, `sync`, `reads`, `config` and the admin
roster were all reachable with nothing but `signedIn()`. Revoking a status does not
invalidate an already-issued ID token, and the token is what the Firestore API
authenticates — so a revoked tester kept read access to everything they had ever created,
`cases.body` included, up to 20 000 characters of dictated text each. The device-side gate
closed the app; the cloud stayed open to anyone willing to skip the app.

Both sets now require `isApproved()` on those paths. Three read exceptions remain, and only
these three:

- `users/{uid}` self-`get` — an account must be able to read its own record to learn that it
  is pending, rejected or revoked. Without it a revocation could never be *displayed*.
- `invites/{email}` self-`get` (future set only) — an invite is read before approval by
  definition.
- `deletionRequests/{uid}` self-`get` (future set only) — a data-subject right that has to
  survive revocation.

`tests/rules/revocation.test.mjs` pins this with a previously-approved account that is then
revoked, rejected and pushed back to pending, holding a still-valid token and talking to the
API with no client in between. Nineteen of its twenty-five assertions fail against the
pre-correction rules, so the guard is load-bearing rather than decorative.

The seat counter is bound in both directions now. Approval takes a seat and losing approval
returns it, each in the same commit as the status change; a transition that never held a seat
must leave the counter alone. Before this only the increment was bound, which made
`approvedUsers` a high-water mark of approvals ever granted rather than a count of accounts
currently approved — and the ceiling it is compared against meant progressively less with
every revocation. What this still does not prevent is a writer moving the counter on its own,
in a commit that changes no status: that remains an audited admin action, not a blocked one.

## What the F1 rules suite does and does not prove

The suite in `tests/rules/` runs against the Firestore emulator and covers negative
tests 1-13, plus the quieter collections in `private.test.mjs`: an account's own
`sync` and `reads` subtrees, `invites`, `news` and `deletionRequests`. Both writing to and
reading from a private subtree require an *approved* account, not merely a signed-in one.

What a green run does not earn, stated plainly:

- **`diff()` sees changed values, not written keys.** Rewriting a field with the value it
  already holds affects no keys, so `affectedKeys().hasOnly(...)` lets it through. The
  document cannot move, so this grants nothing — but a rule that tries to forbid *writing*
  a key rather than *changing* it will not behave as its author expects.
- **Concurrency is not tested.** The quota tests drive the boundary sequentially, which is
  what the losing side of a real race observes after its transaction retries against the
  updated counter. Genuine parallel commits are Firestore's contention handling, not
  something the emulator suite exercises.
- Nothing here says anything about R2, the Worker, or the upload lifecycle. See the F3
  hard stop above.

## What the F2 app tests do and do not prove

The unit and Robolectric tests cover negative tests 14-24: the access gate
(`AccessGateTest`), the per-uid verdict cache (`AccessStateStoreTest`), per-account file
storage (`UserScopedStorageTest`), the outbox ownership and failure rules
(`OutboxPolicyTest`, `OutboxPersistenceTest`) and the version 7 to 8 upgrade
(`OutboxMigrationTest`). What that leaves open:

- **Revocation is discovered at the next check-in, not instantly.** Every consultation of
  the gate kicks off a throttled background refresh (15 minutes), so a revoked account
  loses access on its next attempt rather than during the current one. Dictation never
  waits on the network; that is the trade. With no network the verdict stands until grace
  runs out, as designed.
- **The gate is a product boundary, not a security boundary.** It runs on the device and
  reads a DataStore value; a rooted phone can change it. Cloud access is guarded
  independently by the rules, which have no grace period, so the local gap is limited to
  local dictation.
- **Nothing here exercises Firebase.** `FirebaseAuthGateway`, `FirebaseSignInClient` and
  `RegistrationRepository` are covered by their types and by the build, not by a test —
  the first proof that a real sign-in produces a `pending` user is a live smoke run, which
  Mission 1 does not include.
- **The outbox has no sender.** The queue, its ownership rule and its failure handling are
  proven; `OutboxSender` is a placeholder that refuses permanently, and nothing enqueues a
  row yet. Delivery is F3 and later.
- **"Process death" is a closed and reopened database**, which is what the durability claim
  actually rests on. It is not a killed Android process, and WorkManager's own scheduling
  is not exercised.
- **There was never a legacy directory.** `UserScopedStorage.legacyDir()` had no writer and
  no reader anywhere in the app, so no data was ever in it and nothing had to be adopted out
  of it. It is gone. The real question — what happens to the data already on the device when
  accounts arrive — is answered by storage roots below, not by a file migration.

## What the Mission 1 closeout adds, and what it still does not prove

The access layer now rests on one invariant: the only writer of an `AccessSnapshot` is
`AccessRepository.record()`, and it is called only after a successful, current, non-cached
*server* read for the *active* identity. `SoleWriterInvariantTest` fails if a second calling
file appears. Everything below is what that invariant does not reach.

### Ordering, and where the verdict is actually decided

- **The identity and sequence checks happen inside the write, not before it.** Checking and then
  suspending to write leaves a window in which two answers both pass their checks and the older
  one lands last. `AccessStateStore.recordIfAccepted` re-evaluates the predicate against the
  bytes being replaced, inside DataStore's own serialised transaction, so an older `approved`
  cannot overwrite a newer `revoked` however the two are interleaved.
- **The predicate is re-run on conflict, by design.** DataStore may replay a transform, so it
  must be a pure function of the snapshot it is handed. A predicate that closed over a decision
  made outside would reintroduce exactly the race it is there to close.
- **What this does not prove.** `AccessRecordAtomicityTest` forces the interleaving with gated
  writes on a single process's DataStore. It says nothing about two processes writing the same
  file, which the app does not do today and which would need a different mechanism if it ever
  did.

### Local data and storage roots

- **Isolation is at the storage handle, not per row.** A root names a database file, two
  preference files and a retained-audio directory; the data already on this device keeps the
  current, unrenamed names as the `default` root. `StorageRootIsolationTest` and
  `StorageRootRecoveryTest` prove a second root writes none of the first owner's bytes and
  that the owner reopens exactly its own files after sign-out, restart and a compatible Room
  upgrade. Both run on synthetic data; **no real local data was read, moved or deleted.**
- **Switching roots ends the process.** Re-scoping Room, DataStore, EncryptedSharedPreferences
  and WorkManager mid-process is where a stale handle would leak silently, so the app persists
  the new active identity, cancels the identity scope and restarts instead. The restart itself
  is a `ProcessRestarter` seam in tests; the real `Process.killProcess` path has not been run
  on a device.
- **A root is a product boundary, not a security boundary.** Two roots are as separate as two
  installs to the app's own code. A rooted device reads both, exactly as it reads app-private
  storage today.
- **The claim question is asked once, before the app opens.** Neither answer copies, moves,
  deletes or uploads anything, and declining leaves the `default` root on disk, unclaimed and
  untouched. What is not built is a later "actually, adopt it after all" flow.
- **The persisted active uid is a note, not a credential.** `StorageOwnership` reconciles it
  against the identity actually signed in — inside the provider that hands out the root, so no
  handle can be opened before the check — and corrects the note rather than obeying it. A start
  under a different account therefore opens neither the remembered account's files nor the
  device's, and `StorageOwnershipTest` proves the absent account's bytes are unchanged. Reverting
  the reconciliation fails three of its eight tests, so the guard is load-bearing rather than
  decorative.
- **An unreadable identity is treated as an unknown one.** A signed-out process, and one whose
  auth SDK is missing or throwing, both resolve to the `signedout` root once anyone owns the
  device's data — and to `default` while nobody does, which is the single-user install this app
  has always been, unchanged. `signedout` is a name, not a deletion: the owner's files stay
  exactly where they are and are simply not opened.
- **A root retired mid-switch is not written to.** `StorageOwnership.seal()` runs before the
  restart is requested, so start-up migrations and profile defaults that have not run yet find
  the door shut if the restart is interrupted. What this does *not* cover is anything already
  inside a `DataStore.edit` block at that instant; the process ending remains the real
  guarantee, and the seal is what holds while it does.

### The gate at the effect boundary

- **Authorisation is a lease, re-validated immediately before each outgoing effect** — before
  the ASR request, before the LLM request, and inside the injection bridge immediately before
  the commit call. `EffectBoundaryTest` and `InjectionRefusalTest` pin all three.
- **Text already committed to another app's input connection cannot be recalled.** That is the
  one accepted race, bounded by a single call. It is not a licence to finish an injection that
  has not happened yet, and the tests assert the difference.
- **Late, out-of-order and foreign-identity answers write nothing** (`AccessOrderingTest`,
  `GraceRenewalTest`). A known revocation is not undone by an older approved answer, and a
  cache-served read records nothing at all, so repeated offline refreshes cannot advance grace.
- **`PERMISSION_DENIED` is not read as revocation.** Rules deny for reasons unrelated to
  status; the partner of "a token is not approval" is "a denied read is not proof of
  revocation".

### Time, Doze and latency

- **`decision` re-emits on a computed deadline, not a tick** — zero steady-state wakeups. But
  `delay` does not run in Doze, so a grace expiry falling while the phone sleeps is observed
  late. This is tolerable only because the consumers needing time-correctness are the UI
  (invisible while asleep) and idle service state, and because every effect boundary
  re-validates against live clocks. `currentDecision()` remains the authority. If a displayed
  state must be correct across deep sleep, that needs `AlarmManager.setAndAllowWhileIdle` —
  **noted, not built.**
- **`check()` waits at most ~3 s on a stale-and-online refresh**, and a timeout classifies as
  `Failed`, so a user still inside grace proceeds. That is a deliberate trade of freshness for
  not blocking dictation, and it is the reason revocation is discovered at the next check-in
  rather than instantly.

### Offline, and what no string may claim

- **There is no offline audio queue in Mission 1.** Real network absence means cloud speech
  recognition is unreachable, full stop. This is an accepted scope boundary, not a decision
  that anything is queued: no UI string says a recording is waiting or will be sent later,
  because nothing would send it. A temporary Firebase or auth fault *with* a working network
  still allows dictation inside valid grace — that case is `Degraded`, and it is a banner, not
  a gate.
- **An interrupted recording is preserved or discarded strictly by the user's standing history
  and audio-retention settings**, into its own identity's root, visible and retriable only
  there and not while that identity is blocked. Nothing is deleted because a session changed,
  and no new permanent audio storage is introduced for users who turned retention off.

### The name on a registration

- **The user confirms it; the app never assumes it.** A Google profile name pre-fills the field
  as a suggestion, and the request is only sent once the user has seen and accepted or edited it.
  The previous `email.substringBefore('@')` fallback is gone: it produced a name the person never
  chose, on a record somebody else has to make an approval decision from.
- **Client and rules agree on the same definition.** `DisplayName.normalize` trims and collapses
  whitespace, `isValid` bounds the result at 2–80 characters, and `firestore.rules` requires
  exactly that shape — so a client that skips normalisation is rejected rather than accepted with
  an unreadable name. Two rules tests cover the whitespace-only and over-long cases.
- **What this is not.** There is no profile- or account-editing feature. A name is confirmed once,
  at registration; changing it afterwards is not built.

### Support contact

- **The revoked screen sends nothing.** `OutboxSender` is still a placeholder that refuses
  permanently, so the private feedback backend is not used here and no receipt is shown. The
  screen shows contact information and, at most, opens a user-initiated mail intent on an
  explicit tap.
- **`support_contact_email` is `support@optiqon.se`.** The screen therefore shows the address and
  an explicit tap target that opens the device's own mail composer. `SupportContactTest` asserts
  what is still the contract — that the app itself transmits nothing and shows no receipt — not
  that the affordance is absent. Whether that mailbox is monitored is Lars's to decide; the app
  makes no promise about a reply.

### Still unproven by any of this

Nothing in Mission 1 exercises Firebase. No sign-in happened, no ruleset was deployed, no real
registration was created. The first proof that a live account goes new to pending to approved,
and that revocation reaches a device, is the next Gate's physical smoke run.

### Cloud-side refusal on revoke is UNPROVEN, and G3 cannot prove it

Added 2026-09-09 while correcting the G3 smoke template (finding F1).

`firestore.rules` grants `/users/{userId}` `allow get: if signedIn() && userId == uid();` —
**deliberately not gated on approval**, so an account can read its own record to learn that it
is pending, rejected or revoked. `tests/rules/m1.test.mjs` asserts that this read *succeeds*
for a revoked account. Every other client-visible path is `if false` for a non-admin, and
`isApproved()` is reachable only through `isReader()` / `isWriter()`.

The consequence is a genuine gap in what can be observed: a tester's entire cloud surface is
that one status-independent document, so **there is no request a revoked tester can make whose
refusal would demonstrate that the cloud enforces revocation**. The G3 smoke run therefore
proves the *device* boundary — the app revalidates its lease and shows the revoked screen —
and not the cloud boundary. The `config/limits` probe in step 8 returns 403 both before and
after the revoke and is recorded as a **non-discriminating control**.

This stays UNPROVEN until a feature genuinely protected by `isApproved()` ships and a revoked
account is observed being refused by the rules. An earlier draft of the template asserted 403
on the tester's own document, which would have produced a false FAIL on the one step meant to
prove the boundary; that criterion has been corrected rather than kept.

### The two-document ceiling is VERIFIED, and the emulator must not be used to check it

Added 2026-09-11 after the F-a live verification run
(`docs/F_A_STORAGE_RULES_VERIFICATION_2026-09-11.md`).

Cross-service Cloud Storage Security Rules — `firestore.get()` from `storage.rules` — are
documented to allow **no more than two distinct Firestore documents per evaluation**, while
repeated calls against a document already read are free because they are cached. Mission D Fas 5
depends on both halves of that sentence being literally true. Both are now measured against the
real service:

- **two distinct documents, eight `firestore.get()` calls → allowed**, three runs out of three;
- **three distinct documents → denied**, and **five → denied**, three runs out of three.

The ceiling counts *documents*, not *calls*. The approved rule fits, and it fits with margin on
the call count but none on the document count: **a third `firestore.get()` target is a breaking
change to the Storage contract**, not a refactor, and any change that adds one must be
re-measured live before it ships.

**The Storage emulator cannot be used for this check.** MISSION E0 / PR-0 (Draft PR #18) found
that the emulator enforces no document ceiling at all — it allowed three *and* five distinct
documents, i.e. the exact inverse of production behaviour. An emulator-green rules suite is
therefore evidence about logic and about deny reasons, and is **no evidence at all** about the
document budget. Keep the emulator suite; do not let it stand in for a live measurement.

**The IAM prerequisite is cheap.** Cross-service rules require
`roles/firebaserules.firestoreServiceAgent` on
`service-{projectNumber}@gcp-sa-firebasestorage.iam.gserviceaccount.com`. That grant was made
and rolled back on a throwaway project during F-a: the organization's Domain Restricted Sharing
policy (`iam.allowedPolicyMemberDomains` = `["C01qej65i"]`) **did not block it**, even though the
principal is a Google-owned service agent outside the customer, and the removal left the policy
byte-identical apart from its etag. Production wiring needs no org-policy exemption and no
service-account key.

## FS-1 — Feedback attachments on Cloud Storage

Added 2026-09-28 with FS-1 PR-A. Nothing below is deployed: production Storage still carries the
deny-all release, and production Firestore still carries the Mission 1 set.

### The model

- One object per screenshot at `case-attachments/{ownerUid}/{caseId}/{aid}` in
  `gs://optiqon-voice-47498-eun2`. `firebase.json` names it only as the deploy target
  `feedback`; `.firebaserc` maps that target to the one bucket, so the only deploy that can
  reach it is `firebase deploy --only storage:feedback`, behind `storage-guard.mjs`.
- The authority is the Firestore document `cases/{caseId}/attachments/{aid}` with exactly
  `ownerUid, caseId, messageId|null, maxBytes, createdAt` at creation. It is written in one batch
  with the reservation `users/{uid}/uploads/{aid}`, the quota step, the case counter and, for a
  message, the message counter. **No `sha256` and no other content fingerprint**: the document
  outlives the bytes as a tombstone and must not identify them.
- The quota document `users/{uid}/quota/attachments` is created by the first upload's batch at
  count 1; later batches step it by one.
- Limits: 3 per message (the opening text counts as a message, `messageId == null`), 10 active
  per case. Active means not tombstoned; a tombstone releases the slot in the same batch.
- Deletion by the user is one Firestore write, `deleteRequestedAt`, one way. From that write on,
  both Storage `get` paths deny. The client never updates or deletes an object; physical removal
  and `purgedAt` belong to the server actor (FS-SA).
- Admin read needs the `admin` custom claim **and** `users/{uid}.adminActive == true` (with
  `adminActiveUntil` in the future if present). Nothing writes either yet; the grant belongs to
  the Gate.

### Lookup budget

Every Storage path reads at most two distinct documents: the attachment plus exactly one
`users/` document (the owner's on the owner paths, the caller's on the admin path). uid or
claim is compared before the first read, so a non-owner never pays for the owner's document.
`tests/deploy/storage-rules-text.test.mjs` pins that shape against the text, because the
emulator cannot (see the section above). The changed block is **not** byte-identical to the F-a
block that was measured live, so the budget must be re-measured in the Gate (P2).

The largest Firestore batch (reservation, quota, attachment, case, message) touches about
seven distinct documents by inspection, under the 20-per-batch access-call limit. It passes in
the emulator.

### Download tokens (plan 4.4)

A Firebase download URL is read without rules. Mitigation in three layers: (a) the client never
calls `getDownloadUrl`, pinned by a source contract test in PR-B; (b) the rules deny any
client-set custom metadata; (c) the server actor strips `firebaseStorageDownloadTokens` on
tombstone.

**Measured in the emulator (firebase-tools 13.35.1):** the emulator removes a client-chosen
`firebaseStorageDownloadTokens` from custom metadata *before* rules evaluation, so it is allowed
and not stored, and (b) is **unproven for that key**. Any other custom key is denied as designed.
The emulator mints no token on a plain upload. Neither fact says anything about production.

### What the FS-1 suites prove, and what they do not

- `tests/rules/fs1-cases.test.mjs` (Firestore emulator) covers the case, message, attachment,
  reservation, quota and tombstone rules. Mutation runs killed every mutant but one, and that one
  is equivalent: `caseSlotTaken` is called with `request.resource.data.attachmentFor`, so its own
  `after.attachmentFor == aid` check is a tautology. The "two attachments under one +1" attack is
  also refused by `quotaBound` (`lastUploadId`), so that test proves the combined defence rather
  than isolating the case binding.
- `tests/storage/case-attachments.test.mjs` (Storage + Firestore emulators) covers matrix rows
  N1–N21 and P1/P2 against documents in the real `firestore.rules` shape. It proves logic and deny
  reasons, **not** the document ceiling, not when a real resumable upload is evaluated, and not
  how production treats download tokens.
- Open provider questions, answerable only in the Gate:
  - **P1**: the SDK works against this imported, non-default bucket;
  - **P2**: the two-document budget holds for the changed block;
  - **P3**: when rules evaluate on a resumable upload, i.e. what `request.time` means for the
    72 h window;
  - **P4**: an authenticated client gets the same allow/deny for real;
  - **P5**: is a download token minted on upload, and is a client-chosen one honoured?
  - **P6**: does a token URL fail after tombstone and strip?
  - **P7**: server-actor latency against the candidate SLA;
  - **P8**: revocation is refused by the cloud (the open item in the section above).

## FS-S12 — Feedback lifecycle (two-phase, approvalGeneration, withdrawal intents)

Written in `firestore.rules` and the client; **not deployed**. Prod still runs the Mission 1
ruleset, so there are no case documents to backfill.

### The model

- A case or owner message is created `state: 'submitted'` and becomes `accepted` by a separate
  finalize write. `accepted` is one-way; no branch writes `state` or `acceptedAt` afterwards.
  Readers see only `accepted`; the owner reads back its own `submitted` to resume a lost ACK.
- Every create and finalize carries `approvalGeneration`, which must equal
  `users/{uid}.approvalGeneration` (missing = 0). A writer approve from `pending | revoked |
  rejected` must bump it by exactly one; revoke and reject leave it. The client holds a row
  stamped with an older generation (HELD) and restamps it only on an explicit Send.
- The device's generation can lag the server's. After any refusal of a case, message, finalize,
  screenshot commit or screenshot put, the client reads `users/{uid}` from the server (never the
  cache): not `approved`, or a generation other than the row's, holds the row; the same approval
  leaves the refusal final (closed, foreign, full); an unanswered read retries. A row whose stale
  stamp the device already knows is held without any remote call.
- `activityRev` + `lastRelevantAt` move by exactly one, only with: owner-message finalize,
  screenshot commit, public writer reply, status change. Never with internal note, read, system,
  retention, close or tombstone.
- `retentionState == 'purging'` and `withdrawnAt` refuse every relevant write.
- `users/{uid}/withdrawals/{targetId}`: ID-only, create-only intent, open to a verified account in
  any status (M3=A), self-healing window of 20/h. It blocks later create/finalize/attach of that
  id. It **deletes nothing** and cannot change anything `accepted`.

### What the server (S4) must hold, because the Admin SDK bypasses these rules

- Act on a withdrawal only for a target the intent's uid owns, and only while it is `submitted`.
  An `accepted` case, message or attachment is never deleted by a withdrawal.
- Any server approve writes `approvalGeneration = old + 1` in the same transaction as the status
  (`scripts/admin/m1-bootstrap.mjs` does); a repeated approve is a no-op.
- Any server write that counts as activity bumps `activityRev` by one and sets
  `lastRelevantAt`; none of the non-bumping kinds above may.

### Still open for FS-G

Rules deploy; `approvalGeneration` backfill on existing prod users; the S4 server (withdrawal
handler, submitted sweep, retention R1–R6); live verification. FS-S34 below supplies the S4
withdrawal handler and the submitted sweep as repository code; their provider side is still FS-G.

## FS-S34 — Discard as withdrawal (S3) and server reconciliation (S4)

Repository only. Nothing was deployed, and no provider resource was created or changed. This
mission leaves `firestore.rules` and `storage.rules` **unchanged**: S3 and S4 use the FS-S12
withdrawal contract as it was already written.

### S3 — the client

- Some rows may already have reached the server: a HELD row, or a queued create or message that
  was tried at least once. Discarding one of these queues an ID-only withdrawal row
  (`feedback_withdrawal`) instead of dropping it silently.
- The withdrawal row is never held. It needs auth and a verified address, but not a current
  approval (M3=A), so a revoked or pending account can still take back what it wrote.
- The row writes the intent once. It reads before it writes, so a lost ACK, a retry or a restart
  never writes twice. It then waits for the server's verdict and keeps it on the row:
  - `withdrawn`: the target is gone.
  - `ignored_accepted`: the row ends SENT, and the Feedback screen says "already received" once.
  - `ignored_foreign`: nobody else is affected.
  - `absent`: reconciled locally.
- Not knowing is never final. A refusal that did not land is final.
- Send and Discard of a held row exclude each other. Nothing is sent after a reapproval without an
  explicit Send. Attachment delete keeps its existing tombstone semantics.
- Known residual: a row that has not been attempted yet may be in flight at the moment of a
  discard. It leaves no intent. If its first commit lands, the 30-day sweep removes the
  `submitted` leftover.

### S4 — the server core (`server/`)

- `server/withdrawal.mjs`:
  - `reconcileWithdrawal` handles one intent.
  - `reconcilePending` is the backstop over every intent without an outcome.
  - `sweepSubmitted` is the 30-day sweep.
- `server/config.mjs` provides `loadConfig(env)`:
  - Project lock `optiqon-voice-47498`; a test keeps it equal to the M1 bootstrap's lock. Any
    other project is refused unless `FIRESTORE_EMULATOR_HOST` is set.
  - It writes only when `APPLY` is exactly `true`. Anything else is a dry run.
  - `SWEEP_TTL_DAYS` must be at least 30 and defaults to 30.
- `server/main.mjs`:
  - `onWithdrawalCreated(cloudEvent)` is the handler for a document-created trigger.
  - `scheduledRun()` is for a scheduled job: the backstop, then the sweep.
  - A CLI: `node server/main.mjs reconcile|sweep [--apply]`, a dry run by default.
- `firebase.json` names none of this; a deploy-guard test keeps it that way.

The Admin SDK bypasses the rules, so these invariants are held in code:

- Only `submitted` is removed. `accepted` is received history and is **never** removed by a
  withdrawal or the sweep. So is a legacy document with no state.
- A case is removed only if the intent's uid owns it and it has no events or attachments. The rules
  make children impossible; if there are some anyway, the outcome is `ignored_inconsistent` and a
  person handles it.
- A message is removed only if it is `type == message`, its `actorUid` is the intent's uid, and it
  has no screenshot. The parent case and every other event stay untouched, and the case is not
  bumped.
- Removal has two phases:
  1. Stamp `withdrawnAt` on the target. From then on the rules refuse finalize.
  2. In a second transaction, re-read everything and delete only a target that is still
     `submitted` and still stamped.

  If the process crashes between the two, the next run finishes the job.
- The outcome is written once, with `reconciledAt`, and read back on every retry, duplicate or
  restart. An attachment intent gets `not_applicable`; a malformed one gets `ignored_invalid`.
- The sweep writes no intent, tombstone or fingerprint, so the same id may be sent again (a held
  Send). It never resurrects anything; it only deletes.

### What the repository proves

`tests/server/*.test.mjs` runs under `npm run test:rules`, against the emulator, with the deployed
`firestore.rules` in front of the clients. It covers:

- every outcome above;
- duplicate, retry and restart, and a crash between the phases;
- finalize vs withdrawal in both orders, and a state that changes between the phases;
- sweep vs finalize and sweep vs withdrawal, each in both orders;
- a stale client's late create;
- repeated reconciliation and repeated sweeps;
- a dry run writing nothing, the TTL floor, and the project lock.

A mutation check that removes the `submitted` test turns six of these tests red.

### What FS-G must still prove on the provider

- The deploy itself: runtime, region, and a service account whose IAM is Firestore read/write only.
- The trigger (Eventarc document-created on `users/{uid}/withdrawals/{targetId}`) and the
  scheduled job, and that the backstop catches a failed trigger.
- The indexes the emulator does not need: composite indexes for the sweep on `cases`
  (`state`, `createdAt`) and on collection group `events` (`state`, `createdAt`), plus the
  `messageId` lookup on `attachments`.
- The same races live, with real latency.
- The rules release that lets S3 reach a server.

### Boundaries not decided here

- **M4**, self-service account deletion, is not touched. The withdrawal handler and the sweep act
  on single documents only.
- **M5**, the physical-deletion SLA for screenshots, is not touched. Attachment intents are
  `not_applicable`, and S4 never deletes a Storage object.
- **M6**, what "immediate" means for an offline delete, is not decided. S3 delivers the intent
  when the device is next online, and the UI claims nothing about timing.
