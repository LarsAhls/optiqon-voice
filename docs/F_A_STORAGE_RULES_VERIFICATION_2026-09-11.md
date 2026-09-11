# F-a — live verification of cross-service Cloud Storage Security Rules

**Date:** 2026-09-11 · **Verdict: YES.** The approved Storage rule can read exactly two
distinct Firestore documents per evaluation, and repeated `firestore.get()` calls against
those same two documents are free. A rule that reaches a third distinct document is refused.

This is the question MISSION E0 / PR-0 could not answer. The Storage **emulator** enforces no
document ceiling at all — it allowed 3 *and* 5 distinct documents — so the emulator cannot be
used to prove or disprove the contract. F-a settles it against the real service.

**Consequence:** the Firebase Storage architecture survives. Mission D Fas 5 may be built on
it, and F-b (the read-proxy fallback) is not needed.

---

## 1. Environment

| | |
|---|---|
| Verification project | `optiqon-rules-verify-20260911` (ephemeral, deleted the same day) |
| Project number | `458085169078` |
| Parent | `organizations/248986435570` (`optiqon.se`) |
| Billing | `billingAccounts/013F74-E27522-6117FC` (SEK), Blaze, budget alert 50 SEK |
| Firestore | `(default)`, `nam5`, Native mode |
| Storage bucket | `optiqon-rules-verify-20260911.firebasestorage.app`, `US-CENTRAL1`, uniform access |
| Identities | five synthetic `@verify.invalid` accounts (RFC 2606 reserved TLD — can never belong to a real person) |
| Image bytes | generated in memory (PNG header + zeros); nothing read from disk |
| CLI | node v24.15.0 · firebase-tools 13.35.1 · Google Cloud SDK 584.0.0 |

**Production was never written to.** `optiqon-voice-47498` was read exactly twice, read-only,
to prove isolation. Its billing remained `billingEnabled: false` throughout.

---

## 2. IAM — exactly one binding

One `add-iam-policy-binding` on the verification project:

```
role:   roles/firebaserules.firestoreServiceAgent
member: serviceAccount:service-458085169078@gcp-sa-firebasestorage.iam.gserviceaccount.com
```

`diff iam-before.json iam-after.json` — the complete delta:

```
> "serviceAccount:service-458085169078@gcp-sa-firebasestorage.iam.gserviceaccount.com"
> "role": "roles/firebaserules.firestoreServiceAgent"
  (plus the expected etag change)
```

Role bindings 9 → 10, members 9 → 10. No other change.

**Domain Restricted Sharing did not block it.** This was the single largest open risk in the
approved box: `constraints/iam.allowedPolicyMemberDomains` is set to `allowedValues:
["C01qej65i"]` at the organization, and the principal above is a Google-owned service agent
outside that customer. A *user-initiated* `setIamPolicy` for such a principal was the untested
case. It is now tested: **DRS permits it.** That is a reusable finding for the production path
— Mission D does not need an org-policy exemption to wire cross-service Storage rules.

No service-account key was created, read or downloaded. No ADC was written; the harness
authenticates from the existing `firebase login` refresh token held in memory only. The
organization policy `iam.disableServiceAccountKeyCreation` (enforced) makes the alternative
impossible at platform level.

**Secrets grep.** `grep -rniE '(refresh_token|private_key|BEGIN [A-Z ]*PRIVATE KEY|AIza[0-9A-Za-z_-]{20,})'`
over `tests/storage-live` and `docs/` returns two hits, both in
`tests/storage-live/verify.test.mjs` (lines 97 and 126) and both **property names**, not values:
`store?.tokens?.refresh_token` reads the developer's own `firebase login` session at runtime, and
`refresh_token: cred.refreshToken` passes it straight into the in-memory credential. No token,
key or API key literal exists anywhere in the repo or in this report.

---

## 3. Rules deployed

```
firebase deploy --only storage \
  --config tests/storage-live/firebase.verify.json \
  --project optiqon-rules-verify-20260911 --non-interactive
```

No interactive permission prompt appeared. `--config` resolved
`"storage.rules": "storage.verify.rules"` relative to `tests/storage-live/`, as designed.

**Readback:** release `firebase.storage/optiqon-rules-verify-20260911.firebasestorage.app`,
updated `2026-09-11T15:03:08Z`. Published ruleset content SHA-256
`313fb6704689cf0d77622239087fdf7de4440d65985879ed6cc7dcc4b7cce4d9` — **bit-identical** to
`tests/storage-live/storage.verify.rules`.

The contract block in that file is `storage.future.rules` lines 21–88 verbatim; both preflight
diffs were empty. **The security contract was not weakened to make the measurement pass.**

---

## 4. Results — 19/19, three runs per probe

### Method control

A measurement that cannot produce a DENY proves nothing. Both controls held:

| | Outcome | Expected |
|---|---|---|
| C1 approved owner uploads against a valid reservation | **PASS** | PASS |
| C8 stranger reads the owner's attachment | **DENY** | DENY |

### Contract probes

