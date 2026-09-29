# FS-G provider configuration — intended, NOT deployed

Written in FS-S468. **Nothing in this file has been created, enabled or deployed.** Nothing in it
authorises creating, enabling or deploying anything either. It is the target that FS-G reviews,
approves and then executes through [FS_G_RUNBOOK.md](FS_G_RUNBOOK.md).

The machine-readable version is in `deploy/fs-g/`:

| File | What it is |
|---|---|
| `deploy/fs-g/targets.json` | Project, region, bucket, the Cloud Run service, triggers, scheduler job, service accounts and roles |
| `deploy/fs-g/firestore.indexes.json` | The composite indexes and field overrides the server's queries need |
| `deploy/fs-g/Dockerfile` | The service image: `server/` plus `firebase-admin` at the locked version, nothing else |

`tests/deploy/fs-g-config.test.mjs` keeps these files consistent with the code:

- same project, bucket and region;
- every trigger lands on a route that accepts its event type;
- the service env equals what `server/config.mjs` reads, and `APPLY=false`;
- every server query has its index;
- no broad role and no key material;
- `firebase.json` does not pick the indexes up by accident.

## Target

- **Project:** `optiqon-voice-47498`. The server refuses any other project (`server/config.mjs`),
  and so does the admin tool (`scripts/admin/voice-admin.mjs`).
- **Region:** `europe-north2`, the same region as Firestore `(default)`.
- **Bucket:** `gs://optiqon-voice-47498-eun2`.

## Runtime shape

One Cloud Run service, `voice-feedback-server`, runs `node server/http.mjs`. Its routes:

| Route | Caller | Event type | Entrypoint |
|---|---|---|---|
| `POST /events/withdrawal` | Eventarc | `google.cloud.firestore.document.v1.created` on `users/{uid}/withdrawals/{targetId}` | `onWithdrawalCreated` (S4) |
| `POST /events/attachment` | Eventarc | `google.cloud.firestore.document.v1.written` on `cases/{caseId}/attachments/{aid}` | `onAttachmentWritten` (M5 purge, primary path) |
| `POST /events/case-event` | Eventarc | `google.cloud.firestore.document.v1.created` on `cases/{caseId}/events/{eventId}` | `onCaseEventCreated` (S6 notification) |
| `POST /jobs/scheduled` | Cloud Scheduler, every 6 h | none | `scheduledRun`: withdrawal backstop, submitted sweep, purge backstop, retention |

**Only the subject is trusted.** An entrypoint reads only `ce-subject`, then re-reads the document
and decides from what it finds. The event body is never parsed, so a replayed, duplicated or
reordered event can do nothing that a fresh read would not.

**Retries are safe.** Every entrypoint is idempotent. A failure returns 500, so Eventarc retries it.

**Dry run by default.** The service starts with `APPLY=false`: it logs what it would do and writes
nothing. Setting `APPLY=true` is a separate step in the runbook, taken only after the negative
probes.

## APIs FS-G has to enable

These APIs may not be enabled yet; the runbook's preflight checks each one.

- Cloud Run (`run.googleapis.com`)
- Eventarc (`eventarc.googleapis.com`)
- Cloud Scheduler (`cloudscheduler.googleapis.com`)
- Artifact Registry (`artifactregistry.googleapis.com`), for the image
- Cloud Build (`cloudbuild.googleapis.com`), if the image is built there rather than locally
- Firebase Cloud Messaging (`fcm.googleapis.com`)
- Pub/Sub (`pubsub.googleapis.com`). Eventarc uses it for Firestore triggers.

Firestore and Cloud Storage are already enabled.

Enabling an API can create Google-managed service agents. FS-G records them as they appear.

## IAM — least privilege inventory

No service-account key is created, downloaded or stored anywhere. The service runs as its attached
identity. Administrators use their own `firebase login` / ADC credential, exactly as
`scripts/admin/m1-bootstrap.mjs` already does.

