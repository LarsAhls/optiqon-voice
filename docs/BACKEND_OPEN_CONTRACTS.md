# Backend V1 — open contracts

Durable record of what is **not** settled. Written during Mission 1 (F0–F2) so the
later phases do not have to rediscover it. Nothing here is approved work; each item is
a dependency or an explicit stop.

## F3 — attachments (Worker + R2): hard stop before deploy

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
  `hosting` block in `firebase.json`, but **Hosting has not been deployed and
  Authentication is not initialised** (identitytoolkit still answers
  `CONFIGURATION_NOT_FOUND`). The route is therefore still unproven; G1+G2 in
  `docs/gate-m1/G1_G2_RUNBOOK.md` are the Gate actions, G3 (`G3_SMOKE_TEMPLATE.md`) is the
  proof.
- Google sign-in is shown as primary and e-mail link as secondary by the account screen,
  but the built app still has 0 OAuth clients in `google-services.json`, so Google is
  unavailable until G1 delivers a new file (checked by `scripts/gate/check-google-services.sh`).
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

- Retention for attachments is proposed at 12 months. Not decided.
- Account deletion must cover Auth, Firestore, R2 and dependent documents. No live
  deletion without exact approval.
- Workers execute at the global edge, so request *processing* may happen outside the EU
  even though the bucket is EU-resident. This needs to be stated in the privacy text.

## Undecided policy values

- Local offline grace: **72 h is a test value**, not an approved live policy. It lives in
  configuration, not in code.
- Monotonic quota without refund: proposed for V1.

## Two rule sets: what is deployed, and what is merely written

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