| Probe | Outcome | | Probe | Outcome |
|---|---|---|---|---|
| C2 approved owner reads own attachment | PASS | | C11 `adminActive` missing entirely | DENY |
| C3 revoked owner uploads on an old reservation | DENY | | C12 live admin whose account is revoked | DENY |
| C4 revoked owner reads existing attachment | DENY | | C13a claim present, mirror `false` | DENY |
| C5r / C5u pending owner read / upload | DENY | | C13b mirror present, claim absent | DENY |
| C6 tombstoned attachment read | DENY | | C14a wrong MIME | DENY |
| C7 tombstoned attachment upload retry | DENY | | C14b over 2 MiB | DENY |
| C9 live admin reads owner attachment | PASS | | C14c over `att().maxBytes` | DENY |
| C10 stale admin (`adminActiveUntil` passed) | DENY | | C15 overwrite existing object | DENY |
| | | | C16 client delete | DENY |

Every outcome matched its expectation. C11 and C13b are the ones that matter most for the
production design: an incomplete admin mirror **fails closed**, because `liveAdmin()` is
written in the positive form `me().adminActive == true`, so a missing field raises an
evaluation error rather than silently passing.

### The decisive probes

| Probe | Distinct Firestore documents | `firestore.get()` calls | Run 1 | Run 2 | Run 3 |
|---|---|---|---|---|---|
| **A** | 2 | 8 | **PASS** | **PASS** | **PASS** |
| **B** | 3 | 3 | **DENY** | **DENY** | **DENY** |
| **B′** | 5 | 5 | **DENY** | **DENY** | **DENY** |

Fully deterministic across all three runs.

Probe A is the load-bearing result: **eight** `firestore.get()` calls spread over only **two**
documents are allowed. This confirms the documented caching behaviour — *"You can however do
multiple function calls on the same document. These do not count towards the limit, as they
will be cached."* The ceiling counts distinct documents, not calls.

Probe B′ is read as `adminLive`, not as the owner, on purpose: the rule reads both
`users/{ownerUid}` and `users/{request.auth.uid}`, and an owner-as-reader would collapse those
into one document and measure four, not five.

### Resource use — far inside every cap

`storageOps=29/500` · `firestoreWrites=22` · `signIns=5` · 8 objects, all well under 20 MiB ·
wall clock 45 s. Expected cost 0 SEK; Cloud Billing reports lag by several hours, so the cost
figure is reported as *expected*, not as a verified reading — the resource counters above are
the verified evidence, and they are two orders of magnitude below anything billable.

---

## 5. What this does and does not prove

**Proves:** the real Cloud Storage Rules service enforces a ceiling of two distinct Firestore
documents per evaluation; repeated reads of those documents are free; the approved contract
fits inside that ceiling; and every deny in the contract denies for the right reason.

**Does not prove:** anything about the Storage *emulator*, which enforces no ceiling and must
not be used to validate document budgets. PR-0 (Draft PR #18) stands as the record of that.
Nor does it exercise production data paths — the measurement ran entirely on synthetic
identities in a project that no longer exists.

---

## 6. Cleanup and isolation

Executed in order, each with its own readback. CL1-CL5 are strictly redundant once CL6 runs,
but they were run anyway: each one proves that its individual rollback mechanism works, which
is exactly what Mission D section 12/13 needs for the production path.

| | Step | Readback |
|---|---|---|
| CL1 | Delete all test objects | `storage ls -r` -> *matched no objects* |
| CL2 | Delete Firestore test data | `listCollections()` -> **0** root collections |
| CL3 | Delete Auth test users | 5 `@verify.invalid` users deleted -> **0** remaining |
| CL4 | **Remove the IAM binding** | role absent; policy byte-identical to `iam-before.json` except the etag |
| CL5 | Unlink billing | `billingEnabled: false` |
| CL6 | Delete the project | soft-delete accepted |
| CL7 | **Authoritative readback** | Resource Manager -> **`DELETE_REQUESTED`** |
| CL8 | Isolation proof | all three baseline hashes identical (below) |

**CL4 is the important one.** It is the only place where the IAM rollback is actually tested,
and the post-rollback policy differs from the pre-flight policy by nothing but the etag. The
grant is therefore reversible without residue - a reusable result for the production path.

`gcloud projects delete` gives a 30-day soft delete. That is reported as a window, not as
erasure: the project is inactive and does not bill, and can be restored during that period.
The readback claims no more than `DELETE_REQUESTED`. The secondary advisory check
(`firebase projects:list`) also no longer lists the project, but it is advisory only - a stale
Firebase listing would not have made cleanup fail.

### Isolation proof

All three read back identical to the pre-flight baselines, using the same method
(`gcloud ... --format=json | sha256sum`):

| Baseline | SHA-256 | |
|---|---|---|
| `optiqon-voice-47498` IAM policy | `4401e1cf5140dc0c3eb32176630c529c515cf9bfc8e66792d6d2e222293a0227` | MATCH |
| `organizations/248986435570` IAM policy | `ce1ff4088998b1ca255c07fdcf2f5e406fb3c63bce078c0d002adf4b4c374011` | MATCH |
| Organization policies | `58a5ad2f72b460704e894b6ba0ad6b7a86db5c2cc7e6e4aeda1b0b022e9b4bd7` | MATCH |

Production billing remained `billingEnabled: false` throughout. F-a never issued a write
against production or against the organization.
