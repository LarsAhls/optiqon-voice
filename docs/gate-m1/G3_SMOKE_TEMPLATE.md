# G3 smoke template — install, bootstrap and the access loop on the device

Copy to `docs/gate-m1/SMOKE_<date>.md` when the box is opened ("kör G3"). **Not run.**
Everything below is prepared in Mission L1 and runs only on explicit approval, on device
`c1f9837c`, against project **optiqon-voice-47498**, with a **debug-signed** build (same key
as the installed app, so `adb install -r` is an in-place update: no uninstall, no clear data).

Accounts: admin **lars@optiqon.se** (Google, on the phone's Chrome/Play account list). Tester
= a **separate Google account Lars owns** (its own Firebase Auth `localId`; a plus-alias of
the admin address is *not* used unless step 0 proves it is a separate account).

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

Shorthand used below:

```
ADB="$ANDROID_HOME/platform-tools/adb.exe -s c1f9837c"
PKG=se.optiqon.voice
HOOK="$ADB shell am broadcast -n $PKG/$PKG.debug.AccessDebugReceiver -a"
```

## 0. Before install — inventory of the real installation (counts only)

```
$ADB shell dumpsys package $PKG | grep -E 'versionCode=|firstInstallTime|lastUpdateTime|signatures='
$ADB shell pm get-app-links $PKG
```

| Item | Before |
|---|---|
| versionCode / versionName | |
| firstInstallTime | |
| signer SHA-256 (must be the debug key `234E2833…29EED`) | |
| App Links state for `optiqon-voice-47498.web.app` | |

Data counts are **not** read from the real installation with the synthetic dump (it is
built for the seeded root and would open the default root's database in a second
process). Instead record what the app itself shows: number of profiles, number of
replacement rules, number of history rows, and whether the ASR/LLM keys are present
(the provider settings screen shows a configured key masked, an empty field when not).
Take these from the UI before and after.

| Count (from UI) | Before | After step 1 |
|---|---|---|
| Profiles | | |
| Replacement rules | | |
| History rows | | |
| ASR key set? | | |
| LLM key set? | | |

Tester account check (proves a separate `localId`): sign the tester account into the app
in step 3 first; then `node scripts/admin/m1-bootstrap.mjs status --project optiqon-voice-47498`
lists `users/{uid}` with the tester's e-mail under a uid that differs from the admin's.

## 1. In-place update, data kept

```
./gradlew :app:assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell dumpsys package $PKG | grep -E 'versionCode=|firstInstallTime|lastUpdateTime'
```

PASS when: `Success`; firstInstallTime unchanged; lastUpdateTime new; the UI counts in the
table above are equal; the app opens on the home screen, not on onboarding. Room migrates
7→8 on first start (K21); a crash here is a stop.

## 2. Google sign-in and the e-mail link open the app

```
$ADB shell pm get-app-links $PKG            # want: optiqon-voice-47498.web.app  verified
$ADB shell pm verify-app-links --re-verify $PKG   # once, only if it says none/legacy
```

- Google sign-in with the **admin** account succeeds (Account screen shows the address).
- Sign out. Request an e-mail link for the tester address; open the mail on the phone; the
  link opens the **app**, not the browser. Record the link's host and path prefix as seen in
  the mail (`https://optiqon-voice-47498.web.app/__/auth/links?…` expected); no query string.

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

```
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled true
```

Force a refresh (background/foreground). PASS when the degraded banner appears and dictation
still works. Then:

```
$HOOK $PKG.debug.FAIL_REFRESH --ez enabled false
```

## 7. Offline → 11i, dictation denied because of ASR, not the account

Airplane mode on. PASS when the app shows the offline state and the dictation attempt fails
with the network/ASR reason while the account stays Active. Airplane mode off.

## 8. Revoke: device blocks, and a still-valid token is refused by Firestore

```
node scripts/admin/m1-bootstrap.mjs revoke <tester e-mail> --project optiqon-voice-47498 --apply
```

Before the device notices, export the tester's current ID token to the scratchpad **only**:

```
$HOOK $PKG.debug.EXPORT_ID_TOKEN
$ADB shell run-as $PKG cat files/debug/id-token.txt > "$SCRATCH/id-token.txt"
$ADB shell run-as $PKG rm files/debug/id-token.txt
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $(cat "$SCRATCH/id-token.txt")" \
  "https://firestore.googleapis.com/v1/projects/optiqon-voice-47498/databases/(default)/documents/users/<testerUid>"
rm "$SCRATCH/id-token.txt"
```

PASS when the HTTP status is **403** (rules deny a revoked account even with a valid token)
and the device shows the revoked screen with the `support@optiqon.se` mail action within
the revalidation interval. Record only the status code and the counter (approvedUsers back
to 1, seatFor = tester uid).

## 9. Grace expiry by clock offset, not the device clock

Re-approve is **not** done here; use the admin account (Active) for this step.

```
$HOOK $PKG.debug.CLOCK_OFFSET --el offsetMs 259200001     # 72 h + 1 ms
```

With the refresh failing (step 6 hook on) the app must fall to the 11i gate; with the offset
reset to 0 and the failure hook off it returns to Active.

```
$HOOK $PKG.debug.CLOCK_OFFSET --el offsetMs 0
```

## 10. Account switch and sign-out

- Start a recording, sign out: recording stops, nothing injected.
- Sign in as the tester (revoked) and then as the admin again: the tester does not see the
  admin's profiles/history; the admin's data is intact (UI counts as in step 0); the explicit
  device-owner choice for the `default` root behaves as PR #3 specifies.

## Result table

| Step | PASS/FAIL | Evidence (counts, codes, digests) |
|---|---|---|
| 0 inventory | | |
| 1 in-place update | | |
| 2 Google + e-mail link | | |
| 3 pending blocked | | |
| 4 bootstrap | | |
| 5 dictation | | |
| 6 degraded | | |
| 7 offline | | |
| 8 revoke + 403 | | |
| 9 grace expiry | | |
| 10 switch/sign-out | | |

Rollback: none of the steps is undone by deleting anything. The tester's documents stay
(synthetic account); their removal is a later, separately approved cleanup. If step 1 fails
the phone keeps the previous version; **never** re-install an older APK over the migrated
database (the old build's destructive downgrade would erase it).
