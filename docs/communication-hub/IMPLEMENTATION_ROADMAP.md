# OPTIQON Voice — implementation roadmap and decision gates

**Version:** Discovery 1.0, 2026-09-07. **Status:** Proposed roadmap; no implementation, live provider configuration, signing, release, merge or external write is authorized. **Owner:** Lars. **Initial audience:** at most 10 invited Android testers.

## 1. Recommendation and scope

Use a small native communication hub, Firebase Authentication + Firestore for central identity/feedback, Firebase App Distribution for beta updates and a repo-owned local administrative workflow for Lars/Claude Code. Do not use Supabase, tawk.to, Help Scout, Intercom, a bespoke CRM or a continuously hosted server for the initial feedback core. Design for a future self-built knowledge-grounded AI helper, but do not build inference, retrieval infrastructure or a chatbot backend in this roadmap's initial delivery. No mandatory new recurring charge and no automatic paid-plan upgrade.

This is a sequence of bounded Missions, not one giant PR. Shared models and design contracts are allowed; each implementation Mission must have its own testable DoD, scope and risk/approval box. Claude may perform multiple implementation/test/repair steps within one approved Mission, but may not autonomously start the next product Mission. A relevant source/repo/environment change triggers cheap revalidation before continuing. No repo/code state is inferred from this roadmap.

## 2. Verified baseline and existing work

Canonical repository: `LarsAhls/optiqon-voice`, default main. Reviewed main `e1957c153e8f7f54b0e2a25186173c53374c00c9`; open PR #1 `feat/m1-shell-onboarding` head `d1d85b08196b4e9723cac3e35c2167faad283087`. These are historical review identifiers, not permanent current state. Revalidate exact refs, diff, CI, worktree and repo instructions before work.

PR #1 contains the premium shell, first-run language/Groq/permissions flow, Settings/Home/Profiles and tests. Its physical acceptance remains incomplete. The separate comments on that PR record the restricted-settings, BankID, Help/video and identity/feedback requirements. Do not duplicate completed provider/onboarding work or silently merge the open PR. An existing debug build is not proof of a production signing identity or a data-preserving release upgrade.

## 3. Cost and feasibility gate — before provider setup

### Recommended free baseline

Firebase Spark: no payment method and no automatic paid overage. Firebase Authentication social sign-in, eligible passwordless email authentication, Firestore and FCM have no-cost usage within published limits; App Distribution is no-cost. At the reviewed pricing, Firestore's basic free allocation is 1 GiB storage, 50,000 reads/day, 20,000 writes/day and 20,000 deletes/day. Spark direct email-link sign-in sending is limited to five/day, although email verification and link-generation quotas differ. These limits can change, so verify the current console/pricing documentation before setup.

A critical correction to earlier discussion: do not assume Cloud Functions, Cloud Run, Firebase Storage, a transactional email gateway or a model API can be deployed without a billing account merely because they offer a free usage tier. New default Cloud Storage buckets have Blaze-related requirements, and Cloud Functions deployment is not available as an arbitrary Spark backend. No live project may be upgraded to Blaze implicitly. Budgets/alerts are not spending caps. Any genuinely required paid-capable component must be separately approved with a realistic cost ceiling, technical enforcement and a shutdown/fallback strategy. A zero-recurring-cost V1 may defer optional attachments, AI, automated outbound email and push orchestration.

For ten people, the cheapest viable starting point is Google sign-in plus a controlled email-link alternative, text-only private feedback, Firestore client SDK with restrictive rules, and a trusted local administrative tool. Avoid additional infrastructure just to support rare edge cases before actual beta usage demonstrates a need. The local administrative tool can send an approved reply using the same protected data source; it must not become a public privileged API.

### Gate questions with explicit outcomes

- **Identity:** approve whether verified identity gates all core app use or only central features; verify Google/email linking, five-link/day quota, recovery and an honest unavailable/offline state. Do not advertise unrestricted email sign-in until solved.
- **Backend:** verify exact project ownership, Spark eligibility, Firestore rules/query design, App Check limitations and abuse controls. No service credential in APK, Git or public CI. Admin tool uses separate trusted credentials and least-privilege IAM; do not expose an unrestricted Admin SDK endpoint.
- **Attachments:** decide text-only V1 versus private screenshots. No public bucket and no assumption that new Firebase Storage is free on Spark. If private image storage cannot satisfy the budget, defer images rather than compromise security.
- **Push:** ordinary local completion notifications and optional centralized FCM are separate. A local authorized publisher/admin path may publish a general notice without a hosted trigger. Personal reply push requires a verified per-user target and a trusted sender; if that is not yet available, retain in-app unread/live updates and optional email as the initial delivery route. Do not implement a broad broadcast workaround for private replies.
- **AI:** no shared provider key in APK and no unbounded free API assumption. Defer inference until a secure, metered server path and explicit cost approval exist. A disabled/help-only UI is acceptable now.
- **Distribution:** validate permanent signing, existing-install certificate compatibility, package/version codes, rollback/recovery and App Distribution tester eligibility. Play remains paused. Do not create a new developer account or register a package merely to progress this roadmap.

