# Beta signing and distribution — what is proven, what is decided, what is not

Status 2026-09-09, Mission L1 (plan rev. 4 §R4). Everything below was produced locally on
emulators with throw-away keys. **No real beta key exists yet, nothing was installed on a
real device, and the signing method for the existing installation (decision D2) is still
open.** This file is the evidence for D2/D8 and the runbook for G4; it decides nothing.

## 1. The facts that frame the problem

| # | Fact | Where |
|---|---|---|
| 1 | The repository is public and `debug.keystore` (alias `androiddebugkey`, password `android`) is committed. Its certificate SHA-256 is `234E2833E3E74D721B316C0DB5E87E112B3F74ED5F76D34E5D3208C504429EED`. Every build so far, including the one on Lars's phone, is signed with it. | repo root, `docs/gate-m1/PREFLIGHT.md` |
| 2 | `minSdk = 28` since D8 (raised from 26). APK Signature Scheme v3 (and therefore key rotation) exists from API 28; v3.1 (rotation targeting) from API 33. On API 26–27 the signer of an installed app could never change in an update, which is why that floor was left behind rather than worked around. | `app/build.gradle.kts`, apksigner docs |
| 3 | `apksigner` 0.9 (build-tools 36.0.0) defaults `--rotation-min-sdk-version` to 33: the rotated key is only used through v3.1 on API 33+, the *old* key keeps signing for everything below. With `--rotation-min-sdk-version 28` the rotated key is used from API 28 through a plain v3 block. | `apksigner sign --help` |
| 4 | Without `RELEASE_*` configuration, `./gradlew assembleRelease` signs with the debug key. CI is already fail-closed (release workflows require the four secrets plus `RELEASE_CERT_SHA256` and run `verify-apk-signer.sh`). The open path was local. | `.github/workflows/*.yml`, `app/build.gradle.kts` |

## 2. Proven rotation matrix (B.1, `scripts/signing/rotation-matrix.sh`)

Synthetic package `tools/rotationtest` (one Room row + one EncryptedSharedPreferences value
under an AndroidKeyStore master key), throw-away keys A (original), B (rotated to), C
(attacker holding a competing lineage signed by A). v1 signed A → v2 signed B with lineage
A→B → v3 variants. Emulators API 28, 32 and 36, one full run each, evidence kept outside the
repository (install output, in-app log lines, `dumpsys package` excerpts, lineage dumps).

