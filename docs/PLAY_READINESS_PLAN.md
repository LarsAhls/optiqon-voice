# OPTIQON Voice — Play Readiness Plan

**Status:** Fas 1 AGREED · Fas 2 M1, M2, M3 + M3-LP, M4, M5, M6 = **AGREED / LOCKED** (2026-09-30).
M7 and M8 are **baseline only**, not yet planned in Fas 2.
**Implementation: NOT APPROVED.** Nothing in this document authorises code, Console, Firebase,
provider, signing, deploy or device work. Each step still needs its own go.

This is the single active consensus plan (Claude ↔ ChatGPT, decided by Lars). It is revised in
place when a planning block is locked; there is no v2/v3. It contains the consensus only, not the
discussion history.

Related evidence (these documents decide nothing on their own):
- [BETA_SIGNING_AND_DISTRIBUTION.md](BETA_SIGNING_AND_DISTRIBUTION.md): signing/distribution evidence (D2/D8).
- [DECISION_SHEET_2026-09-10.md](DECISION_SHEET_2026-09-10.md): dated decision log.
- [BACKEND_OPEN_CONTRACTS.md](BACKEND_OPEN_CONTRACTS.md): backend not-proven source of truth.

Classification used throughout:
- **AUTONOM**: local, reversible work inside an approved scope box.
- **ENHET**: needs an emulator or device run.
- **LARS-BESLUT**: a material decision that only Lars makes.
- **GATE**: an external, live or irreversible effect. Each gate needs an explicit Lars go.

---

## 1. Goal and scope

**Goal.** Ship OPTIQON Voice through Google Play (Internal → Closed → Production) without
weakening Architecture B, and with minimal onboarding friction.

**Material triggers (always STOP → Lars):**
- dictation UX;
- BYOK / provider cost;
- account / access model;
- Android floor;
- removal of the bubble or of Gboard compatibility;
- persistence / data behaviour;
- signing or distribution;
- security / privacy boundary;
- Firebase / backend architecture.

### TARGET-1 — first Play version keeps Architecture B

These stay:
- bubble, Gboard compatibility, SAW;
- AccessibilityService with `canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`,
  `flagInputMethodEditor`, and the node `ACTION_SET_TEXT` fallback;
- FGS `specialUse`;
- BYOK;
- the account / access model;
- minSdk 28;
- boot autostart default;
- FCM.

Changes:
- `POST_NOTIFICATIONS` becomes optional / just-in-time.
- `RECORD_AUDIO` is requested contextually from an Activity.
- `typeViewTextChanged` may only be removed after a positive parity matrix (M3-LP1).
- Architecture C (SAW removal) is **LATER**, not on the critical path.

### TARGET-2 — onboarding in at most 3 logical steps

1. **ACCOUNT.** Pending status updates in realtime.
2. **CONNECT.** The recommended provider is preselected, Advanced is hidden, and verify is kept.
3. **ENABLE VOICE.** disclosure → consent → mic (from an Activity) → a11y → overlay →
   status/recovery → bubble autostarts once.

Removed from onboarding:
- LANGUAGE (`sv` default plus a chip);
- the notifications step;
- the boot question;
- the separate Home start.

Restricted Settings guidance is shown only where it is relevant.

### TARGET-3 — distribution path

private → Play Internal (in parallel) → technical + BankID proof → Closed → Production.
- Requires P-D1.
- No existing channel is retired before verification.

### Fas 1 individual decisions

- **X1:** remove LANGUAGE from onboarding, keep `sv`.
- **X2:** the privacy policy is built autonomously before M4 and deployed at the M4 gate.
- **X3:** `release.yml` stays untouched until M4 (M4.1).
- **X4:** when ENABLE VOICE completes, the bubble starts once.
  - Home start/stop stays.
  - A normal app open does not restart a bubble the user stopped.
  - Boot follows the setting.
- **N1:** pending status uses a snapshot listener on the same `users/{uid}`.
  - No client writes.
  - No provider calls before the account is approved.
- **N2:** installer-source arms A/B/C. `adb install -i com.android.vending` is **never**
  Play-equivalent.
- **N3:** M1 exit requires 0 `NewApi`.
- **N4:** a revoked mic gives an actionable signal, with no trampoline from the overlay.
- **Carried forward:**
  - F2-U1: reviewer Google account without 2SV challenges (→ M6).
  - F2-U2: installer-source logging. Resolved in M5: **not needed**, and there is no
    install-source logging in the app.

