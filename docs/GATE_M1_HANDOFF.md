# Gate M1 — handoff to a local session

Durable state for continuing the Firebase activation Gate. Not a plan; the plan is settled.
This exists because the cloud session that did the rules work cannot authenticate to Firebase,
and the remaining preflight is all provider readback.

## Where the work is

- Branch `claude/firebase-activation-gate-m1-iy96xc`, commit **`78f366a`**, branched from the
  Mission 1 head `1b8d0a5`. In sync with origin. **Do not restart Mission 1 and do not redo
  the rules work.**
- `firestore.rules` — the Mission 1 set, and the only file `firebase.json` names.
  sha256 `42f7fc81fcd2d69facc7ade25128f914f94860b8e3c47d66b608ba74e1fa101b`
- `firestore.future.rules` — the full F1 set, preserved, not deployed.
  sha256 `1c7c12cdff9b431adaf4ee06af8c1e1d6f81ebfd993f4cf51542ce12eb816ab8`
- `npm run test:rules` — **121 passing, 21 suites.** 19 of the 25 assertions in
  `revocation.test.mjs` fail against the pre-`78f366a` rules, which is what makes the guard
  load-bearing rather than decorative.

## Why this could not be finished in the cloud session

Established by running the commands, not by assumption:

- No Firebase MCP server is available in that session.
- Firebase APIs are reachable (`firebase.googleapis.com` and friends answer through the proxy),
  but `firebase login` refuses: *"Cannot run login in non-interactive mode."* There is no TTY.
- The CLI's own suggestion, `login:ci`, produces a long-lived token that would have to be
  pasted in, and would put a credential for Lars's Google account inside an ephemeral cloud
  container. That is a new credential, which the Gate boundary excludes, so it was not done.
- `dl.google.com` is egress-blocked (403 at the proxy), so no Android SDK and no Google Maven:
  the Android Gradle Plugin cannot resolve, which means the app build cannot even be
  *configured* there, let alone run. Any Gradle change made there would be unverifiable.

## Readback, 2026-09-09 — and why it changed the plan

Done from a local session authenticated as `lars@optiqon.se`. All four values came back.
Two of them changed what happens next.

| | |
|---|---|
| Project | `optioqon-voice`, number `642507220744`, GCP org `optiqon.se` |
| Firestore | **`(default)`** — the named-database trap does not apply. Native mode, `europe-north2` (Stockholm). Created 2026-09-07, **empty** |
| Active ruleset | Firestore's auto-generated **deny-all**. Nothing has ever been deployed |
| Android app | `1:642507220744:android:78fc7b80e32b84262baebd`, package `se.optiqon.voice`, **no SHA registered** |
| Hosting | default site `optioqon-voice` → `optioqon-voice.web.app`. assetlinks: **404 Site Not Found** |
| P4 | **Not done.** That machine had neither `ANDROID_HOME` nor a connected device |

**The project id carries a typo — `optioqon`, not `optiqon` — and a Firebase project id cannot
be renamed.** It matters more than a cosmetic slip because it becomes the email sign-in link's
domain: a login link pointing at `optioqon-voice.firebaseapp.com` reads as a misspelling of the
company's own name, which is the exact shape people are taught to distrust in sign-in mail. The
project name and public-facing name are spelt correctly; only the id, the one immutable part,
is wrong.

Decision: **a new project with the correct id.** Nothing is built on the old one yet — empty
database, no rules, no SHA, no client config — so this is the cheapest it will ever be. Once
the Gate runs, the id is baked into every installed client's `google-services.json`, and
changing it then means re-registering the app and every user.

**Correction to this document.** It previously said Hosting serves
`/.well-known/assetlinks.json` for the default domain automatically, so nothing needs
publishing by hand. That is not true of a project in this state: the path returns
**404 Site Not Found**. Two causes are possible and both have to be cleared —
no SHA is registered, so there are no fingerprints to generate the file from; and the Hosting
site may never have had a release, in which case the whole domain 404s. Which one it is can
only be settled by registering the SHA and testing again. If it still 404s, an initial Hosting
release is required, and that is a live action needing its own approval. Until it serves,
email-link sign-in cannot reach the app at all.

The same missing SHA also blocks **Google sign-in**: Credential Manager requires a registered
SHA-1.

## What is still missing

1. The new project: id, number, Firestore `(default)` in `europe-north2`, Android app
2. assetlinks status once a SHA is registered
3. P4: the signing certificate of the installed build

