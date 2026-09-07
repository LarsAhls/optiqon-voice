# Backlog

## Android Developer Console — limited distribution and future verification

**Status:** Backlog / research only. **Priority:** Revisit before a wider beta or when Lars asks for app improvement proposals. **Added:** 2026-09-07.

Investigate Google's free limited-distribution developer account for up to 20 explicitly authorized devices. Compare its eligibility, commercial-use implications, identity requirements, package-name registration, signing certificate ownership, device authorization and later migration to full distribution against the ordinary Android Developer Console route. Do not assume this account replaces Google Play, Firebase App Distribution or a full commercial developer registration. Do not create an account, register a package, change signing, or publish anything without Lars's explicit approval.

Current decision: Google Play is paused because the intended organization setup lacks a D-U-N-S number. Firebase App Distribution remains the preferred near-term beta channel for at most 10 testers. The limited account is interesting but not required to proceed in Sweden now. Review the staged Android developer-verification rollout and the latest official terms before recommending registration. Preserve the option of future commercial distribution and avoid unnecessary account/package migration.

Acceptance for the later research: current official eligibility and cost, exact differences between limited and full accounts, commercial suitability, migration/upgrade consequences, package/certificate implications, and a recommendation for Lars's actual distribution needs. No live account action is included.

References:
- https://developer.android.com/developer-verification/guides/limited-distribution
- https://support.google.com/android-developer-console/answer/16561738?hl=en
- https://developer.android.com/developer-verification

## Communication hub, feedback and own AI support

**Status:** Product/design discovery. **Owner:** Lars. **Implementation:** not authorized.

Build an OPTIQON-branded communication surface with Help & feedback and News, verified identity, private feedback and human-controlled triage. Prepare the UI and knowledge content for a future self-built AI assistant, but do not implement the AI service now. Prefer a small Firebase-based solution over tawk.to, Intercom, Help Scout or a bespoke full support platform. No Supabase. The beta target is at most 10 people and no mandatory new recurring charge.

The detailed product contract, source audit, design brief, unresolved gates and implementation sequence are in `docs/communication-hub/`. The uploaded legacy feedback specification is a functional reference, not an implementation or security authority. Preserve its effective feedback-to-decision-to-user loop while correcting name-based identity, privileged API access, public attachments, lossy offline queue, premature seen markers and missing decision history.

Required result: users see only their own feedback; Lars can see all; Claude Code can fetch new and parked feedback read-only, compare it with repo evidence, recommend actions and prepare replies. Material decisions and user-facing/external writes require Lars's approval. A change is only reported as delivered after appropriate verification and availability to that user. No raw private feedback is copied to public GitHub issues or logs.

## Reliable beta updates without re-onboarding

**Status:** Planned, dependent on release-signing and migration verification.

Establish a permanent release identity and verify an A-to-B update without clearing app data. Preserve API keys, profiles, history, onboarding state and permissions. Use Firebase App Distribution for the small beta, with one in-app update view, manual check, release notes and appropriate notification paths. FCM push and the Android launcher notification dot are optional attention mechanisms, not the source of truth. Do not promise silent installation, a custom launcher badge, or automatic migration across incompatible signing certificates. Isolate beta updater dependencies from future Play builds. The full rollout sequence is in `docs/communication-hub/IMPLEMENTATION_ROADMAP.md`.

## Vibe-code technical debt audit for OPTIQON Voice

**Status:** Backlog

**Timing:** Run after the OPTIQON Voice rebrand, production signing/release workflow and first physical-device smoke are stable.

### Goal

Run a focused technical-debt audit of OPTIQON Voice specifically for risks that are plausible in an AI-first / vibe-coded Android codebase.

This is **not** a general cleanup or stylistic refactor. The purpose is to find the small number of places where the app's AI-first origin could create future bugs, security issues, release/update problems, or disproportionately expensive debugging sessions.

### Audit focus

Prioritize high-impact surfaces:

- Android lifecycle / foreground service / boot behavior
- Accessibility and text-injection safety
- overlay/bubble lifecycle and race conditions
- microphone/audio recorder resource handling
- network/API error handling, retries and cancellation
- credential/secret handling and logging
- release signing / versioning / update chain
- Room schema, migrations and persistence invariants
- Hilt/KSP/generated-code assumptions
- coroutine/threading/concurrency hazards
- permissions and exported-component semantics
- state restoration / process death / edge cases
- provider/model configuration validation
- tests that look green but do not verify real behavior
- stale/dead code, duplicated logic and hidden coupling introduced by iterative AI changes

### Required output

Produce a ranked list of roughly **5–10 concrete findings**, each with:

1. evidence (file/path + behavior)
2. severity / likelihood
3. user/business impact
4. whether it is an actual defect, latent risk, or maintainability debt
5. cheapest sufficient verification
6. recommended fix or explicit recommendation to leave it alone
7. rough effort and regression risk

Group findings into:

- **Fix now** — high value / low-to-moderate effort
- **Fix when touched** — real debt but not worth a dedicated mission yet
- **Accept / monitor** — low-value cleanup or speculative risk

### Boundaries

- Do **not** rewrite the app because it was AI-generated.
- Do **not** reward cosmetic code cleanliness for its own sake.
- Do **not** change architecture unless evidence shows the current design is causing meaningful risk or recurring cost.
- Prefer tests, invariants and targeted hardening over broad refactors.
- Preserve current app behavior unless a defect is demonstrated.

### Definition of Done

- 5–10 evidence-backed findings max, ranked by expected future cost/risk.
- Critical user paths and release/update path explicitly reviewed.
- Existing tests assessed for meaningful coverage, not just test count.
- Clear recommendation for which items deserve separate implementation issues/Missions.
- No implementation changes as part of the audit itself unless separately approved.

### Repo migration note

This backlog now belongs to the standalone `LarsAhls/optiqon-voice` repository. The old migration instruction is historical; do not reopen or duplicate a completed repository migration.