## 4. Proposed execution order

### Phase D0 — Design and requirements acceptance (next)

**Model:** Opus. **Effort:** High. **Mode:** design exploration/plan, not code implementation. **Risk:** Fastlane. **Autonomy:** A2 equivalent, design/docs/read-only.

Provide Claude Design with the product contract, existing OPTIQON visual system/current shell, source-review findings and the design handoff in this directory. Explore one coherent hub and a small number of materially distinct variants, then choose a primary direction. Lars returns the design for ChatGPT's full review before any implementation plan is approved.

**DoD:** complete linked user flows, navigation, all essential states, responsive Android layout guidance, reusable components, copy, accessibility/notification constraints, account migration and explicit future-AI placeholders. Design must be feasible with the no-cost/minimal-backend contract; no mock implementation may masquerade as a working feature.

### Phase C0 — Codebase reconciliation and narrow technical plan

**Model:** Opus. **Effort:** High. **Mode:** Plan. **Risk:** Fastlane for read-only planning; Gate for any proposed live/security action. **Autonomy:** A2 until the appropriate plan is approved.

Claude Code inspects current repo/CLAUDE.md/branch/worktree, existing PRs, navigation, lifecycle, persistence, signing/versioning, auth/network dependencies and tests. Review the accepted design and source contract against actual code. Produce a dependency-ordered implementation plan, minimal schema/rules proposal, migration policy, threat model, test matrix and exact provider/action approvals needed. No broad rewrite or automatic merge. Decide whether existing PR #1 should be completed first or its docs need a separate follow-up; preserve its scope and state.

**DoD:** one current-state inventory, materially unresolved technical decisions, target interfaces, lowest-cost safe solution, acceptance tests and bounded Mission envelopes. Do not plan live creation against a guessed project identifier.

### Phase M1 — Finish existing shell/installation acceptance

**Model:** use current routing after freshness check. **Effort:** High for material plan changes, otherwise sufficient for scoped implementation. **Mode:** Auto for clear reversible fixes, Plan if approach uncertainty remains. **Risk:** Fastlane; A3 for approved repo implementation.

Complete the missing restricted-settings guidance, BankID notice/shortcut and Help/video access already recorded on PR #1, without expanding it into a full communication backend. Do not force restricted settings on every Android install. Reuse permission checks, allow a safe Settings fallback, and keep Voice controllable when Accessibility is disabled. Verify the existing onboarding, custom provider path, security-sensitive fields, app restart and physical dictation. No Play, signing or public release in this phase unless separately approved.

**DoD:** original and amended acceptance satisfied, relevant tests green, physical smoke recorded, no secret exposure or unexpected service/injection change. Merge only under a separate explicitly approved action. If PR #1 has materially changed, re-plan/reconcile rather than relying on this historical description.

### Phase M2 — Permanent beta release identity and update preservation

**Model:** Opus. **Effort:** High. **Mode:** Plan. **Risk:** Gate. **Autonomy:** A4 only within Lars's exact approval box.

First inspect actual installed APK/package/certificate/version and current signing workflow. Establish a permanent private release-signing identity with backup/recovery controls and cryptographic fingerprint verification. Plan any debug-to-release migration honestly; Android cannot normally update an app signed with an incompatible certificate. Do not silently uninstall or clear tester data. Assess whether an existing debug-signed install can be upgraded with a compatible build before switching to a permanent release identity. Where a one-time reinstall is unavoidable, require explicit data-loss/migration communication and approval.

Configure Firebase App Distribution only for the approved beta project/tester group, with separate beta updater dependencies/build variant and protected release credentials. Upload a deliberately approved release candidate, install version A and then B, and verify same application identity, increasing versionCode and unchanged local API keys/profiles/history/onboarding. Check permissions/restricted-settings behavior and update cancellation/retry. Keep future Play-specific signing/update behavior isolated; do not implement Play publication now.

**DoD:** a verified A-to-B upgrade on a real supported device with no unnecessary setup or credential replacement, reproducible signed artifact/certificate evidence, documented recovery and a known distribution channel. No public release or account migration outside the box.