## Read-only readback

Every command below reads. None writes. Command names verified against firebase-tools 13.35.1.

```bash
firebase login                                     # real browser, no token pasting
firebase projects:list                             # → project ID and project number
firebase apps:list ANDROID --project <id>          # → app id; must be se.optiqon.voice
firebase apps:android:sha:list <appId>             # → already-registered fingerprints
firebase firestore:databases:list --project <id>   # → is it (default), or a named database?
firebase firestore:databases:get '(default)' --project <id>   # → region, Native vs Datastore
firebase hosting:sites:list --project <id>         # → the default site
```

The active ruleset has no CLI read command in 13.35.1 — take it from the console, Firestore →
Rules → history, and save it verbatim. It is the rollback target and nothing should be
deployed before it exists somewhere.

**Named-database trap.** `firebase.json` has no `firestore.database` key, so a deploy targets
`(default)`. If the existing database is a named one, the reviewed rules land on a database
nobody uses, **no error is raised**, and the app keeps running on whatever rules are actually
live. This is the only silent failure mode in the whole deploy. Check it before anything else.

## Client config

```bash
firebase apps:sdkconfig ANDROID <appId> --project <id> > app/google-services.json
```

Redirect it to the file. Do not print it into a conversation: it carries the project's API key.
It is gitignored and must stay that way.

## P4 — signing inventory, before any install

Needs a machine with `ANDROID_HOME` and the physical device. The repo already has the right
tool; reuse it rather than inventing a command, since it normalises colons and case:

```bash
adb shell pm path se.optiqon.voice
adb pull <path> /tmp/installed.apk
.github/scripts/verify-apk-signer.sh /tmp/installed.apk \
  23:4E:28:33:E3:E7:4D:72:1B:31:6C:0D:B5:E8:7E:11:2B:3F:74:ED:5F:76:D3:4E:5D:32:08:C5:04:42:9E:ED
```

That fingerprint is this repo's `debug.keystore` (`androiddebugkey`, `CN=Debug, O=Sasayaki`),
verified. Exit 0 means an in-place upgrade preserves the device's data. Any other result means
a different key: **do not uninstall.** `android:allowBackup="false"` means `adb backup` captures
nothing, so on a non-rooted device there is no supported way to save the profiles, keys and
history first. Use a separate device instead.

## The remaining code change

The App Link filter for the email sign-in link is **not** applied yet, deliberately.

Since Dynamic Links was retired, Firebase Auth builds the link on the project's default
Firebase Hosting domain — `<project-id>.firebaseapp.com` — not on the continue URL. The
manifest today only has a filter for `voice.optiqon.se/signin`, which is the continue URL and
never receives the link. So email-link sign-in cannot currently reach the app.

Firebase Hosting is *supposed* to serve `/.well-known/assetlinks.json` for that default domain,
built from the SHA-256 fingerprints registered on the project's Android app. On the old project
it returned 404 — see the correction above. Verify, never assume:

```bash
curl -s https://<project-id>.firebaseapp.com/.well-known/assetlinks.json
```

It must name `se.optiqon.voice` and the fingerprint of the build under test.

The fix is a second `<intent-filter>` on host `<project-id>.firebaseapp.com`, path prefix
`/__/auth/links`. The host should come from `project_id` in `google-services.json` via a
`manifestPlaceholder` rather than being hardcoded, so no checked-in project id becomes a guess
on another machine. `MainActivity.offerSignInLink()` needs no change — it reads `intent.data`
generically and validates with `auth.isSignInWithEmailLink()`.

The existing `voice.optiqon.se` filter carries `autoVerify="true"` and will report as
unverified, since no assetlinks file is published there. That is expected, not a fault to chase.

Build and run `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug` after making it —
that change was never compiled anywhere.

## Boundary

Still ungranted, and unchanged: rules deploy, provider changes, SHA registration, installation,
new credentials, data deletion, merge. Readback only until the Gate box is filled and approved.

---

## Update 2026-09-09 — preflight done, three of four inputs filled

The previous section recorded the *old* project and the decision to abandon it. Lars has now
created the replacement, and the same readback has been run against it on his machine. Results: [`gate-m1/PREFLIGHT.md`](gate-m1/PREFLIGHT.md).
Rollback ruleset saved verbatim: [`gate-m1/rollback-ruleset-4937759b.rules`](gate-m1/rollback-ruleset-4937759b.rules).