| API | rotation-min-sdk | positive A→B: signer after / history / data | neg i: v3 signed **only A** | neg ii: v3 with lineage A→**C** | neg iii: lineage grants A *rollback*, v3 signed only A |
|---|---|---|---|---|---|
| 28 | default (33) | **A** / A / kept | **accepted** | **accepted** | accepted (control) |
| 28 | 28 | **B** / A+B / kept | refused `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | refused | accepted (control) |
| 32 | default (33) | **A** / A / kept | **accepted** | **accepted** | accepted (control) |
| 32 | 28 | **B** / A+B / kept | refused | refused | accepted (control) |
| 36 | default (33) | **B** / A+B / kept | refused | refused | accepted (control) |
| 36 | 28 | **B** / A+B / kept | refused | refused | accepted (control) |

Readings:

- The table in plan rev. 4 §Mission B is **confirmed as written**. With the apksigner default
  the debug key stays the effective signer on Android 9–12L, and an APK signed with the debug
  key alone still updates the app there. That is exactly the exposure a public debug key
  creates, so the default is not acceptable for this project.
- `--rotation-min-sdk-version 28` closes it from Android 9 upwards: the rotated key is the
  platform signer, history is old+new, and neither the old key alone nor a competing lineage
  can update the app.
- Negative iii shows *why* neg i is refused: `apksigner rotate` gives the old signer no
  rollback capability by default (`apksigner lineage --print-certs -v` shows `rollback=false`).
  Grant it and the old key can update again on every API level. Never pass
  `--set-rollback true` when producing the real lineage.
- Android 8.0/8.1 (API 26–27) cannot be reached by any rotation. There the debug key remains
  the signer of an already installed app forever; a fresh install with the beta key is the
  only clean state. This was the input to D8 (Android floor for the external beta).
  **D8 decided 2026-09-10: floor Android 9 (API 28), signing with
  `--rotation-min-sdk-version 28`.** No tester is on Android 8.x, so the levels rotation cannot
  reach are now outside the supported range instead of being a permanent signer trap. D2 is
  therefore rotation in place, with no uninstall.

## 3. Proven on the real app (B.2, `scripts/signing/rotation-e2e.sh`)

The OPTIQON Voice **debug build itself**, seeded through the debug-only broadcast hooks with a
fixed synthetic data set (2 profiles, 3 replacement rules incl. one regex, 5 dictations,
17 settings values, two 50-character synthetic API keys in EncryptedSharedPreferences, the
`default` storage root claimed by a synthetic uid with a recorded `APPROVED` access
snapshot). The state dump is a deterministic text (34 fields; the API keys appear only as
SHA-256 digests of the *decrypted* read-back) plus a separate signer report from
`PackageManager.GET_SIGNING_CERTIFICATES`.

| API | rotation-min-sdk | v1 install | restart control | v2 (B + lineage) install | state after | platform signer / history | firstInstallTime | v1 again (debug key only) | state after refusal |
|---|---|---|---|---|---|---|---|---|---|
| 36 | 28 | Success | identical | Success | **identical** | B / debug+B | unchanged | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | identical |
| 28 | 28 | Success | identical | Success | **identical** | B / debug+B | unchanged | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | identical |

"Identical" means every field equal, including `db_user_version=8`, the dictation-text
digest, all settings, both API-key lengths and digests, `default_owner`, `active_uid` and
`access_status`. The API-key digests being equal after the update proves the AndroidKeyStore
master key still unwraps the stored keys under the new signer.

What B.2 does **not** prove, and D2 has to weigh:

- It cannot reproduce the exact Keystore state of Lars's phone (Android 16, data created by
  older builds down to Room v7). It proves the app's *pattern* survives rotation on two API
  levels, not that a specific device does.
- No Firebase user is signed in during the test (no provider calls). The account is
  represented by the root claim and the stored `APPROVED` snapshot, which is what the app
  reads offline.
- No real ASR call is made; decryptability is shown by digest, not by a network round trip.
- After the synthetic uid claims the `default` root, a process with nobody signed in resolves
  to the `signedout` root. The dump therefore opens the default root's files by name; the
  process root is recorded as context, not compared.

## 4. Exact commands (apksigner 0.9)

All paths forward-slash; `APKSIGNER=$ANDROID_HOME/build-tools/36.0.0/apksigner` (`.bat` on
Windows, needs `JAVA_HOME`). Passwords are read from the terminal or from the
`RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` (new key) and `OLD_STORE_PASSWORD` /
`OLD_KEY_PASSWORD` (old key) environment variables; never on the command line.

Create the lineage once (needs both private keys; done by Lars, offline; the file is not committed):

```
$APKSIGNER rotate --out beta.lineage \
  --old-signer --ks debug.keystore --ks-key-alias androiddebugkey \
  --new-signer --ks optiqon-voice-beta.jks --ks-key-alias optiqon-voice-beta
```

Inspect it (expect two certificates, the debug one with `rollback=false`):

```
$APKSIGNER lineage --print-certs -v --in beta.lineage
```

Sign an unsigned release APK with the beta key and the lineage, rotated key effective from API 28:

```
$APKSIGNER sign --lineage beta.lineage --rotation-min-sdk-version 28 \
  --ks debug.keystore --ks-key-alias androiddebugkey \
  --next-signer --ks optiqon-voice-beta.jks --ks-key-alias optiqon-voice-beta \
  --out out.apk app-release-unsigned.apk
