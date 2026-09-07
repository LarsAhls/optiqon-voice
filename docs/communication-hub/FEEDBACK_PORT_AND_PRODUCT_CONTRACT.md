# OPTIQON Voice — feedback and communication contract

**Version:** Discovery 1.0, 2026-09-07. **Status:** Proposed product contract for Lars's design review; not implemented or approved as a live-data architecture. **Scope:** OPTIQON Voice only. **Owner:** Lars.

## 1. Purpose, source and evidence

Create a private, low-friction communication loop between an identifiable user and OPTIQON: submit a question/problem/idea, receive a reliable acknowledgement, have it evaluated, receive a human-approved decision and eventually learn when the result is actually available. The same surface should accommodate news and a future self-built AI helper without becoming a general CRM or a collection of competing inboxes.

The functional reference is Lars's uploaded `FEEDBACK_FUNCTION_SPEC.md` (2026-09-07, 750 lines, SHA-256 `dd568332bdaa59b45c1d1df4f2f392b96c8fc748c3fe6199949ec092b4da0d6c`). It describes another product's repository implementation and explicitly says its live database was not read. Treat the document as source evidence of intended/current code behavior, not proof of live operation or a safe API to reuse. Its sections 1–11 are mapped below. No legacy database, tenant, service key, account, screenshot or user data is to be migrated into Voice. The original complete document should remain available as the accompanying source attachment; this contract is the portable adaptation, not a claim that the legacy system has been repaired.

Current Voice evidence at review: `LarsAhls/optiqon-voice`, main `e1957c153e8f7f54b0e2a25186173c53374c00c9`, open premium-shell PR #1 head `d1d85b08196b4e9723cac3e35c2167faad283087`. Revalidate before execution. The existing app stores provider secrets locally and has an onboarding-completion flag. A separate communications/feedback backend is not established by the inspected source. Do not infer its implementation from the other product's document.

## 2. What to preserve and what to change

| Legacy reference | Voice decision / improvement |
|---|---|
| A universal feedback entry and simple create/read experience | Preserve discoverability, but use one native Help & news destination, not another persistent overlay competing with dictation. |
| Acknowledgement, status badge, concise reason and push | Preserve the entire feedback-to-decision-to-user loop. Make delivery and read state explicit and durable. |
| Admin reads all; submitter reads own | Preserve the intent and enforce it in backend authorization using immutable identity, never a name or client-selected filter. |
| Triage outside the application | Preserve. Lars uses a small repo-owned administrative tool and Claude Code; no large in-app admin UI is required initially. |
| Four states: new, implemented, parked, rejected | Retain their semantics, with a small mapping to user-facing lifecycle states so 'planned', 'working' and 'delivered' cannot be confused. |
| Parked items always revisited; complete ledger | Preserve. Use server-side totals, paginated exhaustive iteration and durable checkpoints rather than an unbounded mobile query. |
| Observation, hypothesis, root cause, recommended fix; priority and confidence | Preserve. Treat the user's proposed solution as evidence of friction, not automatically as a requirement. |
| Source screenshot read before hypothesis | Preserve when an attachment exists and is authorized to be read. Images are evidence, not necessarily proof of root cause. |
| No deletion / offline queue | Preserve non-loss and explicit delivery semantics, not the flawed literal implementation. Support privacy deletion/retention obligations. |
| Public screenshot URLs and name matching | Reject. Use private storage, immutable UID and explicit authorization. |
| Service-role API with no role checks | Reject. Separate least-privilege client access and privileged administration. |
| One JSON metadata object for everything | Use explicit indexed status/owner/timestamp fields and a bounded metadata map; validate server-side or through database rules. |
| Poll everything each minute | Prefer bounded live listeners/refresh and stable unread state. Avoid perpetual full-table reads. |
| Clear unread on opening panel | Replace with per-item acknowledgement or a deliberate mark-as-read for actually presented content. |
| Decision reason overwritten or erased | Preserve previous decisions and messages; never silently erase the user's earlier explanation. |
| No decision history | Add a minimal immutable event/message history with actor and time. |
| Automatic triage status writes | Separate analysis and approval. User-facing writes are material decisions, not an automatic side effect of fetching feedback. |

