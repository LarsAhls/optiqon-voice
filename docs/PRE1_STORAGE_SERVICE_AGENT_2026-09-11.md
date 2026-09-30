# PRE-1 — creating the Firebase Storage service agent without a bucket and without Blaze

**Date:** 2026-09-11 · **Project:** `optiqon-voice-47498` (`699805184613`) · **Verdict: GREEN.**
`service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com` now exists and holds
`roles/firebasestorage.serviceAgent`. No bucket was created, no location was chosen, billing was
never touched, and `firebasestorage.googleapis.com` remains disabled.

The open question PRE-1 existed to answer — *does `firebasestorage.googleapis.com` declare a
service agent in the Workload Identity registry, and how many?* — is answered: **yes, exactly
one.** That could not be determined read-only; `generate` is the only way to query the registry
and `generate` is a write.

---

## 1. R0 — baseline before the first write

| | Read | Value |
|---|---|---|
| Active credential | `gcloud auth list` | `lars@optiqon.se` |
| Project / number / parent | `projects describe` | `optiqon-voice-47498` · `699805184613` · `248986435570` · `ACTIVE` |
| IAM baseline | `get-iam-policy --format=json \| sha256sum` | `4401e1cf5140dc0c3eb32176630c529c515cf9bfc8e66792d6d2e222293a0227` — **matches** F-a's isolation baseline |
| Existence probe | `iam service-accounts describe` | `NOT_FOUND: Unknown service account` — the agent did **not** exist |

The project number was read from Resource Manager, never assumed.

---

## 2. The three writes

| # | Write | Readback |
|---|---|---|
| **W1** | `services enable workloadidentity.googleapis.com` | operation `acat.p2-699805184613-82eb70a1…` finished successfully; `services list --enabled` returns `workloadidentity.googleapis.com`. Existence probe still `NOT_FOUND` — so W2 was required. |
| **W2** | `workload-identity service-agents generate --service=firebasestorage.googleapis.com --location=global --project=699805184613` | see §3 |
| **W3** | `add-iam-policy-binding` → `roles/firebasestorage.serviceAgent` on the `gcp-sa-firebasestorage` principal | see §4 |

---

## 3. Postflight P1–P4 after W2 — all green

**P1 — the complete `generate` response**, verbatim and in full:

```json
{
  "@type": "type.googleapis.com/google.cloud.workloadidentity.v1.GenerateServiceAgentsResponse",
  "serviceAgents": [
    {
      "container": "projects/699805184613",
      "principal": "serviceAccount:service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com",
      "role": "roles/firebasestorage.serviceAgent",
      "serviceProducer": "firebasestorage.googleapis.com",
      "state": "ACTIVE"
    }
  ]
}
```

| | Check | Result |
|---|---|---|
| **P2** | intended principal present in the response **and** existence probe answers `PERMISSION_DENIED` | **GREEN** — the probe flipped `NOT_FOUND` → `PERMISSION_DENIED: Permission 'iam.serviceAccounts.get' denied`, the same answer the three known-existing agents give |
| **P3** | the response's stated normal role is `roles/firebasestorage.serviceAgent` | **GREEN** — exact match |
| **P4** | inventory of every additional created principal | **GREEN — none.** `serviceAgents` has length 1 |

**P4 settles §1.4 of the plan empirically.** The Gate was approved on the broader footprint —
*all* agents the producer declares — because it could not be proven read-only that the producer
declares exactly one. It declares exactly one. The realised footprint is therefore a single
principal, but that is a measurement taken after the fact, not a guarantee that was available
before approval.

At this point the IAM policy was still byte-identical to the baseline (`4401e1cf…0227`) —
creating a service agent grants nothing by itself, which is exactly what the provider
documentation says about user-requested agents.

---

## 4. W3 — exactly one IAM delta

`diff prod-iam-before.json prod-iam-after.json` reports one added binding plus the etag. The
added binding:

```
role:   roles/firebasestorage.serviceAgent
member: serviceAccount:service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com
```

Bindings **7 → 8**. The complete flattened end state:

| Role | Member |
|---|---|
| `roles/owner` | `user:lars@optiqon.se` |
| `roles/firebase.sdkAdminServiceAgent` | `firebase-adminsdk-fbsvc@optiqon-voice-47498` |
| `roles/firebaseauth.admin` | `firebase-adminsdk-fbsvc@optiqon-voice-47498` |
| `roles/iam.serviceAccountTokenCreator` | `firebase-adminsdk-fbsvc@optiqon-voice-47498` |
| `roles/firebase.managementServiceAgent` | `service-699805184613@gcp-sa-firebase` |
| `roles/firebaserules.system` | `service-699805184613@firebase-rules` |
| `roles/firestore.serviceAgent` | `service-699805184613@gcp-sa-firestore` |
| **`roles/firebasestorage.serviceAgent`** | **`service-699805184613@gcp-sa-firebasestorage`** ← the delta |

No editor, no viewer, no additional owner. **DRS did not block it** — a second independent
confirmation of the F-a finding that `iam.allowedPolicyMemberDomains = ["C01qej65i"]` permits
Google-owned service agents, this time against production rather than an ephemeral project.

---

## 5. No other production effect

| Check | After PRE-1 |
|---|---|
| Buckets | `storage ls` → *matched no objects* — **zero** |
| Billing | `billingEnabled: false` — still Spark |
| `firebasestorage.googleapis.com` | **not enabled** |
| Storage rules | none deployed; `firebase.json` still has no `storage` block |
| Firestore / Auth / data | untouched |
| Organization IAM / org policies | untouched |
| Service-account keys | none created (and `iam.disableServiceAccountKeyCreation` is enforced) |

---

## 6. Reversibility — honest account

- **W1 and W3 are reversible.** `services disable workloadidentity.googleapis.com` and
  `remove-iam-policy-binding` with the same role and member. The IAM rollback mechanism was
  measured in F-a's CL4: the policy returns byte-identical except the etag.
- **W2 is not reversible.** Service agents are Google-owned and cannot be deleted. The identity
  is permanent in the project **even if the role from W3 is later removed** — removing the
  binding un-grants, it does not un-create. This was approved as such.
- The consequence of that permanence is nil: the identity is precisely the one Firebase would
  have created on its own at first bucket provisioning. PRE-1 anticipated a state the project was
  heading for rather than creating a new one.

---

## 7. What this does and does not enable

**Enables:** the separate Gate mission for `roles/firebaserules.firestoreServiceAgent` — the
principal it targets now exists, which was the blocker. That role contains exactly one
permission, `datastore.entities.get`.

**Does not enable anything else.** No bucket, no rules, no new access for the app, no
`firestore.get()` from Storage rules. Mission D Fas 5 still needs, each as its own Gate: the rule
file lifted from `spike/storage-rules-pr0` to `main`, a `storage` block in `firebase.json`, a
rules deploy, and a smoke test. And F-a's ceiling is unchanged in production — **a third
`firestore.get()` target is a breaking change**, see
[`BACKEND_OPEN_CONTRACTS.md`](BACKEND_OPEN_CONTRACTS.md).