Of the four missing values, three are now known:

1. **Project ID / number** — `optiqon-voice-47498` / `699805184613`.
   Not `optiqon-voice`; that is only the display name. The old `optioqon-voice` (642507220744)
   was read and left alone.
2. **Database** — `(default)`, `FIRESTORE_NATIVE`, `europe-north2`. The named-database trap
   does not apply.
3. **Active ruleset** — `4937759b-d4a7-4e95-b59a-22d363dac881`, the console locked default
   (`allow read, write: if false`), sha256 `ecf30f94…d84eb`. Only release ever created.
4. **P4 signing certificate** — **done, and it matches.** The installed `se.optiqon.voice`
   (`v20260906`, code `2026090600`) on device `c1f9837c` / CPH2645 is signed by the repo's
   `debug.keystore`: SHA-256 `234e2833…429eed`, SHA-1 `3326d33e…d3ccf4`, DN
   `CN=Debug, O=Sasayaki, C=US`. The repo's `verify-apk-signer.sh` exits 0 against it.
   **An in-place upgrade preserves the device's data — no uninstall, no separate test device.**

Everything else is unchanged: nothing was deployed, no provider touched, no SHA registered,
nothing installed, nothing merged.

### The remaining Gate box, exactly

All Gate *inputs* are now filled; everything below is a write and each needs Lars's approval.

1. ~~P4 signing inventory~~ — **done 2026-09-09, PASS.** See `gate-m1/PREFLIGHT.md`.
2. Register **both** fingerprints on app `1:699805184613:android:35a5860f14504f1e130c8c`:
   SHA-256 `23:4E:…:9E:ED` and SHA-1 `33:26:…:CC:F4`. SHA-1 is what Credential Manager needs.
3. `firebase deploy --only firestore:rules --project optiqon-voice-47498` — rollback target is
   ruleset `4937759b-…`, which denies all reads and writes.
4. Add the App Link `<intent-filter>` for host `optiqon-voice-47498.firebaseapp.com`, path
   prefix `/__/auth/links`, host injected from `project_id` via `manifestPlaceholder`. Then
   `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
5. Enable the email-link auth provider — a provider change.
6. Verify `https://optiqon-voice-47498.firebaseapp.com/.well-known/assetlinks.json` names
   `se.optiqon.voice` and the fingerprint under test. It cannot pass before step 2, and it
   already 404s with "Site Not Found" on the new project exactly as it did on the old one, so
   budget for an initial Hosting release as well — a separate approval.
7. Install, then merge.

### Separate provider actions, each its own approval

- **Delete protection is off** on `(default)`. Nothing in the Gate box deletes a database, so
  this does not block anything — but the guard that would stop an accidental deletion is not
  armed. Enabling it is a provider write and is *not* bundled into the rules deploy.
- **An initial Hosting release** is likely required before `/.well-known/assetlinks.json`
  serves. Both the `.firebaseapp.com` and `.web.app` hosts return "Site Not Found", which is
  what an un-released site serves for every path. Registering the SHA may not be enough on its
  own. This is a Hosting deploy and needs its own approval.

### Config file — secured 2026-09-09

Done, not pending. `google-services (1).json` was verified against the provider readback
(project id, project number, app id, package all match; sha256 `7f0c26ec…7ef9a5`) and moved
byte-identically to `app/google-services.json`, where the Gradle plugin expects it. No target
file existed, so nothing was overwritten.

The ignore rule was the real gap, and it was not about location: `google-services.json` is a
bare pattern that already matched at any depth. What it did not match was the name a *second*
browser download actually gets — `google-services (1).json`. The pattern is now
`google-services*.json`, verified to match at the root, in `app/`, and deeper, with and without
the ` (n)` suffix. Nothing matching has ever been committed on any ref, and no `AIza…` key
appears anywhere in history.

## Update 2026-09-09 (later) — local work finished, one box left

Both remaining local items are done, tested and committed. Nothing was deployed, no provider
was touched, no SHA was registered, nothing was installed, nothing was merged.

### The seat counter is now bound to the decision it counts

Lars found that a writer could move `config/counters.approvedUsers` by ±1 with no account
changing status. The counter is not bookkeeping — it is compared against
`config/limits.maxApprovedUsers` to decide whether anybody else may be approved — so a
standalone movement either manufactures headroom or burns seats nobody holds.

