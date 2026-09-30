# PRE-2 — binding `roles/firebaserules.firestoreServiceAgent` in production

**Date:** 2026-09-11 · **Project:** `optiqon-voice-47498` (`699805184613`) · **Verdict: GREEN.**
The Cloud Storage Rules engine's identity now holds the one role it needs to read Firestore from
`storage.rules`. Exactly one IAM binding was added. No bucket, no billing change, no rules deploy,
no API enablement, no merge.

This is still only a prerequisite for Mission D Fas 5. It activates nothing on its own.

---

## 1. What the role is

| | |
|---|---|
| Role | `roles/firebaserules.firestoreServiceAgent` (GA) |
| Permissions | **exactly one** — `datastore.entities.get` |
| Description | *Grants Firebase Security Rules access to Firestore for providing cross-service Rules.* |
| Member | `serviceAccount:service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com` |

Read with `gcloud iam roles describe`, not assumed. The member is the principal PRE-1 created;
the same role/principal shape was proven to work end to end in F-a, on a throwaway project.

---

## 2. Preflight — read-only, all green

`gcloud` 584.0.0. **Not on PATH** in either Git Bash or PowerShell — it must be invoked as
`C:\Program Files (x86)\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd`.

| # | Check | Result |
|---|---|---|
| F1 | Active credential | `lars@optiqon.se` (`ahlstedt.lars@gmail.com` is present but inactive) |
| F2–F5 | Project / number / parent / lifecycle | `optiqon-voice-47498` · `699805184613` · `organizations/248986435570` · `ACTIVE` — the number read from Resource Manager, never assumed |
| F6 | Service agent exists | `iam service-accounts describe` → `PERMISSION_DENIED` on unique id `103998023732438560561` = **exists** (the documented existence probe; `NOT_FOUND` would mean absent) |
| F7 | PRE-1 binding intact | `roles/firebasestorage.serviceAgent` present on the target principal |
| F8 | IAM baseline | 8 bindings, **identical to PRE-1 §4's end state** — no unexplained drift |
| F9 | **Target binding already present?** | **No.** Filtered `get-iam-policy` returned empty → not a no-op |
| F10 | DRS | `constraints/iam.allowedPolicyMemberDomains` effective = `["C01qej65i"]`, unchanged |
| F11 | `workloadidentity.googleapis.com` | **ENABLED** — residual provider state from PRE-1's W1. Left untouched by this mission |
| F12 | `firebasestorage.googleapis.com` | not enabled |
| F13 | Billing | `billingEnabled: false` (Spark) |
| F14 | Buckets | zero |

### Baselines

```
prod-iam full      sha256 903b32fdfa85f17799ca7779f7fad8aa0b8385c391f778eb7176ecca6c851157
prod-iam bindings  sha256 4c384a3b1370313521a032bc06a4cc1cd4e1718b2861abe05b1708290a6cc08c
etag                      BwZbN3lcaEU=
```

The **bindings-only** hash is what governs the rollback proof — it excludes the etag, which
changes on every write and can never be restored. Re-read immediately before the write: still
`4c384a3b…c08c`. **No drift between approval and execution.**

---

## 3. The write — exactly one

```
gcloud projects add-iam-policy-binding optiqon-voice-47498 \
  --member="serviceAccount:service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com" \
  --role="roles/firebaserules.firestoreServiceAgent" \
  --condition=None \
  --format=json
```

`--condition=None` is load-bearing, not cosmetic: without it `gcloud` can prompt interactively
about conditions, and an unanswered prompt in a non-interactive run is an undefined outcome. The
flag writes an explicitly **unconditional** binding, deterministically — the same form used in F-a
and PRE-1's W3, and the form the rollback command must match to be unambiguous.

`set-iam-policy` from a local file was deliberately **not** used: it would overwrite any binding
added between read and write. `add-iam-policy-binding` does read-modify-write internally and
carries the etag, so a concurrent change fails loudly with `ABORTED` instead of silently clobbering.

**Execution note:** the command was run manually by Lars. The agent's own attempts were refused by
the session's permission classifier in both shells, before any request reached Google — see §6.

---

## 4. Readback P1–P6 — all green

| # | Check | Result |
|---|---|---|
| **P1** | `diff` full policy, pre → post | **exactly one** added binding plus the etag line. Nothing else |
| **P2** | Binding count | **8 → 9** |
| **P3** | Members on the target role | exactly one, the intended principal |
| **P4** | All eight pre-existing bindings | **intact, member for member** — verified as a flattened `(role, member)` set: 8 pairs → 9 pairs, one added, **zero removed**, `pre ⊆ post` true |
| **P5** | Side effects | `firebasestorage.googleapis.com` still not enabled · `billingEnabled: false` · zero buckets · no `storage` block in `firebase.json`, no rules deployed |
| **P6** | `workloadidentity.googleapis.com` | still `ENABLED`, unchanged — this mission did not touch it |

The full-policy diff, verbatim:

```
> "serviceAccount:service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com"
> "role": "roles/firebaserules.firestoreServiceAgent"
  (plus the expected etag change)
```

```
etag     BwZbN3lcaEU=  →  BwZbN7j091g=
bindings 4c384a3b…c08c →  15dd35da65fcf92f30827daefe4941b63b6ffdceb9f423c262b409c5878160a6
```

Every binding was also asserted to carry **no** `condition` key, so nothing conditional entered
the policy by accident. The target principal now holds precisely two roles:
`roles/firebasestorage.serviceAgent` (PRE-1) and `roles/firebaserules.firestoreServiceAgent` (this
mission).

**No rollback was triggered.**

---

## 5. Rollback — available, unused

```
gcloud projects remove-iam-policy-binding optiqon-voice-47498 \
  --member="serviceAccount:service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com" \
  --role="roles/firebaserules.firestoreServiceAgent" \
  --condition=None
```

Proof condition: `get-iam-policy --format="json(bindings)"` must hash back to
**`4c384a3b…c08c`**. The full policy may differ **only** in the etag.

Unlike PRE-1's W2 — which created a permanent, undeletable Google-owned identity — **this write is
fully reversible.** Removing the binding un-grants the permission and leaves nothing behind.

---

## 6. Process deviation, recorded

**PRE-1 was executed in Auto Mode although its approved routing was Plan Mode + A4.** No rollback
was demanded, because the provider evidence showed the writes stayed inside the approved box — but
the deviation is recorded here so it is not repeated.

PRE-2 was run to the approved routing: planned in Plan Mode, no write attempted before explicit
approval. The agent's two attempts to execute the approved command were refused by the session's
permission classifier (Bash and PowerShell alike). **No request reached Google on either attempt**,
which the pre-write drift check confirms — the bindings hash was still `4c384a3b…c08c` afterwards.
Lars ran the command manually instead, and this document records the readback of that run.

---

## 7. What remains for Mission D Fas 5

Each of the following is still its own Gate:

1. Lift the rules file from `spike/storage-rules-pr0` onto `main`
2. Add a `storage` block to `firebase.json`
3. Deploy the rules
4. Smoke test

And F-a's ceiling stands unchanged in production: cross-service Storage rules tolerate **exactly
two distinct Firestore documents per evaluation**. **A third `firestore.get()` target is a breaking
change** that must be measured live again — the emulator has no ceiling at all and cannot be used
for this budget. See [`BACKEND_OPEN_CONTRACTS.md`](BACKEND_OPEN_CONTRACTS.md) and
[`F_A_STORAGE_RULES_VERIFICATION_2026-09-11.md`](F_A_STORAGE_RULES_VERIFICATION_2026-09-11.md)
(branch `verify/storage-rules-fa`, Draft PR #20).
