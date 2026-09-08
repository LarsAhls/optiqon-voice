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

- Email-link sign-in needs SDK >= 23.2.0, a Hosting domain and an App Links intent
  filter for `/__/auth/links`; Dynamic Links are gone. Not yet verified end to end.
- The actual set of signed and installed builds has not been inventoried. Any SHA or
  signing-identity change is a separate approved action.
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

## What the F1 rules suite does and does not prove

The suite in `tests/rules/` runs against the Firestore emulator and covers negative
tests 1-13. Two limits are worth stating plainly, because a green run invites more
confidence than it has earned:

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
- **Legacy adoption is untested because it does not exist.** Pre-account files stay in
  `files/legacy` and are unreadable through `isReadableBy`; migrating them is a separate
  decision, and there is no code to test until it is made.
