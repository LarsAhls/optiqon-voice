# Decision sheet — sitting 1, 2026-09-10

> **ANSWERED 2026-09-10.** All seven lines decided; the answers are recorded immediately below
> and the questions are kept underneath as the record of what was asked and on what evidence.

## Answers

| # | Decision | Answer |
|---|---|---|
| 1 | `MISSION_2_SPEC.md` | **Approved in full, including `NOTES` = `SummarizeMode.LIGHT`.** Room 8→9 and its irreversibility accepted knowingly. |
| 2 | Merge lift for the grace rename | **Lifted.** Merged as [PR #6](https://github.com/LarsAhls/optiqon-voice/pull/6) → `007230f`. |
| 3 | **D2** — existing installation | **Rotation in place, no uninstall.** G3 runs first; the debug-signed APK is kept on hand as the fallback. |
| 4 | **D8** — Android floor | **Raise `minSdk` 26 → 28.** Lars confirms no tester is on Android 8.0/8.1. |
| 5 | **D3** — distribution | **Firebase App Distribution.** Console work is Lars's, in sitting 3. |
| 6 | Identity for feedback | **Reuse the live Firebase Auth.** Feedback is therefore never anonymous — accepted. |
| 7 | G3 | **Box open, sitting 2 booked.** |

**What the answers unblock:** Mission 2 is built in full per the approved spec; the `minSdk` raise
becomes a PR (running code — it needs its own lift before merge); the G3 build is prepared and
every part of the smoke protocol that does not need the phone is filled in; the G4 commands are
made concrete for floor 28; and the App Distribution console steps become a tickable list for
sitting 3. No key is generated and no password entered — G4 stays Lars's work in sitting 3.

---

## The questions as asked

Seven lines. None needs the phone. Everything else in the programme is built autonomously
between this sitting and the device run, so this is the sheet that unblocks the rest.

Read the recommendation, say yes or say otherwise. Lines 3–6 are material and are yours to
decide — the recommendation is evidence plus an opinion, not the choice.

---

## 1. Approve `docs/MISSION_2_SPEC.md`

**Recommendation: yes.**

[`MISSION_2_SPEC.md`](MISSION_2_SPEC.md) is the whole mission on one page: a `profileKind`
enum column, a `ProfileKinds` preset registry in the house idiom, and a pure
`ProfileCapabilities.evaluate`. It also closes a real defect — the profile card says
"Transcribe only, no cleanup" for a profile that does clean text up, because replacement rules
run before the `llmEnabled` check.

**Two things inside it are yours, not mine:**

- **Irreversible:** once a v9 build is installed, no v8 APK can open the database again. By
  design (no destructive fallback in either direction), and no later update undoes it. Rolling
  back to a pre-Mission-2 build costs the tester their profiles, keys and history.
- **`NOTES` presets `SummarizeMode.LIGHT`** — the only preset in the spec that changes visible
  output. The alternative is `NONE`, leaving condensing an explicit choice. Either is cheap.

## 2. Lift merge for PR A2 (#6) — the grace-constant rename

**Recommendation: yes.**

`AccessGate.PROPOSED_GRACE_MS` → `BETA_GRACE_MS`, plus the KDoc in three files that still calls
72 h "proposed" and "not an approved live policy" even though you decided D6 = 72 h on
2026-09-09. Six files, no behaviour change, proven by the compiler and `AccessGateTest`.

It is open as [PR #6](https://github.com/LarsAhls/optiqon-voice/pull/6), CI green, and left unmerged on purpose.
It needs your lift because it is running code: the standing lift covers `docs/`, `BACKLOG.md`,
KDoc and comments only. PR A1 (documents) is merged under that standing lift already.

## 3. D2 — method for your existing installation

**Recommendation: rotation in place. No uninstall.**

This is now a cheaper decision than it looks, on two pieces of evidence already in the repo:

- **Your phone is CPH2645, Android 16 (SDK 36)** (`gate-m1/PREFLIGHT.md:151`) — far above the
  API 28 floor where key rotation exists at all, and above the API 33 line for v3.1.
- **Rotation in place is proven on the real app**, not just in theory:
  `BETA_SIGNING_AND_DISTRIBUTION.md` §3 ran `scripts/signing/rotation-e2e.sh` on **API 36 and
  API 28** — v1 signed with the debug key, then v2 signed with the beta key carrying an A→B
  lineage installed over it; every state field came back identical (including both API-key
  digests, which proves the Keystore master key still unwraps them under the new signer),
  `firstInstallTime` unchanged, platform signer became the new key, and re-installing the old
  debug-key-only APK was refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

So `adb install -r` of a beta-signed, lineage-carrying APK should keep your data and your
install date. The fallback in the plan — a beta key for new installs only, uninstalling once —
is not needed for your device and I would not spend it.

**What this does not prove, per §3's own caveat:** the e2e ran on emulators with a synthetic
data set. It cannot reproduce the Keystore state of *your* phone, whose data was created by
older builds going back to Room v7. It proves the app's pattern survives rotation on your API
level, not that your specific device will. The honest mitigation is order: run G3 first (which
proves the 7→8 upgrade on your actual installation), and do the rotation in sitting 3 with the
debug-signed APK still on hand as the thing you can reinstall if the rotated one refuses.

**Residual:** the lineage must be created with `--set-rollback` left at its default `false`
(§2), or an attacker holding the committed debug key could roll you back on every API level.
§4 has the exact commands.

## 4. D8 — Android floor

**Recommendation: raise `minSdk` from 26 to 28 (Android 9).**

`minSdk = 26` today (`app/build.gradle.kts:56`). On API 26–27 the signer of an installed app
can **never** change in an update — no rotation reaches those levels by any method
(`BETA_SIGNING_AND_DISTRIBUTION.md` §2). Keeping 26 means keeping a tier where the committed
debug key is permanently the effective signer, and the doc's own recommended floor is
"Android 9+, signing with `--rotation-min-sdk-version 28`".

**What I need from you:** confirm no tester is on Android 8.0/8.1. For ≤10 known people that is
a question, not a statistic. If one is, say so — they get the debug-signed build and are
recorded as a known exception rather than silently broken.

## 5. D3 — distribution channel

**Recommendation: Firebase App Distribution.**

≤10 private testers, the Firebase project is already live and Authentication is already
initialised in it, so there is no new vendor and no new identity. Testers get a mail and an
in-app updater.

**Not** an APK from a public GitHub repo: a public release asset is a public download of a
build that talks to your Firestore, and the gate against it is only the approval list.

## 6. Identity for feedback (blocks M4/M5)

**Recommendation: reuse the Firebase Auth that is already live.**

Authentication is initialised in `optiqon-voice-47498` with Google primary and email link
secondary (`gate-m1/GATE_2_READBACK.md`), and the access loop already keys everything on the
Firebase `uid`. A second identity provider for ≤10 testers would add a mapping table, a second
account recovery story and a second thing to revoke, and buy nothing.

## 7. Open the G3 box, and book sitting 2

**Recommendation: yes.**

Everything G3 waited on is done: D6 is decided, G1+G2 ran, and the template's two known traps
are now fixed in it (step 6 could not work as written — finding F8; debug flags silently reset
on a process restart — finding F9).

G3 is one continuous session with the phone in your hand: you install, enable the
accessibility service, sign in as **lars@optiqon.se**, open the tester's mail link on the
phone, dictate in Messages, approve and revoke in the console, toggle airplane mode, and tap
**Settings → Konto → "Kontrollera igen"** in step 6. I drive adb read-only and fill in the
protocol live. Ten steps; budget an hour.

---

## What happens the moment this sheet is answered

Without further contact: PR A2 merges if you lifted it, then Mission 2 is built in full per the
approved spec (Room 8→9, the generated `9.json`, the `DowngradeGuardTest` re-base, five new test
files, the re-recorded Roborazzi baseline), the G3 build is prepared and every part of the smoke
protocol that does not need the phone is filled in, and the G4 commands are made concrete for
your decided floor. I generate no key and enter no password — G4 is yours, in sitting 3.