## 3. Product boundaries

The beta is invitation-only, initially at most 10 people. New mandatory recurring cost: zero. No Supabase, outsourced support platform, new general-purpose CRM, custom password service, or AI chatbot implementation in the current scope. Firebase Auth + Firestore is the preferred candidate, subject to the gates below. Firebase App Distribution remains the beta installer; future Play and developer-verification decisions are separate. A small trusted administration component may run locally rather than as a permanently hosted service.

Core dictation, local history, profiles, personal provider keys and processing remain local/direct-to-provider by default. A communication feature must never silently capture the focused application's text, record audio, read history or transmit provider keys. Only user-authored feedback and explicitly selected/reviewed diagnostics may leave the device. No access to the microphone, Accessibility or overlay data is granted to the future AI helper by virtue of being part of the same app.

## 4. User experience and information architecture

One entry called **Hjälp & nyheter** (English equivalent when localized). It has two principal destinations:

- **Hjälp & feedback:** searchable guides, 'Fråga OPTIQON' (future AI entry), report a problem, suggest an improvement, ask a question, and 'Mina ärenden'. A question, suggestion and bug share the same underlying case/message model. The AI is not a separate conversation silo; unresolved questions can be handed to Lars with the user's consent and the relevant safe context.
- **Nyheter:** release notes, important product information and a link to an available update. User-facing announcements are distinct from private cases and from local processing jobs.

The existing floating dictation control stays focused on dictation. If a feedback entry already exists or a launcher shortcut is retained, it opens the common full-screen destination. A badge is an attention affordance, not an additional information architecture. Do not add a persistent floating support window on top of other applications simply to make the product feel premium.

Help and onboarding reuse one source of guidance. Initial topics: installation, restricted settings, microphone/overlay/Accessibility/notifications, BankID compatibility, Groq/key setup, profiles, updates and troubleshooting. Lars's installation video is a future asset; use a valid embedded/player destination when provided and a useful text fallback until then. No CMS or video backend is needed solely for this.

## 5. Identity, roles and account lifecycle

Recommended UX: Google sign-in or passwordless verified email; display name required, phone optional. The persistent Firebase UID is the identity, not the email or name string. Normal sessions survive app restarts and updates. Google and email identities must be linked deliberately where supported; never merge accounts merely because a typed name matches. Do not build password storage or SMS verification without a demonstrated need. Firebase's direct Spark email-link sending quota is small; verify the exact quota and a workable alternative before promising unrestricted email onboarding.

Account access is a product gate: Lars must approve whether verification is required for all core use or only central communication. The current recommendation is that already-working local dictation remains available during temporary identity/network failure. New central writes require a verified session. A lost/revoked session has a recovery/re-authentication path. Provide sign-out, account deletion/request handling, data export/retention policy and a safe way to unlink a device. Deleting a local app is not the same as deleting an account or central feedback.

Roles are `user` and `admin`. Admin authority must be derived from a trusted server-side role assignment or exact protected UID allowlist, never a client-editable field. Admin read/write capabilities are separate. User-specific data is protected with the authenticated UID and validated ownership. An email or display name may be shown as a snapshot for context but does not authorize anything. A user must not be able to grant themselves admin rights, change an owner, impersonate support or set protected workflow fields. 

## 6. Minimal conceptual data model

The exact Firestore collections/paths, indexes, rules and query strategy are HOW decisions for the implementation plan, but the following semantic objects must be representable without a later rewrite.

### User profile

Stable UID, display name, verified email, optional phone, created/updated time, minimal account preferences. Trusted roles are not writable by ordinary users. Avoid duplicating authentication state unnecessarily; verified email must come from the authentication provider, not a user-controlled text field.

### Feedback case

Stable case ID, immutable owner UID, type (`question`, `problem`, `suggestion`), title/description, created/updated timestamps, lifecycle state, optional severity and app/Android version context, optional private attachment references, last user-visible update time and optional linked development work. The client may create only allowed fields, bounded lengths and allowed enum values. Server timestamps and idempotent submission identifiers are preferred. A trusted admin may update protected fields; direct user updates to status/owner/role are forbidden. Do not permit arbitrary client-authored message roles.