| Identity | Roles | Scope | Why |
|---|---|---|---|
| `voice-feedback-server@` | `roles/datastore.user` | project | Firestore read and write for reconcile, purge, retention and notify |
| | `roles/storage.objectAdmin` | **bucket `optiqon-voice-47498-eun2` only** | M5 purge and retention list and delete objects; it never reads bytes |
| | `roles/firebasecloudmessaging.admin` | project | S6 send |
| | `roles/logging.logWriter` | project | structured logs |
| `voice-feedback-trigger@` | `roles/eventarc.eventReceiver` | project | receive the Firestore events |
| | `roles/run.invoker` | service `voice-feedback-server` only | invoke the routes |
| `voice-feedback-scheduler@` | `roles/run.invoker` | service `voice-feedback-server` only | OIDC call to `/jobs/scheduled` |

**Never granted** to the server:

- `roles/firebaseauth.admin` — the server never touches Auth. Account deletion (M4) and support
  operations run through the admin tool under the administrator's own credential.
- `roles/owner`, `roles/editor`
- `roles/storage.admin`, or any project-wide storage role
- `roles/iam.serviceAccountKeyAdmin`

The service is deployed without unauthenticated access. `allUsers` and `allAuthenticatedUsers`
appear nowhere.

FS-G preflight must confirm whether the Pub/Sub service agent needs
`roles/iam.serviceAccountTokenCreator` to mint the push OIDC token. That depends on the project's
age and Eventarc's current behaviour. It is a Google-managed agent, not one of ours; if it needs
the role, FS-G grants exactly that, and records it.

## Indexes

The emulator needs no indexes; the provider does. `deploy/fs-g/firestore.indexes.json` holds:

**Composite indexes, for `sweepSubmitted`:**
- `cases`, collection scope, on `(state, createdAt)`
- `events`, collection group, on `(state, createdAt)`

**Field overrides, collection group, for the backstop scans:**
- `attachments.deleteRequestedAt` — `sweepPurges`
- `notificationTokens.token` — the token-binding check before each send

An override replaces the automatic single-field indexes for that field, so each override also
restates the collection-scope ascending and descending indexes.

**Automatic indexes, no entry needed:** `cases.ownerUid ==` (account deletion);
`state == 'accepted'` ordered by `__name__` (the retention scan); `attachments.messageId ==`
(withdrawal); `withdrawals.caseId` and `notificationSends.caseId` (retention).

`firebase.json` deliberately does not reference this file. A test keeps it that way, so no plain
`firebase deploy` can publish indexes before FS-G decides to.

## Environment

| Variable | Value | Read by |
|---|---|---|
| `PROJECT_ID` | `optiqon-voice-47498` | `loadConfig`; any other value is refused |
| `BUCKET` | `optiqon-voice-47498-eun2` | `loadConfig`; any other value is refused |
| `SWEEP_TTL_DAYS` | `30` | the submitted sweep; must be at least 30 |
| `APPLY` | `false` at deploy | writes only when exactly `true` |

Nothing secret goes in the environment. FCM authenticates as the attached identity.

## Scheduler region

Cloud Scheduler is planned in `europe-north2`. If FS-G preflight finds that Cloud Scheduler is not
offered there, the documented candidate is `europe-north1`.

In that case the job only holds its schedule and makes an authenticated call to the
`europe-north2` service. No Feedback data is stored or processed in `europe-north1`.

**Choosing `europe-north1` is an FS-G decision and is not made here.**

The schedule is every 6 h (`0 */6 * * *`, Europe/Stockholm). That keeps the M5 promise — physical
deletion within 24 h of a tombstone — even when the event-driven purge was lost: the backstop
catches it on its next run.

## Cost

This is not a new cost model. Everything is scale-to-zero or per-invocation:

- Cloud Run with `minInstances: 0` and `maxInstances: 2`
- three Eventarc triggers
- one Scheduler job
- FCM, which is free

FS-G confirms the billing state before enabling anything.

## What the repository proves, and what it does not

**Proven in the repository:**
- routing and the event-type check, and the listener (`tests/server/http.test.mjs`)
- every entrypoint's refusal of a foreign project
- the consistency tests above

**Not proven, until FS-G:**
- that Eventarc delivers Firestore events in `europe-north2` with the headers `server/http.mjs`
  reads
- that the IAM set above is sufficient, and that it is minimal on the live project
- real latency, and the races under it
- FCM delivery
- the image build
- Scheduler availability in the region