---

## 2. Milestones (M0–M8)

| M | Content | Class | Exit (summary) |
|---|---|---|---|
| M0 | Consensus baseline | — | Fas 1 AGREED |
| M1 | Platform: SDK 36, API-28 `startForeground` fix, 0 NewApi | AUTONOM + ENHET | §4 M1 claim |
| M2 | AAB CI, manifest contract, `dataExtractionRules`, signer denylist | AUTONOM | §4 M2 |
| M3 | Onboarding v2 + privacy prep | AUTONOM + ENHET (M3.6 GATE) | §4 M3 |
| M3-LP | Least-privilege proofs (non-blocking; no removal without parity; SAW excluded) | AUTONOM + ENHET | parity evidence only |
| M4 | Play / signing gate | M4-A AUTONOM · M4-B LARS-BESLUT · M4-C GATE | §4 M4 |
| M5 | Play Internal + BankID discriminator | M5-A AUTONOM · M5-B GATE + ENHET | §4 M5 |
| M6 | Compliance / Closed readiness | local AUTONOM · live GATE | exit criteria 1–12 |
| M7 | Closed testing | GATE | baseline only (§8) |
| M8 | Production | GATE | baseline only (§8) |

---

## 3. Execution order

After implementation-go:

**M4.1 → M1 → M2 → M3 (+ M3-LP) → rest of M4 → M5 → M6 → M7 → M8**

- M4.1 comes first and is limited to:
  - neutralising the `release.yml` trigger and its publishing;
  - tests;
  - doc corrections.

  It involves no secrets, no ceremony and no Play effect.
- Inside M4-C:

  C1 → C2 → M4.3 → C3 → C4 → C5 → C6 → C7 (incl. readback) → C8(b) green → C9 → C10

- M5-B needs the relevant M4 evidence (C7 readback, C8(b)) plus L1 and L2.
- M6 must be complete before M7. That includes the Remote Feedback subset required for a live AI
  report.
- If BankID blocks on a real Play install (M5): **STOP before Closed** and go back to a
  product / architecture decision.

---

## 4. Per-milestone definition of ready (locked content)

### M1 — Platform (LOCKED)