### Phase M3 — Verified identity foundation

**Model:** Opus. **Effort:** High. **Mode:** Plan. **Risk:** Gate for auth/provider configuration and data rules. **Autonomy:** A4 after exact approval.

Implement the approved Google and email-link identity paths with persistent sessions, account-linking/recovery behavior, required display name and optional phone. Keep provider secrets/local dictation data outside the account service. Use a small verified-user profile and protected role source; ordinary users cannot edit their own privileged role or another user's profile. Establish emulator-based auth/rules tests before live configuration. Resolve existing-user migration so that an update cannot unnecessarily clear local settings or force re-onboarding. Decide and document account deletion/retention and offline behavior.

**DoD:** two distinct test users with stable UIDs, verified email semantics, no duplicate account history from supported linking paths, safe sign-out/recovery, protected admin identity, no default privileged access, and existing local dictation preserved. Live auth tests require synthetic test accounts and approved environment scope.

### Phase M4 — Feedback kernel and read-only administration

**Model:** Opus. **Effort:** High. **Mode:** Plan for schema/rules/admin trust boundary; Auto for later scoped UI work. **Risk:** Gate for live data/auth/rules/admin credentials; A4 within box. Repo-only UI/tests may be Fastlane A3 once interfaces are approved.

Implement minimal Firestore collections and rules for owners, cases, public messages, internal admin events and protected role assignment. Prefer text-only initial feedback. Use stable UID, controlled fields/statuses, real server timestamps, bounded pagination and idempotent submissions. Reuse Firestore offline persistence where its guarantees satisfy the pending/confirmation contract; add a local queue only if necessary. Make the user-facing state distinguish queued from confirmed. Add an event/decision history and read state rather than a single mutable reason or a timestamp cleared on opening.

Create a repo-owned local admin adapter with separate read and narrow write capabilities. The first approved capability is read-only 'Hämta feedback': exhaustive aggregate, paginated new/changed records, parked ledger, stable checkpoint, exact source content/authorized attachments, deduplication and evidence-based recommendations. It must not create public issues or send replies merely because an analysis finishes. A later narrowly approved write command previews case IDs, target users, public text and status changes, verifies current state and records audit/idempotency data. Use least-privilege trusted credentials; never place private feedback in public GitHub artifacts.

**DoD:** two-user isolation tests; forbidden cross-user reads/updates and forged admin messages rejected; Lars can read both; read-only triage enumerates all items with no missing page; a failed retry cannot lose or duplicate a submission; approved reply/status reaches only the correct user; old reasons/history remain; parked items stay visible; a partial admin write is recoverable. Use emulator/synthetic data before a separately approved live smoke. No automated product decision or response.

### Phase M5 — Native Help, news and communication UX

**Model:** appropriate implementation model, normally Sonnet; High if material architecture/risk uncertainty reappears. **Mode:** Auto for bounded accepted-design implementation, Plan for unresolved dependency. **Risk:** Fastlane A3 for UI; Gate for publishing/live rules/integration changes.

Build the accepted two-part hub, My cases, feedback composer/details, private replies/status history, knowledge articles and a common in-app news/update destination. Preserve the compact main UI and keep dictation controls separate. Design and implement useful empty/loading/offline/error/denied states. Store guides as maintainable versioned content, with video source/fallback. Add a placeholder for future AI assistance, clearly labelled as unavailable or using non-AI guide search, not a fake chat service. A simple local admin publishing process may create approved news records; drafts cannot become public by client choice.

**DoD:** full native flow, correct deep links, one shared source of release/support content where applicable, accessible layouts on representative Android sizes/versions, correct per-user unread state and all affected existing dictation tests green. No actual AI inference included.

### Phase M6 — Updates, release notes and notifications

**Model:** Opus for release/security plan; appropriate implementation model for scoped UI. **Effort:** High for Gate plan. **Mode:** Plan for release/integration, Auto for accepted bounded UI. **Risk:** Gate for release/push provider/live actions; A4 within box.

Implement a single update source adapter for beta distribution. Settings 'Sök efter uppdatering', News release detail and optional notification share one state machine. Use the documented Firebase App Distribution Android SDK for eligible testers and supported install/download flow; do not build a generic custom APK downloader. Distinguish latest/available/checking/failure/download/installer-declined/installed. Release notes are centrally published only after Lars approves the target/version and verifies availability. The actual installed version determines whether the update remains outstanding.