```

Verify (expect v2 and v3 true, v3.1 false with `--rotation-min-sdk-version 28`, and the
*beta* certificate as the effective signer):

```
$APKSIGNER verify --print-certs -v out.apk
```

`--next-signer` does not produce two independent signatures. It lets apksigner emit the
signer set the lineage prescribes for each platform level; on every level ≥ 28 the effective
signer is the beta key.

## 5. The guarded local chain (Mission L1, security invariant 2)

1. `./gradlew assembleRelease` without `RELEASE_*` now prints a warning and produces
   `versionName` with the suffix `-devsigned` (verified: `v20260909-devsigned`, signer =
   debug SHA). Recognisable by a human, not enforcement.
2. `./gradlew assembleRelease -PunsignedRelease=true` produces `app-release-unsigned.apk`
   (verified: apksigner reports it unsigned). This is the only input `sign-release.sh` accepts.
3. `scripts/signing/sign-release.sh` signs that file and refuses to leave an output behind
   unless the effective signer equals `--expected-sha256`, no signer is on the deny-list (the
   debug SHA is, permanently), and — when `--lineage` was given — the APK carries that
   lineage with the expected key as its newest certificate. Harness:
   `scripts/signing/test-sign-release.sh` (13 cases with throw-away keys, all pass).
4. `.github/scripts/verify-apk-signer.sh` carries the same deny-list, so CI rejects the debug
   SHA even if `RELEASE_CERT_SHA256` were set to it by mistake.
5. Still to come (Mission D, before G5): `scripts/dist/distribute.sh` as the only upload path
   to App Distribution, adding versionCode/Room-version monotonicity to the same checks.

## 6. What Lars does, offline, when D2 is taken (G4 inputs)

Nothing in this section passes through an agent. Passwords go into a password manager, not
into chat, repository, CI or logs.

1. Generate the key on the workstation, not in a cloud shell:

   ```
   keytool -genkeypair -v -keystore optiqon-voice-beta.jks -alias optiqon-voice-beta \
     -keyalg RSA -keysize 4096 -validity 10950 \
     -dname "CN=OPTIQON Voice beta, O=Optiqon, C=SE"
   ```

2. Back it up **and prove the backup**: copy the `.jks` to the offline medium, then from the
   *copy* run `keytool -list -v -keystore <copy> -alias optiqon-voice-beta` and compare the
   SHA-256 with the original; then sign any throw-away APK with the copy and check that
   `apksigner verify --print-certs` shows the same SHA-256. A backup that has not signed
   something is not a backup.
3. Create `beta.lineage` with the `rotate` command in §4 (needs `debug.keystore` from the
   repository and the new key). Keep it next to the key; it is required for every future
   signing.
4. Register the beta certificate SHA-1 and SHA-256 **additively** in the Firebase project
   (debug SHAs stay), add the SHA-256 to `public/.well-known/assetlinks.json` and redeploy
   Hosting (G2 postflight again) **before** the first beta-signed build is installed.
5. Then G4 proper, according to the method D2 selects:
   (a) rotation in place on the phone (`adb install -r` of a beta-signed, lineage-carrying
   build), or (b) beta key only for new installations while the phone stays on the debug key
   until a proven migration exists. Rollback after a successful rotation does not exist,
   which is why §2 and §3 came first.

## 7. Play App Signing — out of scope

Uploading lineage-rotated APKs to Play and Play's own key rotation (Android 13+) follow
different rules from sideloaded/App Distribution builds. Nothing here is designed to make the
beta key a Play upload key; that is a separate decision when Play becomes relevant.

## 8. Distribution (unchanged from plan rev. 4, decision D3 pending)

Firebase App Distribution is recommended for ≤ 10 private testers (private APK, invitation
by mail, in-app update SDK, Spark tier). GitHub Releases would publish every beta APK from a
public repository and is not used for artefacts while the repository is public. The in-app
"check for update" must show three distinct states (up to date / new version / could not
check). App Distribution's own feedback channel stays off; feedback goes through the
decided private Help & news loop (Mission E).