Fix, in `firestore.rules` (commit `2ab7b5e`): the counters document carries `seatFor`, naming
the account a movement is spent on. The `config/counters` update rule now requires that
account's before/after status to match the direction (+1 needs `pending → approved`, −1 needs
`approved → rejected|revoked`), and `seatTaken(userId)` / `seatReleased(userId)` additionally
require `getAfter(counters).seatFor == userId`. That second half closes a hole the first does
not reach: without it one batch could approve two accounts against a single +1, since each
`users/` write reads the same before/after counter. Rules cannot count the documents in a
commit, so naming the one account a seat belongs to is what makes "one decision per movement"
expressible at all.

The M1/F1 rule split is unchanged, every future path is still closed, and
`firestore.future.rules` is untouched (sha256 still `1c7c12cd…16ab8`).

- **New rules hash — this is the deploy target:**
  `firestore.rules` sha256 `21f39fe57a29c8767ab1c64d6eb3feed8a763574cf0afb28ea48f4a4ca49553d`
  (was `42f7fc81cd…1e1a101b`). Hash the **git blob**, not the working tree: the checkout is
  CRLF and `sha256sum` on it will not reproduce this.
- **RED proof:** 19 new tests in `tests/rules/seat.test.mjs`; **7 fail against the previous
  rules** — the standalone raise and lower, the unnamed write, the write naming an account
  that does not exist, a seat paid to a bystander while somebody else is approved, two
  approvals sharing one seat, and lowering the counter to walk around the cap. The other 12
  passed before and still pass, which is the point: the real transitions are untouched.
- **GREEN:** `npm run test:rules` → **140 passing, 25 suites, 0 failures** (was 121 / 21).
  The emulator needs a JDK on PATH; use `~/.jdks/jbr-21.0.11`.
- No app code writes `config/counters`, so the contract change reaches only an out-of-band
  admin writer, not the shipped app.

### The App Link filter is implemented and built

Commit `6194d73`. Second `<intent-filter>` on `MainActivity`, host injected from
`project_info.project_id` in `app/google-services.json` via `manifestPlaceholders`, path prefix
`/__/auth/links`, `autoVerify="true"`. A checkout without the config still builds; the host
falls back to a `.invalid` name so the filter is valid and inert rather than aimed at a host
the project does not own.

Verified on the built debug APK (**not installed**):

| Check | Result |
| --- | --- |
| Merged manifest host | `optiqon-voice-47498.firebaseapp.com`, prefix `/__/auth/links` |
| versionCode | `2026090900` — above the installed `2026090600` |
| Signer SHA-256 | `234e2833e3e74d721b316c0db5e87e112b3f74ed5f76d34e5d3208c504429eed` — same identity as installed |
| Signer SHA-1 | `3326d33e30e035999b9a3c5c2fce404a06d3ccf4` |
| Unit tests | 296 in 45 classes, 0 failures, 2 skipped |

Build with `JAVA_HOME=C:\Users\ahlst\.jdks\jbr-21.0.11`. Android Studio's bundled `jbr` is now
JDK 25, which Gradle 8.11.1 rejects.

### The one Gate box, filled

Everything below is a provider write against `optiqon-voice-47498` and needs Lars's approval.
Once approved the four actions run as one sequence with readback — no further internal
reporting barriers.

1. **Register both fingerprints** on Android app `1:699805184613:android:35a5860f14504f1e130c8c`
   (package `se.optiqon.voice`):
   - SHA-1 `33:26:D3:3E:30:E0:35:99:9B:9A:3C:5C:2F:CE:40:4A:06:D3:CC:F4`
   - SHA-256 `23:4E:28:33:E3:E7:4D:72:1B:31:6C:0D:B5:E8:7E:11:2B:3F:74:ED:5F:76:D3:4E:5D:32:08:C5:04:42:9E:ED`
2. **Enable delete protection** on `projects/optiqon-voice-47498/databases/(default)`.
3. **Deploy only the new M1 ruleset** to that same `(default)` database:
   `firebase deploy --only firestore:rules --project optiqon-voice-47498`. `firebase.json` names
   `firestore.rules` and has no `firestore.database` key, so `(default)` is the target and the
   future file cannot be reached by accident.
