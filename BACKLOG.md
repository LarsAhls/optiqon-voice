# Backlog

## Vibe-code technical debt audit — executed, one finding left open

**Status:** Closed as an audit. One finding deferred by design.

The audit this file used to describe was run on 2026-09-09 and is recorded in
[`docs/gate-m1/SKULDREVISION_2026-09-09.md`](docs/gate-m1/SKULDREVISION_2026-09-09.md):
ten ranked findings with evidence, severity and cheapest sufficient verification. Findings
9, 5, 4, 3, 8 and 6 (with F15) were fixed and merged in PR #4 (`6a67184`); finding 8 turned
out to be a **wrong finding** and was corrected in the opposite direction. Do not re-run the
audit — read the report.

### Open — fix when touched

**Finding 7: `BubbleService` carries eight responsibilities (1132 lines as of 2026-09-10; the
audit measured ~1018 before PR #4).**
Trigger: the next mission that changes the file (M3, the bubble/overlay work). The audit's own
recommendation is to lift out one responsibility when the file is edited anyway, not to
refactor it on speculation. Splitting it now would be a large diff across the most
lifecycle-sensitive file in the app with no behaviour change to show for it.

### Open — profile card reports capabilities it does not have

The profile card's summary line is wrong in two directions, and the same decision is written
out in four places:

- `ProfilesScreen.profileSummary` prints "Transcribe only, no cleanup" whenever `llmEnabled`
  is false, but `TextProcessor.process` applies the profile's replacement rules **before** it
  checks `llmEnabled`. A profile with rules and no LLM does clean text up; the card says it
  does not.
- The same function prints "Cleanup on" from `llmEnabled` alone, while the runtime path also
  needs a non-blank `llmBaseUrl` and `llmApiKey`. A profile with the toggle on and no key
  promises processing that cannot run.

Fix as part of Mission 2's capability work — that mission replaces exactly this scattered
decision with one evaluated answer, so fixing it separately would mean writing a fifth copy.
See [`docs/MISSION_2_SPEC.md`](docs/MISSION_2_SPEC.md).

### Open — sign-out leaves the Credential Manager state behind

`FirebaseAuthGateway.signOut` calls `auth.signOut()` and flushes the access state; nothing in
`app/src/main/java/` calls `CredentialManager.clearCredentialState()`. Google's own guidance is
to clear it on sign-out so the next sheet asks which account rather than resuming the last one.

No observed symptom yet. It was noticed while tracing the "Activity is cancelled by the user"
report of 2026-09-10, which turned out to be airplane mode and not this — which is why it was
left out of that fix rather than folded into it. Trigger: the next mission that touches the
account screens, or the first report of sign-in reusing an account the user did not pick.

### Open — an approved account with unfinished onboarding has no way out

Found on 2026-09-11 while closing G3 step 2, on the device, by walking into it.

An account that is **approved** but has not finished onboarding, on a storage root with no
stored ASR key, can go neither forward nor back:

- Forward is closed because `OnboardingViewModel.canLeaveConnectStep` requires
  `Verified`, `Restored` or `VerifiedWithoutCleanup`, and `canVerify` requires a non-blank
  key. A user without the key cannot even attempt the verification that would release the step.
- Back is closed because `AccountScreen`'s approved branch renders heading and body only —
  `SignOutAction` exists on the pending and revoked branches, not this one. Sign-out otherwise
  lives in Settings, which sits behind `MAIN`, which sits behind `onboardingComplete`.
- Restarting the app does not help: `RootViewModel` maps `onboardingComplete=false` back to
  `ONBOARDING`, and the account step is where it lands.

The only exit left is uninstall. This is reachable by a real user, not just by a test: any
approved account whose key verification fails, or who signs in on a second account and so gets
a fresh empty root, is in it. Note that the trap is a consequence of storage isolation working
as designed (`DeviceDataOwner.rootFor` allocates a new empty root per uid, and
`SecurePreferencesStore` opens a per-root file), so the fix belongs in the UI, not in the
isolation.

Worked around during G3 by typing the key and leaving through "Finish later", which is exactly
the path a user without the key does not have.

Trigger: before G4, or the next mission that touches the account or onboarding screens.
Cheapest sufficient fix is probably a sign-out affordance on the approved branch of
`AccountScreen` — the state where the user is furthest from any other exit.

### Open — Gate question: should a typed API key survive a sign-out during testing?

Asked by Lars on 2026-09-11: re-entering the ASR key on every account switch costs real time
while the app is still only being tested.

Today it does not survive, and that is deliberate: `SecurePreferencesStore` opens
`storageRoot.securePreferencesName`, so the keys one account typed are **absent from the file
the next account opens**, not merely hidden. `StorageRoot`'s own doc states the intent — two
roots are separate the way two installs are separate. G3 step 0b and the isolation rows rest on
that property.

So this is a Gate question, not a task. If it is ever implemented it must be **build-variant
gated to debug**, and it must not be able to leak into a release build, because a shared key
store across accounts is precisely the thing the isolation evidence says does not exist. A
debug-only convenience that weakens the release invariant by accident would invalidate the G3
evidence retroactively.

Deferred. Not started, and not to be started without an explicit decision.

### Repo migration note

If OPTIQON Voice is moved into a new `LarsAhls/optiqon-voice` repository instead of renaming
this repository, migrate this backlog file with it.