### Case messages / decisions

Stable message/event ID, case ID, actual actor UID or trusted system actor, role, visibility (`public_to_owner` or `internal_admin`), text, timestamp, optional previous/new state and evidence references. User messages and public support replies are distinct from internal triage notes. Append-only history is preferred; corrections produce a new event rather than erasing the old explanation. A client cannot create a support/admin/system message by submitting a role string. No user may fetch internal notes.

### News / releases

Stable announcement ID, type, published/draft state, target beta channel and compatible version range if relevant, release version/code, short description, full notes, optional validated destination/action, publication/withdrawal times. Only trusted publishing tools can create or change published content. Drafts/internal release evidence must not be readable by ordinary users. The update service, not a news text field, determines whether an installable build is actually available to this tester.

### Read state and delivery

Per-user unread acknowledgement or equivalent durable per-item state, with separate categories for private replies and news. A push is a hint, not proof of delivery or reading. News read state is independent of whether the update has actually been installed. The local installation state is authoritative for whether the app is updated. Notification preferences are per meaningful category, and the OS remains the authority for system-level permission. Never use a client-supplied timestamp as proof of a privileged decision.

### Attachment and local pending submission

Attachments are optional, private, bounded by validated content type/size and linked to the correct owner/case. Storage is not public merely because filenames are unguessable. A local pending submission has a stable idempotency key, owner UID, payload, state, retry metadata and an explicit retained/failed/confirmed outcome. Authentication changes must not leak the previous user's pending data to the next user.

## 7. Status and communication semantics

Keep workflow state and the message shown to the user conceptually distinct. The simplest useful public lifecycle is `Mottaget`, `Under granskning`, `Planerat`, `Pågår`, `Levererat`, `Parkerat`, `Inte planerat`. It is acceptable for V1 to expose fewer intermediate states if the underlying status is unambiguous. Do not expose an entire engineering workflow or promise delivery dates without approval.

- `Mottaget`: persisted by the server, not merely queued locally.
- `Under granskning`: Lars has accepted/started triage; not an implementation commitment.
- `Planerat`: Lars has decided to pursue an action; may be linked to an internal issue.
- `Pågår`: relevant implementation actually started; avoid automatic claims based on a branch existing.
- `Levererat`: the change is verified and available in the relevant user's release/environment. A merge alone does not qualify.
- `Parkerat`: not now; retains a review trigger and appears in recurring triage. Explain why without implying a delivery promise.
- `Inte planerat`: explicit decision with a respectful reason. It may be reconsidered without losing history.

Preserve the original four-state semantics for migration/triage mapping: `new` → received/under review; `parked` → parked; `rejected` → not planned; `implemented` → delivered only after the appropriate real-flow verification. Do not automatically set `implemented` when an issue closes or a PR merges.

A public status change should produce a short human-readable explanation. The case page displays the complete history rather than a single overwritten reason. A user can ask a follow-up or add information to the same case. Internal priorities, confidence, estimates, root-cause hypotheses and private development notes remain internal. A user can never see another user's case just because multiple cases map to one development issue.

## 8. Submission, offline and non-loss contract

1. The user enters text, optionally selects an allowed attachment, and presses Send. Prevent duplicate taps and validate size/empty text before sending. Image-only feedback may use an explicit user-visible placeholder or a permitted image-only schema; do not silently manufacture misleading user text.
2. Save a durable local pending item before network submission when offline/retry support is enabled. The UI says 'Sparat på enheten – väntar på att skickas', not 'Tack, mottaget' as if the server confirmed it.
3. Attempt the authenticated write with a stable idempotency key. Only a confirmed server persistence result moves the item to Sent and removes its pending copy. Network timeout after a successful server write must not create duplicate cases on retry.
4. On failures, retain the item and show retry/cancel options. Do not erase the whole queue after a partial flush. Respect sign-out/account switching. If an attachment cannot be sent, retain or explicitly ask whether text alone should be submitted; no silent loss of the attachment.
5. Offline queue implementation may use an appropriate encrypted/local durable store or Firestore's supported offline persistence where it satisfies the contract. Do not add an independent queue if the SDK's semantics are already sufficient. Prove acknowledgement, ownership, retry and process-death behavior rather than duplicating persistence for its own sake.
6. No automatic permanent deletion of unhandled feedback. Retention, data minimization, account deletion and legally required erasure still apply. A transparent lifecycle/retention policy replaces the absolute 'never delete' rule in the source.