4. **Read back all three** — that the app carries exactly those two fingerprints, that delete
   protection reads as enabled, and that the active ruleset's content is the one just built.
   Note what a readback actually returns: the CLI uploads the **working-tree** bytes, which are
   CRLF here, so the released file hashes to `97ab0646…0a3f40` (12108 bytes), not to the git-blob
   hash `21f39fe5…49553d` (11833 bytes). Same content, different line endings — see the
   postflight below. Then fetch
   `https://optiqon-voice-47498.firebaseapp.com/.well-known/assetlinks.json` and check it names
   `se.optiqon.voice` with the SHA-256 above.

**Rollback:** redeploy the saved deny-all ruleset
[`gate-m1/rollback-ruleset-4937759b.rules`](gate-m1/rollback-ruleset-4937759b.rules)
(ruleset `4937759b-d4a7-4e95-b59a-22d363dac881`, sha256 `ecf30f94…d84eb`).

**Not in this box:** no Hosting deploy, no provider (auth) change, no bootstrap or seeding, no
install, no merge, no data deletion, no cleanup. The old project `optioqon-voice`
(642507220744) is never a fallback.

**If assetlinks still 404s after step 4** — which is likely, since both
`optiqon-voice-47498.firebaseapp.com` and `.web.app` currently return "Site Not Found", the
response an un-released Hosting site gives for *every* path — the exact minimal next action is:

```
firebase deploy --only hosting --project optiqon-voice-47498
```

with a `hosting` block in `firebase.json` pointing at an otherwise empty public directory. That
is the smallest thing that creates a first release; Firebase then serves
`/.well-known/assetlinks.json` from the registered fingerprints automatically, and nothing has
to be authored by hand. It is a Hosting deploy and belongs to the next Gate, not this one.

Also still open and unbundled: enabling the **email-link auth provider**, and finally
**install + merge**.

## Postflight 2026-09-09 — the box was approved and executed

Lars approved the box ("Godkänd — kör boxens fyra åtgärder med readback"). All four actions ran
as one sequence against `optiqon-voice-47498`. Nothing outside the box was touched: no Hosting
deploy, no auth-provider change, no bootstrap or seeding, no install, no merge, no deletion. The
old project `optioqon-voice` (642507220744) was never contacted.

**Preflight, before any write**

| Thing | State |
| --- | --- |
| SHA hashes on the Android app | none — "No SHA certificate hashes found" |
| `(default)` database | `FIRESTORE_NATIVE`, `europe-north2`, `DELETE_PROTECTION_DISABLED`, PITR disabled |
| `firebase.json` | names `firestore.rules`, no `firestore.database` key |

**1 — Fingerprints registered.** Both `apps:android:sha:create` calls succeeded on
`1:699805184613:android:35a5860f14504f1e130c8c`.

**2 — Delete protection enabled.** "Successfully updated
projects/optiqon-voice-47498/databases/(default)".

**3 — Rules deployed.** `firebase deploy --only firestore:rules --project optiqon-voice-47498`:
compiled successfully, "released rules firestore.rules to cloud.firestore", "Deploy complete!".

**4 — Readback**

- **Fingerprints:** exactly two, no extras — `3326d33e30e035999b9a3c5c2fce404a06d3ccf4` (SHA_1,
  id `a737473cece802b7`) and
  `234e2833e3e74d721b316c0db5e87e112b3f74ed5f76d34e5d3208c504429eed` (SHA_256, id
  `93241816e3c34b0a`).
- **Database:** `DELETE_PROTECTION_ENABLED`, still `FIRESTORE_NATIVE` / `europe-north2`.
- **Active ruleset:** release `projects/optiqon-voice-47498/releases/cloud.firestore` →
  ruleset `f5588727-03da-4205-8763-0c9891d959b1`, updated `2026-09-09T06:46:00.449842Z`.
  File `firestore.rules`, 12108 bytes, sha256
  `97ab0646bda54acefd1fcc8501ad20999df687d8946763f0b44705f1700a3f40`. Content assertions on the
  live text: `seatFor`, `seatTaken(userId)`, `seatReleased(userId)` and `userStatusAfter` all
  present. There is no public firebase-tools command for this; the readback goes through the
  CLI's own `lib/gcp/rules.js` with the CLI's own credentials.

**About the two hashes.** The released bytes hash to `97ab0646…0a3f40`, the committed git blob to
`21f39fe5…49553d`. That is not a content difference: the CLI uploads the working tree, which is
CRLF on this machine. Proven, not assumed — taking the git blob (11833 bytes,
`21f39fe5…49553d`) and replacing every `\n` with `\r\n` gives 12108 bytes and exactly
`97ab0646…0a3f40`, and the working-tree file with CRLF normalised back to LF is byte-identical to
the blob. **A provider readback returns `97ab0646…0a3f40`.** The git-blob hash is what to compare
a checkout against, not what to compare the cloud against.