- **M1.0 — test leak.** `@After` teardown in `OutboxWorkerScheduleTest` (floor). AUTONOM.
- **M1.1 — API-28 FGS.** AUTONOM + ENHET.
  - A `ServiceCompat.startForeground` helper.
  - `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.
  - Targeted `@SuppressLint("InlinedApi")`.
  - **NewApi gate:** `LintBaselineContractTest` requires 0 `NewApi`. A fail-closed CI step also
    parses `lint-results-debug.xml` with Python `xml.etree`:
    - a missing report fails;
    - any `NewApi` present fails;
    - no raw grep.
  - A root start on an AVD is only a narrow red/green proof.
- **M1.2 — SDK 36 / AGP ≥ 8.9.1.** AUTONOM + ENHET.
  - Scope: compileSdk and targetSdk 36, `tools:targetApi="36"`, baseline triage,
    `platforms;android-36` in `test.yml`, and an Android 16 audit.
  - `enableOnBackInvokedCallback` is **not** declared (veto stands).
  - Report AGP, compile, target, warnings, KSP and Hilt.
  - On toolchain failure: a separate narrow AUTONOM toolchain mission. No spontaneous Kotlin
    migration. Go to Lars only if the failure is material.
- **M1.3 — runtime parity matrix.** AUTONOM + ENHET.
  - Emulator on API 28, 32 and 36.
  - A smoke test on Lars's phone. A real dictation is started only manually, by Lars.
- **Formal claim:** "The identified API-28 crash is reproduced and eliminated on a real API-28
  runtime. FGS promotion/demotion compatibility is verified through ServiceCompat and multi-SDK
  JVM/Robolectric. A full authenticated lifecycle on physical API 28/32 is not proven."

### M2 — Build / manifest / signer contract (LOCKED; B2 = a: block all D2D)

- **M2.1:** only `allowBackup=false` + `android:dataExtractionRules` + `data_extraction_rules.xml`.
  - No `backup_rules.xml` and no `fullBackupContent`.
  - `bmgr` is only a sanity check.
  - OEM device-to-device transfer is Not proven.
- **M2.2:** a canonical allowlist per entry of the merged manifest, covering value, provenance and
  category. The categories are CORE_REQUIRED, OPTIONAL_FEATURE, TRANSITIVE_ACCEPTED and
  SYSTEM/SDK_GENERATED. Counts are secondary.
- **M2.3a:** a canonical denylist `scripts/signing/denied-cert-sha256.txt`, with the loader
  `scripts/signing/lib-denylist.sh`.
  - Consumers: the APK verifier, `sign-release` and the AAB verifier. There is no third copy of the
    policy.
  - Fail-closed on a missing file, an empty list or a bad format.
  - CRLF hardening, `.gitattributes`, and a mutation test.
  - `jarsigner` runs with `LC_ALL=C -J-Duser.language=en -J-Duser.country=US`, and its exit code
    alone is not trusted.
  - Fixtures: unsigned, debug (denied) and throwaway.
  - Runs on JDK 17 and JBR 21.
- **M2.3b:** an unsigned AAB in CI.
  - bundletool is pinned, with SHA-256, in the version catalog.
  - A universal APK is built locally.
  - 16 KB checks.
  - No public binary artifacts and no Play upload.
- **M2.4:** workflow dispatch input goes via env, with an allowlist regex.
- The AAB upload happens only in M4. `READ_GSERVICES` is kept.

### M3 + M3-LP — Onboarding v2 + privacy prep (LOCKED)

- **M3.0 — prefs keys, migration and `VoiceStartPolicy`.**
  - `VoiceStartPolicy` is pure Kotlin with four predicates:
    - `voiceReady`;
    - `autostartAfterSetup` (never reads `startOnBoot`);
    - `startOnSystemEvent` (the only reader of `startOnBoot`);
    - `manualStart`, which returns a reason enum.
  - Migration:
    - a missing `bubble_autostart_consumed` counts as consumed when onboarding is complete;
    - a missing `a11y_disclosure_version` counts as "no consent", which is fail-closed.
  - A `BubbleService.onCreate` gate covers Blocked, consent and pending claim.
  - `A11yConsentSoleWriterTest`.
  - Pending-claim block (M3-D3).
- **M3.1 — pending → approved in realtime** (N1).
- **M3.2 — 3-step state machine and the single ASR write path.**
  - `PreferencesDataStore.updateAsrConfig(baseUrl, apiKey, model, verified)` takes a per-root
    Mutex. It clears the flag, then commits the key, then does one atomic edit.
  - `asr_verified_model`. A model mismatch **warns**, it does not block, and never triggers an
    automatic verify call (H3).
  - `AsrConfigSoleWriterTest`.
  - `restoreVerifiedEndpoint` requires the flag.
  - **I2 preflight:** see §5.
- **M3.3a — a11y disclosure and consent** (including the bypass card). The UI shows "Tillgänglighet
  är aktiverad – granska hur OPTIQON använder den", never "saknas". Consent is shown as given only
  after a successful write on the current, unsealed root.
- **M3.3b — ENABLE VOICE surface, and autostart once.**
  - A HomeViewModel latch plus a Mutex. `consumed` is written only after an accepted
    `startForegroundService`.
  - Home maps the `manualStart` reason to existing actions, so there is never a silent no-op.
- **M3.4 — recovery** (N4).
- **M3.5 — privacy policy (no deploy)** and a hosting guard.
  - The hook runs `node scripts/deploy/hosting-guard.mjs`, and blocks on the `[[LARS` token.
  - Exit codes: 2 on a hit, 0 when clean, 3 on an internal error, never 1.
  - The hook itself is AUTONOM. `firebase deploy` stays a GATE.
- **M3.6 — acceptance and the M3 gate** (GATE + ENHET). Device checks:
  - a sticky restart without consent gives no bubble;
  - a pending claim gives no dictation and no boot start;
  - `startOnBoot=false` plus a new onboarding still gives the initial bubble.
- **M3-LP1** `typeViewTextChanged`, **LP2** FGS with notifications denied, **LP3** permissions
  merged in from libraries. All three are non-blocking and are removed only after parity.
- **H1–H4:**
  - **H1:** `manualStart` returns a reason enum (invariant I1).
  - **H2:** `onboardingComplete` is part of the boot and setup predicates. `startOnBoot` only
    affects start on a system event.
  - **H3:** a model mismatch warns and does not block.
  - **H4:** ASR readiness is not part of `voiceReady`, which covers access, claim, consent, mic,
    overlay and a11y.

### M4 — Play / signing gate (LOCKED)

- **M4-A (AUTONOM):**
  - **M4.0 — stop rules and custody gate.** No key gets any Play role before its password is in a
    password manager **and** an offline backup is proven.
  - **M4.1 — neutralise the pipeline and make the docs true.** `release.yml` is currently armed on
    `v*` tags and publishes a public GitHub Release. This step runs **before M1**.
  - **M4.2 — Play upload artifact hardening.** It is a delta on top of M2.3: "M4.2 consumes the
    merged M2.3a/M2.3b contract and only adds Play-upload-key-specific behavior." It adds:
    - `--expected-sha256 <K_U>`;
    - local K_U signing;
    - F11 `bundleRelease` fail-closed, unless M2 already covers it;
    - lineage-aware APK verification with min-sdk 28;
    - a versionCode floor;
    - an upload runbook.
  - **M4.3 — continuity proof on an emulator, with throwaway keys.**
  - **M4.4 — Play / Android 16 readiness verification after M1.2.** It is verification only. It is
    BLOCKED if M1.2 has not delivered 36. If Android 16 behaviour needs a dictation UX change:
    STOP → Lars.
  - **M4.5 — declaration drafts, video scripts and App access.** Videos pass a parity check against
    the release candidate.
  - **M4.6 — postflight and readback (read-only).** The blocking checks are:
    - the effective classical signer for API 36;
    - lineage;
    - SHA vs SHA_MATRIX;
    - the Console readback;
    - `firstInstallTime`;
    - data markers;
    - install source.

    The v3.2 block inspection is diagnostic only (Not contractual).
  - **M4.7 — dynamic SHA_MATRIX, assetlinks and privacy hosting design.**
- **M4-B (LARS-BESLUT):** P-D1, P-D2a/b/c and P-D3. See §7.
- **M4-C (GATE):**
  - **C1:** read the signer and lineage on `c1f9837c`.
  - **C2:** create the Play developer account (irreversible).
  - **C3:** key ceremony for K_beta, a new K_U and lineage. It is reversible until C7.
  - **C4:** M4.3 with the real keys, on an emulator.
  - **C5:** Firebase SHA and hosting deploy.
  - **C6:** declaration videos.
  - **C7:** create the Play app and register signing, including the readback (irreversible).
  - **C8(b):** first AAB → Internal, tested on a disposable device.
  - **C9:** rotate `c1f9837c` in place (sideload, irreversible).
  - **C10:** Lars updates `c1f9837c` from Internal (irreversible).
- **Chosen path B:**
  - sideload rotation debug → K_beta, with lineage and `--rotation-min-sdk-version 28` (floor API 28);
  - K_beta is the Play app-signing key via PEPK;
  - a separate new upload key K_U.
- **Five separate roles:**
  - installed signer;
  - beta signer (K_beta);
  - Play app-signing key;
  - upload key (K_U);
  - lineage.

  "Play accepting an AAB ≠ proven update/data continuity."
- **SHA_MATRIX roles:**

  | Role | Registered in Firebase / assetlinks? |
  |---|---|
  | `debug` | Yes, until it is removed. The debug SHA is removed **before Closed**. |
  | `K_beta_local` | Yes |
  | `PLAY_CLASSICAL_PRE17` | Yes |
  | `PLAY_CLASSICAL_HYBRID_17PLUS` (optional) | Yes, if reported |
  | `PLAY_PQC_17PLUS` (optional) | Yes, if reported |
  | `K_U_UPLOAD_ONLY` | **No** (M4-D3) |

  - Roles, not unique certificates.
  - Play rows are filled **only** from the C7 Console readback.
- **C7 rule:** "C7 readback determines all actual Play signing identities. For the current API36
  continuity path, the classical signer used for Android ≤16 must match the intended K_beta
  continuity identity. Additional Play-reported Android17+/hybrid/PQC identities are recorded and
  propagated to SHA/API/App Links configuration as required; their mere presence is not failure."
- **Closed decisions:**
  - M4-D1 = a (target API 36).
  - M4-D2 = a: data probe via fixed markers plus `firstInstallTime` plus signer readback. CLOSED as HOW.
  - M4-D3 = no. K_U is upload-only and not a Firebase identity. CLOSED as HOW.
- **Findings behind M4:**
  - K0: the `RELEASE_*` secrets exist (SHA `e6baa523…`), custody is undocumented, and there is no
    proven offline backup.
  - The debug keystore is public.
  - `c1f9837c` currently has the debug signer.
- **Requirement placement:** every M4 requirement is classified as before Internal, before Closed,
  before Production, or non-blocking.

### M5 — Play Internal + BankID discriminator (LOCKED)

- **Question:** does Architecture B (bubble + SAW + Accessibility + Gboard) work when it is
  genuinely installed from Play Internal, including together with BankID? We do not assume what
  BankID detects.
- **Variables:**
  - V1: install pathway.
  - V2: signer.
  - V3: Accessibility, either OFF or ON with CURRENT consent given through the real flow. Never
    `adb settings put` in binding cases.
  - V5a SAW: granted.
  - V5b ARMED: BubbleService is running and no window is visible.
  - V5c VISIBLE: an overlay is actually observed at that T-point. It is **never forced**, and is
    marked NOT REACHED if absent.
- **Arms:**
  - **B0:** a control on the same device, without OPTIQON.
  - **Fresh Play A0–A3, A3r, A2r:**
    - A0: SAW not granted, A11y OFF.
    - A1: SAW granted.
    - A2: A11y ON, bubble stopped.
    - A3: ARMED, with VISIBLE observed per T-point.
    - A3r and A2r: reversibility.
  - **C0–C3:** a different install pathway, with identical bytes, signing and version where
    possible. The splits are reinstalled with `adb install-multiple` without `-i`.
  - **M-arm:** C8(b) migration history on device D. It runs only if L1 holds.
  - **A′:** `c1f9837c` after C10. It is deployment acceptance, not causal proof, and the phone is
    never uninstalled.
  - **E** (App Distribution): OPTIONAL.
  - **X** (`adb install -i com.android.vending`): DIAGNOSTIC ONLY, never evidence.
- **Order on disposable device D:**

  B0 → C8(b) + M-arm → uninstall → fresh Play A0–A2r → uninstall → C0–C3.

  Uninstalling is allowed only on D.
- **I1, empirical Play baseline:**
  - the install was really initiated from the Internal track;
  - the signer matches the C7 / SHA_MATRIX identity;
  - readback is taken before and after;
  - the observed readback on D becomes the baseline.

  `initiatingPackageName=com.android.vending` and `packageSource=STORE` are expected, not
  definitional. Readback comes from `dumpsys`, which is a runtime observation, not a contract.
- **BankID observation:**
  - T-points: T1 app start, T2 start of identification, T3 PIN / signing, T4 completion.
  - Outcomes: OK / WARN / BLOCK / N/A.
  - One low-risk identification flow, with no real transaction.
  - Never log personnummer, PIN or transaction data.
  - No hooking, modification, spoofing or bypass of BankID.
- **Rules:**
  - **WARN:** transcribe the warning text. Do not continue a signing the warning advises against.
    Run the safe arms. STOP before the primary phone, Closed and M6. It becomes a Lars decision.
  - **BLOCK in A2 or A3:** stop that arm; Architecture B is not approved. STOP, and go back to a
    product / architecture decision before M6.
  - **B0 ≠ OK:** no conclusion can be drawn.
- **Invariant:** "`V5c = NOT REACHED` is not evidence that a visible OPTIQON overlay is compatible
  with BankID. It proves only the observed ARMED/hidden-overlay state for that BankID flow."
- **Exit wording:** "Architecture B passed the tested BankID discriminator for all states actually
  reached. Any V5c state marked NOT REACHED remains explicitly unproven."
- **F7:** the bubble is keyboard-driven (`addView` / `removeView` on IME show / hide).
- **Prerequisites before M5-B (they do not block the DoR):**
  - **L1:** a secondary physical Android 13+ device with its own Mobilt BankID.
  - **L2:** any earlier WARN / BLOCK observed on `c1f9837c`, otherwise UNKNOWN.

### M6 — Compliance / Closed readiness (LOCKED)

**Exit criteria 1–12:**
1. A source→sink / Data Safety matrix.
2. An SDK audit from a clean, pinned checkout, including transitive SDKs and Credential Manager /
   Google Identity.
3. The privacy policy is live.
4. Account deletion:
   - in-app in **both** `AccountScreen` (all non-approved states that have an Auth session) and
     `AccountSettingsSection` (Approved);
   - an external web resource;
   - a live E2E run.
5. The Accessibility declaration and video.
6. The FGS declaration and video.
7. A live in-app AI report, E2E.
8. Reviewer authentication proof.
9. A reviewer provider credential.
10. App access instructions, tested.
11. Data Safety, audience, ads, rating and listing.
12. No promise exceeds the implementation, including retention promises.

**Further locked content:**
- **Q-A copy invariant:** "When AI cleanup is enabled, OPTIQON may send the name and package
  identifier of the app you are currently dictating into to your selected AI provider, so the
  cleanup can adapt formatting and style to that context." Data Safety declares it as App activity
  → Installed apps: Collected YES, Shared NO, Optional.
- **BYOK in Data Safety:**
  - Collected YES / Shared NO.
  - Audio is Required.
  - LLM text is Optional (`llmEnabled=false` by default).
- **AI reporting (AI-b+):** recommended 9/10, with hard acceptance 1–6. It remains a Lars decision
  (P-M6-AI).
- **Remote Feedback:** the "minimum approved Remote Feedback subset necessary for live AI report →
  M7". Enabling it is a separate gated release decision.
- **D4-a deletion carrier (recommended):**
  - an in-app `deletionRequests/{uid}`;
  - an admin runs `delete-account`;
  - a web page plus an email route;
  - an SLA of about 30 days;
  - a request SLA ≠ purge latency.
- **D4-I1:** the owning uid can create its request regardless of status (including Revoked and
  Rejected). This is covered by a rules test.
- **D4-I2:** the request record is deleted or anonymised after completion. If the Auth session is
  no longer usable, it fails safe to the external resource.
- **Deletion invariant:** no irreversible local wipe happens on a request alone. A wipe happens only
  after server deletion is confirmed, or on an explicit "rensa denna enhet nu".
- **R1 (reviewer account):**
  - R1-a: a dedicated Google account that passes 3 clean sign-ins over ≥ 48 h on clean devices.
  - R1-b is the fallback on any challenge.
- **R2 (reviewer credential):** a dedicated, limited key, entered only in the Console App access
  field. It is never put in the repo or in chat.

---

## 5. Verified invariants (must hold in implementation)

- **I1 — `manualStart` priority:**

  BLOCKED > NEED_CLAIM > NEED_MIC > NEED_OVERLAY > NEED_CONSENT > NEED_A11Y > OK

  `VoiceStartPolicyTest` is parameterised over all 2^6 gate combinations.
- **I2 — local preflight on bubble tap, before any recording:**
  - Applies if the config is missing or `asr_config_verified == false`.
  - In that case: no recording and no ASR or LLM call. The user is routed to CONNECT (fail-closed).
  - `TranscriptionManager` retry applies the same check, and the audio is kept.
  - UNVERIFIED CONFIG → block; UNVERIFIED MODEL VARIANT → warn.
- **M3-D3 — pending-claim block.** It applies in all of these:
  - `AccessGuard.authorize`;
  - `AccessLease.isValid/requireValid` (the lease revalidates before external effects);
  - `VoiceStartPolicy`;
  - `BootReceiver`;
  - `BubbleService.onCreate`.
- **Autostart:** `startOnBoot` is read only by `startOnSystemEvent`, and autostart after setup
  happens once per root.
- **Consent:** consent is per root and follows the root on claim (M3-D2). A missing disclosure
  version counts as no consent.
- **Signing:**
  - No Internal release is uploaded, rolled out or installed before the C7 app-signing readback is
    verified against the chosen P-D2b path and SHA_MATRIX is updated.
  - C8(b) comes before C9 (hard).
- **Installer source:**
  - Manipulated installer source is diagnostic only.
  - The binding test is a real Play Internal install.
- **BankID:** the NOT REACHED invariant (M5).
- **Deletion:** the deletion invariant and D4-I1 / D4-I2 (M6).
- **Q-A:** the Q-A copy invariant (M6).

---

## 6. Hard stops and rollback

- Any material trigger (§1) → STOP → Lars.
- A toolchain failure in M1.2 → a separate narrow mission, not a spontaneous migration.
- C7 → STOP when any of these holds:
  - the classical signer for ≤16 ≠ K_beta;
  - the readback contradicts the continuity design;
  - C8(b) no longer represents the intended path.
- M5:
  - WARN → STOP before the primary phone, Closed and M6.
  - BLOCK in A2 / A3 → Architecture B is not approved, and the next step is a product /
    architecture decision.
  - B0 ≠ OK → no conclusion.
- No key gets a Play role before custody is proven (M4.0).
- Irreversible steps: C2, C7, C9 and C10. C3 is reversible until C7.
- **Rollback:**
  - Local and AUTONOM work: branch revert.
  - Firebase SHA / hosting (C5): restore the previous SHA set or hosting release.
  - Device D: uninstall is allowed.
  - `c1f9837c`: never uninstalled.
  - Play-side steps after C7 have no rollback. This is why the readback and C8(b) come first.

---

## 7. Open Lars decisions (latest decision point)

| ID | Question | Recommendation | Blocks first |
|---|---|---|---|
| implementation-go | Start implementation (M4.1 first) | — | all implementation |
| P-D1-DISTRIBUTION | Internal replaces App Distribution after C10 | 7/10 | C10 |
| P-D2a | Package identity: keep `se.optiqon.voice` | 8/10 | C7 |
| P-D2b | App-signing: path B (K_beta via PEPK) | 9/10 | C3 |
| P-D2c | Upload key: new K_U (not K0) | 9/10 | C3 |
| P-D3-DEV-ACCOUNT | Publisher: organisation (D-U-N-S) or personal | factual question | C2 |
| L1 / L2 | Secondary BankID device; earlier WARN / BLOCK history | — | M5-B |
| P-D4-DELETION-CARRIER | D4-a in-app request + admin delete | 9/10 | M6 deletion build |
| P-M6-AI | AI-b+ in-app AI reporting | 9/10 | M6 exit 7 |
| P-M6-R1 | Reviewer account R1-a (fallback R1-b) | — | M6 exit 8 |
| P-M6-R2 | Dedicated limited reviewer key | 9/10 | M6 exit 9 |

**Decided:**
- M3-D1-ASR-MIGRATION = a: an existing config counts as unverified, and re-verification is manual
  and paid.
- M3-D2-A11Y-CONSENT-SCOPE = a: consent is per root.
- M3-D3-PENDING-DATA-CLAIM = a: blocked.

All three were decided 2026-09-30. M4-D1 = a, M4-D2 = a and M4-D3 = no are closed. The hosting
hook is CLOSED as implementation HOW / AUTONOM.

---

## 8. M7 / M8 — baseline only

- **M7 Closed testing (GATE):**
  - Closed works technically.
  - Relevant real use is done.
  - There are no blocking policy or regression findings.
  - 12 testers × 14 days applies only if the personal-account rule applies (P-D3).
  - The debug SHA is removed before Closed.
- **M8 Production (GATE):** needs explicit Lars approval.

Detailed M7 / M8 DoR is **not yet planned** in Fas 2.

---

## 9. Not proven

- A full authenticated lifecycle on physical API 28 / 32 (M1).
- OEM device-to-device transfer behaviour (M2.1).
- Whether a sticky `specialUse` FGS restart is allowed in the background on Android 12+ (checked
  in M3.6).
- That Android 17 accepts a classical-only app; hybrid rotation continuity from K_beta for an
  existing install on Android 17 (M4.7 follow-up before Android 17 on `c1f9837c`).
- Exact Play Console signing labels and the hybrid / PQC rows before C7.
- BankID visible-overlay compatibility wherever V5c = NOT REACHED (M5).
- What BankID actually detects. That is not assumed, only observed.
- Whether the reviewer Google account avoids 2SV / new-device challenges for reviewers in other
  countries (R1).
- Whether Play requires videos for Accessibility / FGS `specialUse` in this case.
- Whether Play's AI policy applies to BYOK LLM cleanup.
- Whether a report without content satisfies Play's AI-reporting expectation.
- Transitive SDK inventory (M6-A2 step 1).
- The live value of `config/limits.maxApprovedUsers` and the seats taken (relevant to M7).
- Whether 12×14 applies to the chosen account type.