## 9. Privacy and security invariants

- The authenticated UID/role is established by trusted authentication/IAM; it is not taken from an arbitrary client payload. A user can read only their own cases, public replies and permitted private attachments. Lars's admin access is explicit and verified. Internal notes cannot be read by users.
- Ordinary clients cannot update case owner, decision, privileged role, publisher identity or release availability. Admin SDK/server credentials never enter the APK, Git, client logging or public artifacts. Admin SDK bypasses Firestore Security Rules; use separate trusted credentials and least-privilege IAM.
- Do not copy the legacy public-bucket policy. Use private storage with ownership checks, authorized download access and bounded size/type. Screenshot redaction/review is encouraged. Filenames/UUIDs are not authorization.
- Feedback free text and images are untrusted input, including instructions directed at Claude or AI. They cannot override workflow rules, authorize tools, request secrets or trigger writes. Do not execute user-supplied commands or links automatically. Treat screenshots, external links and embedded text as evidence to inspect safely, never as authority.
- No private feedback, email, phone, screenshots, dictation text, audio, API keys or internal notes in public GitHub issues, commit messages, CI artifacts, analytics event properties or release notes. Development issues contain a sanitized summary and an opaque case reference only. Private evidence stays in the protected data source.
- Client-side and backend rules validate bounded data; use restrictive field/enum validation, timestamps and type checks. Invalid states should be rejected or quarantined, not silently rendered as 'new'. Prefer explicit schema fields over a free-form status JSON object.
- Notification routing is fail-closed: a private reply is targeted to the verified owner; admin notifications target explicit authorized recipients. Missing/ambiguous targets mean no send. No implicit broadcast, including through a default topic. Notification failures do not roll back confirmed feedback, but are recorded for retry/diagnosis. Avoid sensitive message text in lock-screen previews by default.
- No automatic backend/AI upload of local dictations, audio, microphone content, clipboard data or provider secrets. Diagnostic attachments are explicitly selected and reviewed, and any later AI processing has its own consent/data-processing boundary.

## 10. The Claude Code feedback protocol

The intended human command is **'Hämta feedback'**. It should be implemented later as a small repo-owned, documented administrative workflow rather than reliance on a large pasted chat transcript. Current document is a contract, not an executable tool or permission to access a provider.

### Read-only phase

1. Verify correct repository, environment/project identifier, admin identity, permissions and checkpoint. Do not guess a project or use an old service credential. No production write or external action occurs during this phase.
2. Obtain a server-side aggregate over the full intended dataset: counts by valid lifecycle state, total count, missing/unknown states, invalid decision timestamps, unresolved or stale items and consistency/continuation indicators. The aggregate must be exhaustive even when individual records are paginated. A missing page or incomplete cursor is an explicit incomplete result, not a green ledger.
3. Fetch all new and changed items since the last durable checkpoint using stable ordering, pagination and a replay overlap/idempotent cursor strategy. Always include parked items in the ledger; a deep review or due-review trigger reads their full details. Do not rely on a blind fixed LIMIT or a local timestamp that could silently skip updates. Checkpoint advancement follows successful complete processing, not merely a request attempt.
4. For each item, preserve exact user wording and inspect authorized attachments before a causal hypothesis. Separate observation, hypothesis, verified root cause and recommended remedy; use confirmed/likely/possible. Explain the real friction and alternatives, not only the user's proposed fix. Assign blocker/P1/P2/P3 and product value/effort/risk.
5. Revalidate relevant repo/branch/current tests, existing issues, approved plans and contradictory evidence cheaply. Do not perform a broad repo audit for every suggestion. Group duplicate themes while retaining a link to every private source case. A pending numbered feedback series should be collected until complete unless a blocker requires immediate action.
6. Report a complete ledger and a ranked recommendation: fix now, follow plan, change plan, park, reject or request more evidence. Include source case IDs, proposed scope/DoD, dependency, risk and cheapest required verification. Internal personal data is shown only in the authorized private working context; public artifacts use sanitized references.