Add FCM only after update lookup and publishing are reliable. A trusted local/CI publisher can send an explicitly approved general release message. Personal reply notifications require an authorized per-user target and a trusted sender; defer if the approved free architecture cannot deliver them securely. Never embed messaging server credentials in the app, trust client-specified recipients or broadcast private feedback. Use Android notification channels, manual refresh and durable in-app unread state so a denied/delayed/cleared notification does not lose information. No custom launcher badge promise, and no push interruption of recording.

**DoD:** approved release becomes discoverable in-app, manual check correctly distinguishes no update from network failure, installed update preserves local data, user can decline/retry, a notification opens the right destination, private message cannot reach the wrong user, no paid plan is silently enabled, and beta updater is excluded from future Play builds.

### Phase M7 — Future own AI support (design only now)

**Model:** Opus. **Effort:** High. **Mode:** Plan. **Risk:** Gate for shared model credentials, server/external processing and user-data access. **Autonomy:** A4 after a new exact approval.

Separate future Mission, not authorized by the current roadmap. Evaluate the actual guide corpus, ordinary keyword/full-text retrieval before vectors, model/provider quality, model cost, latency, privacy/legal notices, prompt-injection defenses, answer citation, refusal/uncertainty behavior and human escalation. Use a trusted metered inference boundary, no shared secret in APK and no automatic upload of local dictations. Start with a small evaluation set of real product questions (sanitized) and test whether retrieval actually improves answer quality. Do not add a vector DB or an agent tool framework merely because it is fashionable.

**DoD for future plan:** explicit cost cap and technical enforcement, chosen retrieval/model strategy supported by tests, secure backend option, user consent/data handling, no autonomous privileged actions and a working fallback to human support. Implementation requires a new approval.

### Phase M8 — Future long-running transcription jobs

Separate product Mission. Evaluate durable recording/job/result semantics, safe background execution, cancellation/retry/process death, data retention and return destination. Local completion notification opens the saved result; no delayed injection into a different text field. Do not claim the current short-dictation service already supports meetings reliably. No implementation now.

## 5. Cross-cutting verification and release discipline

Every implementation Mission uses the cheapest sufficient repo/test checks before broader reasoning. Revalidate affected branch/base, worktree, exact diff, current tests and target environment. Gate plans must include exact allowed actions, target project/environment, secrets/permissions, rollback/fallback and postflight. Live writes, account/provider configuration, production signing, publication, destructive cleanup and merge are never implied by an earlier docs approval.

No broad repo rewrite, duplicate persistence engine, new backend framework, paid plan or public release merely because an internal phase finished. Keep durable decisions/evidence in relevant PR/issue/repo files. Use private protected storage for raw feedback and credentials, not public GitHub. An unexpected cross-user data leak, failed authorization test, incompatible signature, unknown project identity or material data loss is a hard stop.

## 6. End-to-end beta acceptance

A first invited tester installs the correct signed version, completes only necessary setup, verifies identity, connects a personal provider and can dictate normally. A second user cannot access the first user's cases or attachments. Each can submit feedback, including an offline/retry scenario, and see only their own acknowledgement/history. Lars retrieves all relevant cases read-only, gets a complete ledger and approves one reply/status action. The correct user receives the update in-app; an unavailable push cannot lose it. A published release appears under News and Settings; A-to-B installation preserves the original API key, profiles, local history and onboarding status. A user with notifications disabled can still discover it manually. The Help/video and BankID/Accessibility guidance work on the supported Android matrix without device-specific hacks. The AI entry is clearly future functionality and does not pretend to answer through a backend that is not present.

## 7. Future distribution backlog

Google Play remains paused. Investigate the free Android Developer Console limited-distribution account (up to 20 authorized devices) before wider distribution or when Lars asks for app improvement opportunities. Its eligibility, commercial suitability, certificate/package registration and non-upgradeable transition to full distribution require fresh official verification. It is not a replacement for Firebase App Distribution and must not be registered automatically. See `BACKLOG.md`.

## 8. Official references

- https://firebase.google.com/pricing
- https://firebase.google.com/docs/auth/limits
- https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
- https://firebase.google.com/docs/firestore/security/rules-conditions
- https://firebase.google.com/docs/firestore/manage-data/enable-offline
- https://firebase.google.com/docs/firestore/query-data/query-cursors
- https://firebase.google.com/docs/app-distribution/add-remove-testers
- https://firebase.google.com/docs/app-distribution/set-up-alerts?platform=android
- https://firebase.google.com/docs/app-distribution/troubleshooting?platform=android
- https://firebase.google.com/docs/cloud-messaging/server-environment
- https://developer.android.com/developer-verification/guides/limited-distribution
- https://support.google.com/android-developer-console/answer/16561738?hl=en
- https://developer.android.com/google/play/app-updates