**assetlinks: still 404, and the documented next action needed a correction.**
`https://optiqon-voice-47498.firebaseapp.com/.well-known/assetlinks.json` returns **404 "Site Not
Found"**, and so do `https://…firebaseapp.com/` and `https://…web.app/` — every path, which is
what an un-released Hosting site returns. `hosting:sites:list` shows the site `optiqon-voice-47498`
does exist (`https://optiqon-voice-47498.web.app`) but has never had a release. Registering the
fingerprints therefore changed nothing here, exactly as anticipated: the SHAs are stored on the
app, but nothing is serving them.

The command written down earlier is *not* sufficient on its own — `firebase.json` currently has no
`hosting` key, so `firebase deploy --only hosting` fails before it reaches the network. The exact
minimal next-Gate action is: add a `hosting` block naming an (otherwise empty) public directory,
then

```
firebase deploy --only hosting --project optiqon-voice-47498
```

That first release makes Firebase serve `/.well-known/assetlinks.json` from the fingerprints now
registered; the file is not authored by hand. It is a Hosting deploy and is **not** approved.

**Rollback is unchanged:** redeploy `gate-m1/rollback-ruleset-4937759b.rules` (deny-all, ruleset
`4937759b-d4a7-4e95-b59a-22d363dac881`, sha256 `ecf30f94…d84eb`). Note that delete protection is
now on, which is deliberate and separately reversible via
`firestore:databases:update "(default)" --delete-protection DISABLED`.

**Still open, still unapproved:** Hosting deploy, enabling the email-link auth provider,
install, merge.

## Update 2026-09-09 (L1 done) — next box is G1+G2, not yet run

Mission L1 (plan rev. 4 §R4) is finished locally at `68ace89`; the details above that
predate it are superseded on three points: `public/.well-known/assetlinks.json` and
`public/signin/index.html` are now authored in the repo and `firebase.json` has the
`hosting` block, so `firebase deploy --only hosting` is a valid command again; Google
sign-in is the primary provider and the e-mail link the secondary one, so G1 enables
both; and the continue-URL is `https://optiqon-voice-47498.web.app/signin` (D4 default,
`voice.optiqon.se` filter removed).

**State as verified 2026-09-09 (read-only):** branches
`claude/firebase-activation-gate-m1-iy96xc` and `feat/m1-registration-access` both at
`68ace89`, PR #3 draft on that head, 22 commits ahead of `main`; Authentication still not
initialised, Hosting still 0 releases (`/.well-known/assetlinks.json` → 404, `/__/auth/links`
→ 200), `app/google-services.json` still has 0 OAuth clients; active ruleset
`f5588727-03da-4205-8763-0c9891d959b1`, rollback `4937759b…` (file above).

**Next approved box:** G1+G2 per [`gate-m1/G1_G2_RUNBOOK.md`](gate-m1/G1_G2_RUNBOOK.md)
(Authentication console steps + first Hosting release of `public/` only). **Not run.**
**Not approved:** G3 (smoke on the device, [`gate-m1/G3_SMOKE_TEMPLATE.md`](gate-m1/G3_SMOKE_TEMPLATE.md)),
signing rotation / real beta key (G4, after D2 and D8 —
[`BETA_SIGNING_AND_DISTRIBUTION.md`](BETA_SIGNING_AND_DISTRIBUTION.md)), distribution (D3),
merge of PR #3. Open decisions: D2 (method for the existing installation), D8 (Android floor),
plus D3/D5/D2b/D7. D6 was settled on 2026-09-09 — see the decision entry below.
What is still unproven is listed in [`BACKEND_OPEN_CONTRACTS.md`](BACKEND_OPEN_CONTRACTS.md) F5.

## Update 2026-09-09 (G1+G2 run) — Authentication live, first Hosting release

G1 and G2 were run on 2026-09-09 from `eeed194`, evidence in
[`gate-m1/GATE_2_READBACK.md`](gate-m1/GATE_2_READBACK.md). Authentication is initialised in
`optiqon-voice-47498` with Google (primary) and Email/Password + email link (secondary);
authorized domains are the two default Hosting domains plus `localhost`; 0 users. The local
`app/google-services.json` now carries 2 OAuth clients (1 web) and the build generates
`default_web_client_id`. Hosting has exactly one release on `live` serving `public/`
(assetlinks + `/signin` fallback); the Digital Asset Links API lists `se.optiqon.voice`.
Active ruleset unchanged: `f5588727-03da-4205-8763-0c9891d959b1`.