### Approval and write phase

Lars decides material product priorities, rejection, delivery promises, external messages and scope. Analysis never automatically creates public issues, changes live status, sends replies, starts an implementation Mission or announces a release. A subsequent explicitly approved action may use a narrowly scoped administrative write tool with immutable actor attribution and an audit/event record. Provide a preview of exactly which cases, messages and statuses will change. Validate the target UID/case ownership before dispatch. Fail closed on ambiguity or changed underlying state. Use idempotency/retry protection for partial failures and record the result. Never use an arbitrary raw Admin SDK operation as the normal user-facing workflow.

GitHub owns implementation work, Firestore owns private user cases. One GitHub issue may address many private cases, but each case retains its own status and communication. Case-to-issue mapping is durable, sanitized and access-controlled. A merged PR may justify 'work completed internally'; only relevant verification and availability justify 'delivered'. At that point Lars approves an appropriate public reply and a release link if one exists. Do not automatically announce unrelated implementation details to every user.

### Triage modes and durable state

Default: new/changed items, integrity aggregate, all parked titles/due-review indicators, relevant cheap evidence and concise recommendations. Deep: explicitly requested full parked review, backlog/decision review, targeted source/code/test inspection and architecture alternatives. A live action is not approved merely by choosing deep mode. Maintain a minimum durable checkpoint/decision ledger in the protected admin environment or repo-safe opaque metadata, never with private raw feedback in a public repo. A failed or interrupted run resumes safely without losing or double-processing cases.

## 11. Notifications, news and processing jobs

Use one reusable internal attention/notification routing layer, not one new inbox per feature. It receives typed events such as `case_reply`, `news_published`, `release_available` or `dictation_completed`, and resolves an authorized destination. Private case events never broadcast. Broad news publication is an explicit approved publisher action with an intended audience; use a dedicated topic only for genuinely public/beta-wide information. No client can impersonate an admin publisher.

The in-app unread indicator is durable and independent of system notification delivery. A case is marked read only when the relevant content is actually presented or the user explicitly marks it read; simply opening the hub must not clear all unrelated unread items. News can use a last-seen published version or per-item acknowledgement as long as it handles edits and multiple devices correctly. Badge count/priority must be explainable and not overwhelm Home.

Android launcher badges are OS/launcher-dependent notification dots or counts, not a guaranteed custom red exclamation mark. Support Android's documented notification channels and relevant API levels; no Pixel-only or private launcher APIs. Notifications may be denied, delayed or cleared, so opening the app and manual refresh must recover the correct state. Use meaningful channels so users can silence news without losing personal replies or important job-completion notifications.

Long-running meeting transcription is a separate future product capability, not feedback. Its local results and safe background execution need their own scope, persistence, failure/recovery and destination rules. Do not treat the current short-dictation pipeline as a verified meeting-processing feature. A completion notification must open the correct saved result; never inject into a newly focused or unrelated field after the original context has gone away.

## 12. Update and release contract

Use a single update screen reached from Settings, News and an optional update notification. The beta implementation should delegate authorized release lookup/download/install to Firebase App Distribution's supported Android SDK or its documented distribution flow. Do not build a custom APK downloader or expose an arbitrary install URL to user-generated content. A release announcement is not proof that a compatible installable build exists; the updater verifies eligibility/version/signing and distinguishes 'latest', 'available', 'checking', 'offline/error', 'downloaded', 'installer declined' and 'installed'.

A normal A-to-B update must retain package identity, compatible signing certificate, increasing versionCode, local API keys, profiles, history and onboarding state. Existing users must not be sent through full first-run setup again. Only genuinely new/missing permissions or material account migration steps may require a targeted explanation and action. Android owns installation consent. The beta updater must not be bundled into a future Play distribution in a policy-incompatible way. Keep manual update check available when notifications are disabled.

