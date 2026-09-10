# G3 smoke template — install, bootstrap and the access loop on the device

Copy to `docs/gate-m1/SMOKE_<date>.md` when the box is opened ("kör G3"). **Not run.**
Everything below is prepared in Mission L1 and runs only on explicit approval, on device
`c1f9837c`, against project **optiqon-voice-47498**, with a **debug-signed** build (same key
as the installed app, so `adb install -r` is an in-place update: no uninstall, no clear data).

Accounts: admin **lars@optiqon.se** (Google, on the phone's Chrome/Play account list). Tester
= a **separate Google account Lars owns** (its own Firebase Auth `localId`; a plus-alias of
the admin address is *not* used unless step 0b proves it is a separate account).

Prerequisites: G1 and G2 PASS, `GATE_2_READBACK.md` filled in, D6 decided (grace constant),
`app/google-services.json` from G1 in place, `adb devices` shows `c1f9837c` only.

## Hygiene rules for this file

- Record **status, counts and SHA-256 digests**, never contents. No dictation text, no
  e-mail bodies, no ID tokens, no API keys, no `state.txt` copies.
- Screenshots only of screens showing synthetic or account-free content; anything with a
  personal address is kept outside the repo.
- The ID token exported in step 8 goes to a file in the session scratchpad, is used once and
  deleted in the same step.
- The debug hooks are reachable only through `adb shell am broadcast` (permission
  `android.permission.DUMP`) and only exist in the debug build (verified in L1).
- **Debug flags do not survive a process restart — re-assert them (finding F9, 2026-09-10).**
  `AccessDebugControls` holds `failRefresh` and `clockOffsetMs` as plain `@Volatile var`s in
  memory (`app/src/debug/.../AccessDebugControls.kt:24,27`); nothing is persisted. A broadcast
  sent to a **stopped** package starts a fresh process with both flags back at their defaults
  (`false` / `0`), and it does so **silently** — the broadcast still reports success, so the
  hook looks like it worked. Any step that spans time, a force-stop, a crash, a low-memory
  kill or an `install -r` must therefore (a) re-assert the flag and (b) confirm the process
  identity before the state is believed:

  ```
  $ADB shell pidof $PKG                       # note the pid
  $ADB shell stat -c '%Z' /proc/$(…pid…)      # process start time — a new value means a new process
  $HOOK $PKG.debug.DUMP_STATE                 # read the flags back rather than assuming them
  ```

  This matters most in **step 9**, where `CLOCK_OFFSET` has to hold across a grace expiry
  together with step 6's failure hook: if the process restarted in between, the app is
  measuring real time with a working backend and the step silently proves nothing. Record the
  pid and its start time next to each hook in steps 6 and 9.

Shorthand used below:

```
# $ANDROID_HOME is NOT set in this shell — take the path from local.properties.
ADB="/c/Users/ahlst/AppData/Local/Android/Sdk/platform-tools/adb.exe -s c1f9837c"
PKG=se.optiqon.voice
HOOK="$ADB shell am broadcast -n $PKG/$PKG.debug.AccessDebugReceiver -a"

# Gradle needs JDK 21 (8.11.1 rejects the bundled JDK 25) and runs through Git Bash
# (the repo has no gradlew.bat). The POSIX form of the PATH entry is required.
export JAVA_HOME="C:\Users\ahlst\.jdks\jbr-21.0.11"
export PATH="/c/Users/ahlst/.jdks/jbr-21.0.11/bin:$PATH"
```

## 0a. Before install — minimal inventory (right target, safe method)

Only what is needed to install on the right device with the right key and a legal
versionCode. **The old installation's data counts are no longer captured as a PASS
criterion** — Lars decided 2026-09-09 that the installed build predates sign-in and
feedback and holds nothing worth preserving. One line noting what it held may be
recorded for the record; nothing in this run depends on it.

```
$ADB shell dumpsys package $PKG | grep -E 'versionCode=|firstInstallTime|lastUpdateTime|signatures='
$ADB shell pm get-app-links $PKG
```

| Item | Before |
|---|---|
| versionCode / versionName | |
| firstInstallTime | |
| signer SHA-256 (must be the debug key `234E2833…29EED`) | |
| App Links state for `optiqon-voice-47498.firebaseapp.com` | |

**versionCode gate (stop condition).** `versionCode = baseCode * 100 + patchCode`, and
`baseCode` falls back to today's date as `YYYYMMDD` when no `-PversionTag` is given
(`app/build.gradle.kts`). `adb install -r` refuses with `INSTALL_FAILED_VERSION_DOWNGRADE`
unless the new code is **strictly greater** than the installed one. Compare before building;
if it is not greater, build with `-PversionTag=v<YYYYMMDD>.<patch>`. A refused install is not
a data risk — the phone stays on the old version — but it is a stop.

| versionCode check | Value |
|---|---|
| Installed | |
| New build | |
| New > installed? | |

## 0b. After step 3 — tester separateness readback

Proves a distinct `localId`. Runs **after** the tester has signed in (step 3), not here:
`node scripts/admin/m1-bootstrap.mjs status --project optiqon-voice-47498` lists
`users/{uid}` with the tester's e-mail under a uid that differs from the admin's. The
tester uid equalling the admin uid is a stop.

## 1. In-place update

```
./gradlew :app:assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell dumpsys package $PKG | grep -E 'versionCode=|firstInstallTime|lastUpdateTime'
```

PASS when: `Success`; firstInstallTime unchanged; lastUpdateTime new; **the app launches and
runs**. Room migrates 7→8 on first start (K21); a crash here is a stop — the app must run,
even though the old data itself is expendable. Whether the old profiles, rules, history and
keys survived is recorded as an observation; their loss is **not** a FAIL and **not** a stop.

If the install fails, verify the actual state (`dumpsys package $PKG` for versionCode and
lastUpdateTime) before choosing anything — an `INSTALL_FAILED_*` code is not on its own proof
that the installation is unchanged. Uninstall, clear-data and older APKs are **not**
authorised; stop and obtain Lars's separate explicit approval for any destructive step.

## 2. Google sign-in and the e-mail link open the app

**Corrected 2026-09-09 (finding F8).** This step used to name `optiqon-voice-47498.web.app`.
The app's intent filter is built from `project_id` as `<projectId>.firebaseapp.com`
(`app/build.gradle.kts`, placeholder `firebaseAuthHost`), and that is also the domain Firebase
Auth puts its action links on. `web.app` is the *Hosting* domain and is not what the filter
matches. The wiring was always correct; the template text was not.


```
$ADB shell pm get-app-links $PKG   # want: optiqon-voice-47498.firebaseapp.com  verified
$ADB shell pm verify-app-links --re-verify $PKG   # once, only if it says none/legacy
```

- Google sign-in with the **admin** account succeeds (Account screen shows the address).
- Sign out. Request an e-mail link for the tester address; open the mail on the phone; the
  link opens the **app**, not the browser. Record the link's host and path prefix as seen in
  the mail (`https://optiqon-voice-47498.firebaseapp.com/__/auth/links?…` expected); no query string.

## 3. New account is pending and cannot dictate

- Tester signs in → app shows pending. Bootstrap readback shows `users/{uid}` with
  `status: pending` for the tester e-mail:
  `node scripts/admin/m1-bootstrap.mjs status --project optiqon-voice-47498`
- Try to dictate in Messages: recording does not start / injection blocked; note the exact
  UI text and one logcat line: `$ADB logcat -d | grep -i "access" | tail -5`.

## 4. Bootstrap: admin becomes writer, tester approved, seat +1

Dry run first, Lars reads the plan, then `--apply`. Both print the plan.

```
node scripts/admin/m1-bootstrap.mjs init --project optiqon-voice-47498
node scripts/admin/m1-bootstrap.mjs init --project optiqon-voice-47498 --apply
node scripts/admin/m1-bootstrap.mjs approve <tester e-mail> --project optiqon-voice-47498
node scripts/admin/m1-bootstrap.mjs approve <tester e-mail> --project optiqon-voice-47498 --apply
node scripts/admin/m1-bootstrap.mjs status --project optiqon-voice-47498
```

PASS when: `config/limits` = {maxApprovedUsers 10, uploadsEnabled false}; `config/counters`
approvedUsers = 2 (admin + tester), seatFor = tester uid; `admins/<adminUid>` role writer;
both users approved with `decidedBy` = admin uid; the phone goes to Active within the
refresh interval (or after a foreground/background cycle). Repeating `approve --apply` prints
"no-op". Stop triggers: any precondition abort, counter ≠ 2, any write mentioning another
project.

## 5. Approved user dictates for real

ASR + LLM + injection into Messages with the tester's own configured keys. Record: PASS/FAIL,
duration, no text.

## 6. Backend failure inside grace → degraded banner, dictation allowed (11h)

**Corrected 2026-09-10 (finding F8).** This step used to say "force a refresh
(background/foreground)". That cannot work, and it cost a full cycle of "it looks the same as
usual" before it was traced. `AccessRefresher.refresh` throttles every trigger except
`UID_CHANGE` and `MANUAL` (`AccessRefresher.kt:62`): a `FOREGROUND` trigger first asks
`isStale()`, which needs the verdict to be at least `CHECK_IN_INTERVAL_MS` = 15 min old
(`:109-115`). Below that it returns `RefreshOutcome.Throttled`, which is deliberately **not
published** (`:87`), so `lastOutcome` stays `Confirmed` and the banner renders nothing. A
backgrounded-and-foregrounded app inside 15 minutes therefore looks exactly like a healthy one.

The only unthrottled path reachable from the UI is **Settings → Konto → "Kontrollera igen"**
(`ui/access/AccountSettingsSection.kt:105` → `AccountViewModel.refresh` →
`AccessSession.refreshNow()` → `RefreshTrigger.MANUAL`, `AccessSession.kt:117`).

```
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled true
$ADB shell run-as $PKG ls files/debug 2>/dev/null   # re-assert: see the hygiene rule on flags
```

Then tap **Settings → Konto → "Kontrollera igen"** on the phone. Do **not** rely on a
background/foreground cycle. PASS when the degraded banner appears and dictation still works.
Then:

```
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled false
```

Waiting out the 15 minutes and then foregrounding is a valid *second* way to see the same
thing, but it is not the step: if the banner does not appear after the manual re-check, that is
a real FAIL and not a throttle.

## 7. Offline → 11i, dictation denied because of ASR, not the account

Airplane mode on. PASS when the app shows the offline state and the dictation attempt fails
with the network/ASR reason while the account stays Active. Airplane mode off.

## 8. Revoke: the device blocks, and the revocation is server-visible

**Corrected 2026-09-09 (finding F1).** The earlier version of this step asserted 403 on the
tester's own `users/{uid}` document. That is provably wrong: `firestore.rules` grants
`allow get: if signedIn() && userId == uid();` deliberately **not** gated on approval, so an
account can learn that it is pending, rejected or revoked — and `tests/rules/m1.test.mjs`
asserts that this read *succeeds* for a revoked account. The old criterion would have produced
a false FAIL on the one step meant to prove the security boundary.

Note the limit this exposes: in Mission 1 **no client-visible Firestore path is gated on
`isApproved()` for a non-admin** (`isApproved()` is reachable only through `isReader()` /
`isWriter()`). A tester's whole cloud surface is that one status-independent document. So
cloud-side refusal on revoke is **not observable here**, and G3 proves the *device* boundary,
not the cloud boundary — recorded as UNPROVEN in `docs/BACKEND_OPEN_CONTRACTS.md`.

```
node scripts/admin/m1-bootstrap.mjs revoke <tester e-mail> --project optiqon-voice-47498 --apply
```

Before the device notices, export the tester's current ID token to the scratchpad **only**:

```
$HOOK $PKG.debug.EXPORT_ID_TOKEN
$ADB shell run-as $PKG cat files/debug/id-token.txt > "$SCRATCH/id-token.txt"
$ADB shell run-as $PKG rm files/debug/id-token.txt
# Probe 1 — own document. Expect 200 with status "revoked".
curl -s -w '\n%{http_code}\n' \
  -H "Authorization: Bearer $(cat "$SCRATCH/id-token.txt")" \
  "https://firestore.googleapis.com/v1/projects/optiqon-voice-47498/databases/(default)/documents/users/<testerUid>" \
  | grep -E '"stringValue"|^[0-9]{3}$'

# Probe 2 — control. Expect 403 (isReader() false), before and after revoke alike.
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $(cat "$SCRATCH/id-token.txt")" \
  "https://firestore.googleapis.com/v1/projects/optiqon-voice-47498/databases/(default)/documents/config/limits"
rm "$SCRATCH/id-token.txt"
```

PASS when all three hold:

1. **Primary:** probe 1 returns **200** with `status: "revoked"` — the token still
   authenticates, and the revocation is server-visible. Record the status code and that one
   field; no body, no token.
2. **Control:** probe 2 returns **403**. Recorded as a **non-discriminating control** — it is
   403 before and after the revoke, and proves only that the tester is not a reader.
3. **The real PASS:** the device shows the revoked screen with the `support@optiqon.se` mail
   action within the revalidation interval.

Also record the counter: approvedUsers back to 1, seatFor = tester uid.

## 9. Grace expiry by clock offset, not the device clock

Re-approve is **not** done here; use the admin account (Active) for this step.

**Process identity first (finding F9).** This step needs two flags to hold simultaneously, and
neither survives a restart. Record the pid and its start time before and after, and read the
flags back rather than assuming them:

```
$ADB shell pidof $PKG                                    # pid before
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled true          # re-assert step 6's hook
$HOOK $PKG.debug.CLOCK_OFFSET --el offsetMs 259200001     # 72 h + 1 ms
$HOOK $PKG.debug.DUMP_STATE                              # both flags must read back set
$ADB shell pidof $PKG                                    # pid after — must be the same pid
```

With the refresh failing (step 6 hook on) the app must fall to the 11i gate; with the offset
reset to 0 and the failure hook off it returns to Active. If the pid changed at any point, the
offset was lost and the observation is void — re-assert and repeat rather than recording the
result.

```
$HOOK $PKG.debug.CLOCK_OFFSET --el offsetMs 0
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled false
$HOOK $PKG.debug.DUMP_STATE                              # confirm both are back at defaults
```

## 10. Account switch and sign-out

- Start a recording, sign out: recording stops, nothing injected.
- Sign in as the tester (revoked) and then as the admin again: **isolation must hold in both
  directions** — neither account sees the other's profiles, history or keys; the explicit
  device-owner choice for the `default` root behaves as PR #3 specifies.
- Isolation is proven by evidence, not appearance: a masked key field that looks filled proves
  only that *a* value is present. Where a stronger statement is needed, use the `DUMP_STATE`
  hook's SHA-256 digests. **No plaintext key is ever exported**, and no unplanned provider
  call is made to manufacture evidence.

## Result table

| Step | PASS/FAIL | Evidence (counts, codes, digests) |
|---|---|---|
| 0a inventory + versionCode gate | | |
| 0b tester separateness | | |
| 1 in-place update | | |
| 2 Google + e-mail link | | |
| 3 pending blocked | | |
| 4 bootstrap | | |
| 5 dictation | | |
| 6 degraded | | |
| 7 offline | | |
| 8 revoke | | |
| 9 grace expiry | | |
| 10 switch/sign-out | | |

## Safe end state — required on a pass *and* on a controlled abort

The debug flags are runtime state in the app process and must be returned to normal whenever
it is safe to do so:

```
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled false
$HOOK $PKG.debug.CLOCK_OFFSET --el offsetMs 0
```

Then **verify** rather than assume: the app is back to Active with no degraded banner, and
`files/debug/` holds no `id-token.txt` (deleted in step 8). G3 is not closed until that
verification is recorded here. If a crash loop makes the hooks unreachable, record the debug
state as left-set and name it as an explicit follow-up — it affects only the debug build.

## Rollback, and what is not authorised

Live Auth and Firestore changes are **not automatically reversible**. Two Auth users, two
`users/{uid}` documents, `config/limits`, `config/counters` and `admins/<adminUid>` persist
after the run; the tool has no delete, no undo and no counter reset by design. The tester's
documents stay (synthetic account); removing them is a separate, separately approved cleanup.
No re-approval of the revoked tester and no destructive cleanup is performed to obtain a
green result.

If step 1 fails the phone keeps the previous version. **Never** re-install an older APK over
the migrated database (the old build's destructive downgrade would erase it). Uninstall,
clear-data, an older APK and any other destructive step require Lars's separate explicit
approval — the decision that the old data is expendable does **not** pre-approve a method.

## What a filled-in table does not mean

**A filled-in smoke table is not a pass.** G3 = PASS only when every mandatory test passes:
real Google sign-in **and** the e-mail link; a genuinely separate tester `localId`; the full
lifecycle pending → blocked → approved (seat +1) → dictating → revoked → device blocks; a real
end-to-end ASR → LLM → injection round trip; the negative tests (degraded-in-grace, offline,
grace expiry by clock offset, sign-out mid-recording); account isolation in both directions;
and the corrected step 8 criteria. Any mandatory test that fails *or cannot be verified* makes
the run **PARTIAL or STOPPED**, and the report then names the exact remaining blocker.