One deviation from the runbook text: `/signin` answers 301 → `/signin/` (Hosting's directory
index redirect, query preserved) rather than 200 directly; the postflight script was adjusted to
follow it. **Next box:** G3 (device smoke, [`gate-m1/G3_SMOKE_TEMPLATE.md`](gate-m1/G3_SMOKE_TEMPLATE.md)),
which needs D6 decided first. Still not approved: G3, signing rotation (G4), distribution (D3/G5),
merge of PR #3. The old project `optioqon-voice` was not opened.

## Decision 2026-09-09 — D6 settled: 72 h offline grace as beta policy

Lars decided **D6 = 72 h**, the proposed value. This is the last decision G3 was waiting on.

**No code change follows.** The value was already the constant
`AccessGate.BETA_GRACE_MS` (`72L * 60L * 60L * 1000L`), reached through
`AccessConfig.graceMs`, and every test that pins a grace value already pins this one. G3 can
be built from the current head without touching app code.

**Wording folded in 2026-09-10.** The constant was named `PROPOSED_GRACE_MS` and the KDoc in
`AccessGate.kt`, `AccessRepository.kt` and `AccessModule.kt` still called 72 h "proposed" and
"not an approved live policy". Renamed to `BETA_GRACE_MS` and reworded to match the decision.
No behaviour change; the literal is untouched.

**What D6 governs and what it does not.** It governs how long an *already approved* device may
keep dictating without reaching the server. It does not govern cloud access: `firestore.rules`
has no grace, so a revoked account is refused at its next request regardless of this value.
Online the device re-checks every 15 minutes (`AccessRefresher.CHECK_IN_INTERVAL_MS`), so the
grace window only applies when Firestore is genuinely unreachable. A moved clock can only
shorten the window (`AccessGate.isWithinGrace` takes the larger of the wall and elapsed ages and
treats a negative age as spent). G3 step 9 exercises expiry through the debug clock offset, not
by waiting, so the length of the window does not slow the smoke run.

**Scope of the decision:** beta policy for ≤10 known testers, not a V1 policy. The residual risk
accepted is that a revoked tester may keep dictating locally, with their own API keys and at
their own cost, for up to 72 h while offline.

**Still open:** D2 (method for the existing installation), D8 (Android floor), D3/D5/D2b/D7.
**Still not approved:** G3, G4, distribution, merge of PR #3.

## Fix 2026-09-09 — the bootstrap tool's live Firestore client (found in G3 preflight)

`scripts/admin/m1-bootstrap.mjs status` failed on its first real run with
`firestore/invalid-credential`. The cause is in `firebase-admin` itself: its Firestore factory
accepts **only** a certificate credential or application default credentials, and rejects the
Firebase CLI's OAuth refresh token outright (`firestore-internal.js`, `getFirestoreOptions`).
`getAuth()` accepts the same credential without complaint, which is why the Auth half of the
tool had always looked fine.

The defect had never been caught because every one of the 22 bootstrap tests runs under
`FIRESTORE_EMULATOR_HOST`, and that variable short-circuits the credential path entirely. The
live branch had literally never executed.

**Fix:** the live run now builds the Firestore client directly from `@google-cloud/firestore`
with a `UserRefreshClient` carrying the CLI's existing refresh token (`liveFirestore()`). That
is the same client class `firebase-admin` re-exports, from a single hoisted install, so
`FieldValue` sentinels stay interchangeable and the transaction preconditions, seat counting,
idempotency and audit semantics are untouched. **No new credential, service account, key file,
`gcloud` install or changed permission model** — the credential, the project lock and the admin
identity are exactly what they were.

**Verified:** `npm run test:rules` 166/166 in 25 suites (162 as before, plus four new tests in
`tests/admin/live-credential.test.mjs` that exercise the live branch with a fake token, no
network and no writes — including a regression guard asserting that `getFirestore()` on a
refresh-token app still throws). A live read-only probe returned the empty `users` collection,
and `status` now reaches its real Auth lookup and aborts correctly with
`ADMIN_NOT_IN_AUTH` — expected while the Auth user count is still 0.