## 13. Acceptance and tests

Required security tests: user A cannot list/read/update B's cases, messages, internal notes or attachments; user B cannot obtain A's private data through query/filter tampering; user cannot change owner/role/status or publish news; a forged support message fails; user cannot read admin-only drafts/notes; admin authority is not inferred from a display name; revoked/expired sessions fail appropriately; malformed/unknown status is rejected; private notification without a valid target is skipped; public broadcast requires authorized publication.

Required reliability tests: successful submission gets a real server acknowledgement; offline pending is labelled honestly; failed/partial queue flush retains data; retries do not duplicate cases; an interrupted process resumes; switching accounts does not expose pending items; an attachment failure never silently discards the image; paginated triage enumerates all records; an invalid status/time does not disappear from the ledger; historical decisions survive later edits; parked items are never hidden by a new-only checkpoint; read state survives navigation/restart and does not clear on merely opening a panel.

Required communication tests: user sees only own history and reasons; admin sees every authorized case; a private reply targets the correct UID; unanswered/parked items remain visible to triage; a rejected/parked decision has an explanation; a mapped PR merge does not falsely claim delivery; verified release completion can be communicated; notification failure does not lose the case; no private content is sent to public repo/analytics/lock-screen preview by default; the future AI entry falls back to human help without pretending a model exists.

Physical Android acceptance must include a representative mix of supported versions/manufacturers and actual A-to-B update preservation. Exact test devices, minimum SDK policy, installation source and signing identity are revalidated in the implementation gate. No live user data or real notification test is permitted merely by accepting this document.

## 14. Open decisions and dependencies

1. Lars approves whether verification gates all core use or only central services. The contract recommends no interruption of already-working local dictation solely because identity/network is unavailable.
2. Choose exact Firebase project/environment, free-plan constraints, account-recovery/deletion policy and email-link delivery method. No provider project, billing, IAM or secrets are created by this document.
3. Validate the minimum Firestore schema/rules/index/query design and admin authentication mechanism through emulator tests before any live collection. Prefer client SDK + rules for ordinary reads/writes and a separate least-privilege admin workflow; no generic service-role HTTP endpoint.
4. Decide whether screenshots are needed in V1. Text-only is a valid first release. If included, private storage and a truthful non-loss offline path are mandatory. Firebase Storage's current plan requirements must be checked independently; no assumption that storage is available on Spark for new buckets.
5. Set explicit retention/deletion and privacy-notice requirements, including feedback containing personal data. Consult appropriate privacy/legal guidance before collecting more than necessary.
6. Prove the first real release-signing/update chain and existing-install migration before broad distribution. Existing debug-signed APKs may require a one-time migration; never promise compatible signatures without checking the installed artifact.
7. The AI support module remains design-only. Future secure inference, model cost cap, retrieval evaluation, escalation and telemetry require a separate approved Mission.

## 15. References

Legacy source: uploaded `FEEDBACK_FUNCTION_SPEC.md`, sections 1–11; source SHA above. Prior discovery notes: OPTIQON Voice PR #1 comments dated 2026-09-07 (installation/BankID/Help and identity/feedback). Current repo and branch are checked again at execution time.

Official technical references:
- https://firebase.google.com/docs/firestore/security/rules-conditions
- https://firebase.google.com/docs/firestore/security/rules-structure
- https://firebase.google.com/docs/firestore/manage-data/enable-offline
- https://firebase.google.com/docs/firestore/query-data/query-cursors
- https://firebase.google.com/docs/auth/android/account-linking
- https://firebase.google.com/docs/auth/limits
- https://firebase.google.com/docs/projects/billing/firebase-pricing-plans
- https://firebase.google.com/docs/app-distribution/set-up-alerts?platform=android
- https://developer.android.com/develop/ui/views/notifications/badges
- https://developer.android.com/develop/background-work/background-tasks/persistent
- https://support.bankid.com/sv/tekniska-fragor-och-problem/systemkrav
