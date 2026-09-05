# Backlog

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

If OPTIQON Voice is moved into a new `LarsAhls/optiqon-voice` repository instead of renaming this repository, migrate this backlog item with it.
