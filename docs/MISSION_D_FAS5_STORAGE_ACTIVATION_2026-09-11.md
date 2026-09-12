# Mission D Fas 5 — Storage Activation: the repository half

Evidence for the repository part of the Storage activation. The provider part — Blaze, the budget
alert, the default bucket in `europe-north2`, and the rules deploy itself — is separately approved
and has **not** been carried out. This document records what was built, what was proved, one
provider fact that changed outside the approved box, and the recovery decision taken afterwards.

Date: 2026-09-11, revised 2026-09-12. Branch: `feat/storage-rules-repo-integration`.
Project: `optiqon-voice-47498`.

---

## 1. What is in the repository now

| File | What it is |
|---|---|
| `scripts/deploy/storage-guard.mjs` | predeploy hook on the **storage target only** |
| `tests/deploy/storage-guard.test.mjs` | the guard refuses and permits; exit code and message pinned |
| `tests/deploy/predeploy-scope.test.mjs` | which targets the hook is attached to, behind a network trap |
| `storage.denyall.rules` | emergency containment ruleset, 450 bytes |
| `firebase.json` | `predeploy` on `storage`; `firestore` and `hosting` untouched |
| `app/build.gradle.kts` | in CI the test task is neither cacheable nor up to date |
| `.github/workflows/test.yml` | the guard suite runs in the existing `firestore-rules` job |
| `.gitattributes` | `storage.rules` and `storage.denyall.rules` pinned to LF |
| `package.json` | `npm run test:deploy-guard` |

The only permitted production deploy form from here on:

    OPTIQON_ALLOW_DEPLOY=storage firebase deploy --only storage --project optiqon-voice-47498

## 2. Checksums

`storage.rules` was not edited. An edit would void the F-a measurement it carries.

| Artifact | Bytes | sha256 |
|---|---|---|
| `storage.rules` | 5045 | `d814cd7e5477ad156344a99285007bac86aeb78c33f78fb83f6c50e1f1f844e9` |
| `storage.rules` lines 34-101 (the F-a contract block) | — | `8dd1654ed4f3ab53eab8dfb62d6b86ced4d7279ff542e9e2c242ec24fe9cfb2a` |
| `storage.denyall.rules` | 450 | `63860e692c5a55bb648ab62bc8d87891e9b230e9cfafe791ad2ca9dd081c4878` |

Both rules files are LF in the index **and** in the working tree, and `.gitattributes` now keeps
them that way. This is not tidiness. The firebase CLI uploads working-tree bytes, so on a clone
with `core.autocrlf` on — the Windows default, and the setting on this machine — the same commit
would otherwise publish a CRLF ruleset whose checksum does not match the one above, and the deploy
evidence would be comparing two different artifacts. `firestore.rules` is deliberately excluded: it
is already live as CRLF (12108 bytes) and renormalising it would silently change what the next
Firestore deploy publishes.

## 3. How this half is verified: no deploy command path at all

**No `firebase deploy` invocation — with or without `--dry-run` — is part of verifying this
repository.** The reason is in §5: `--dry-run` was assumed provider-free, and that assumption was
falsified against production. The CLI offers no mode that runs the predeploy hook and then stops,
so there is no safe variant of that proof to fall back to. Everything below runs locally.

To keep that a property of the code rather than of whoever reads it next,
`tests/deploy/predeploy-scope.test.mjs` arms a **network trap** before firebase-tools is loaded:
`net.Socket.prototype.connect`, `net.connect`, `net.createConnection`, `tls.connect`,
`http.request`/`get`, `https.request`/`get`, `dns.lookup`, `dns.promises.lookup` and `fetch` all
throw. A test asserts the trap is armed, so a node release that made one of those properties
read-only would fail the suite rather than leave a silently disarmed trap behind.

`npm run test:deploy-guard` — **11 tests, 11 pass, no network.**

**The guard itself** (`storage-guard.test.mjs`). Each case spawns the guard as a real child process
with a rebuilt environment, because what a predeploy hook actually delivers is an exit code. The
child is the guard script, which reads two environment variables and exits; it has no network path
of its own.

| Environment | Exit | Message asserted |
|---|---|---|
| no `OPTIQON_ALLOW_DEPLOY` | 2 | names the variable, and prints the one permitted command |
| `OPTIQON_ALLOW_DEPLOY=firestore` | 2 | names the variable |
| `OPTIQON_ALLOW_DEPLOY=storage`, `GCLOUD_PROJECT=optioqon-voice` | 2 | names the wrong project |
| `OPTIQON_ALLOW_DEPLOY=storage`, `GCLOUD_PROJECT=optiqon-voice-47498` | **0** | `storage-guard: ok` |

The last row is not a formality. A guard that only ever refuses is indistinguishable from a broken
build script, and it gets deleted the first time it is in the way. The third row matters because
the misspelled older project `optioqon-voice` still exists and is still reachable with these
credentials.

**Its scope** (`predeploy-scope.test.mjs`), against firebase-tools' own `filterTargets` and
`VALID_DEPLOY_TARGETS`, with `OPTIQON_ALLOW_DEPLOY` deleted for the whole file so inherited shell
state cannot decide the result:

| `--only` | targets selected | storage hook in the chain? |
|---|---|---|
| *(absent)* | `firestore, hosting, storage` | yes — an unscoped deploy meets the guard |
| `storage` | `storage` | yes |
| `firestore` | `firestore` | **no** |
| `hosting` | `hosting` | **no** |

Plus: `firebase.json` attaches `predeploy` to `storage` and to neither of the other two, and the
storage hook — driven directly through `lifecycleHooks` — rejects with `storage predeploy error`
when the allow variable is absent. That rejection is what aborts a deploy before its first release.

`filterTargets` is the right seam, and finding that out took a wrong turn worth recording.
`lifecycleHooks('storage', 'predeploy')` is **not** scope-aware on its own: its internal
`getReleventConfigs` returns a single unnamed config whatever `--only` says, because the filter it
applies (`!config.target`) is about Hosting's *named deploy targets*, not about which product was
selected. Asserting there produced a confident green for a claim that was false — the first version
of this test "proved" that `--only firestore` runs the storage hook. What actually decides whether
the hook is ever added to the chain is the target list computed upstream in the deploy command, so
that is where the assertions live now.

## 4. Why the guard exits 2

firebase-tools runs predeploy commands through `cross-env-shell`, which spawns via cross-spawn
(7.0.6) with `shell: true`. In that mode cross-spawn never resolves a command file, so its
`verifyENOENT` heuristic — `status === 1 && !parsed.file` — treats **any** exit status of 1 as
"the command did not exist" and raises a spurious `ENOENT` whose stack trace buries the guard's own
message. The first version of the guard exited 1: the deploy aborted, so it looked correct, but the
operator was told the wrong thing about why. The heuristic fires only on status 1. Exit 2 aborts
just as hard and the message survives, and `storage-guard.test.mjs` asserts the exact code and the
exact message text so this is not tidied back to 1 later.

## 5. The incident: a provider write during the repo-only box

**What was approved at the time was the repository part B plus commits 1–2, and explicitly no
provider write.** The provider write below happened anyway, during B5, while that was the standing
scope. It is recorded here as a scope breach, not as a step completed.

The fourth of the planned B5 CLI proofs was the one believed to be zero-risk: the hook passes, the
deploy then fails on the missing bucket, and that failure is itself evidence that provider state is
untouched. It did not hold:

    OPTIQON_ALLOW_DEPLOY=storage firebase deploy --only storage --dry-run --project optiqon-voice-47498
      +  storage: Finished running predeploy script.
      i  storage: ensuring required API firebasestorage.googleapis.com is enabled...
      !  storage: missing required API firebasestorage.googleapis.com. Enabling now...
      +  storage: required API firebasestorage.googleapis.com is enabled
      Error: Firebase Storage has not been set up on project 'optiqon-voice-47498'.

`--dry-run` skips deploy and release. It does **not** skip prepare, and prepare enables missing APIs
for real. Three earlier proofs in the same series were harmless only because the guard refused
before prepare began; the moment the guard let a run through, prepare was already executing. There
is no line between those two cases that a reviewer can see from the command alone, which is why
§3 removes the whole command path rather than the one invocation.

Read-back immediately afterwards, to bound it:

| Fact | Value | Changed? |
|---|---|---|
| `firebasestorage.googleapis.com` | **enabled** | **yes** |
| buckets | 0 | no |
| `billingEnabled` | `false`, account `''` | no |
| IAM bindings | 9, etag `BwZbN7j091g=` | no |
| PRE-1 `roles/firebasestorage.serviceAgent` | present | no |
| PRE-2 `roles/firebaserules.firestoreServiceAgent` | present | no |

Exactly one provider fact moved. No bucket, no billing and no IAM side effect occurred. The zero
IAM delta is also the first empirical confirmation of what was predicted: PRE-1 and PRE-2 had
already created the service agent and both its role bindings without a bucket and without Blaze, so
enabling the API provisions nothing new.

## 6. Recovery decision (2026-09-12)

Recorded because the state of the project now differs from what the plan's preflight asserts, and
a later session reading only the plan would try to re-run a step that is already done.

- **No rollback.** `firebasestorage.googleapis.com` stays enabled. Disabling it would itself be a
  further provider write, and the enabled API has no cost and no attack surface without a bucket.
- **Plan step E1 is complete.** It is accepted as performed early. When the Gate sequence reaches
  the provider part, **E1 becomes a readback, not an enable** — confirm the API is on, do not run
  `services enable` again.
- **The new verified baseline**, replacing the preflight row for this fact: API enabled, buckets 0,
  `billingEnabled false`, IAM exactly 9 bindings at etag `BwZbN7j091g=`, PRE-1 and PRE-2 intact.
  A preflight that finds `firebasestorage` already enabled is now the expected result and no longer
  a stop condition; every other row of that table still is.
- **No further provider write** until the Gate sequence reaches the provider part under its own
  approval. Blaze, the budget, the bucket and the rules deploy remain untouched and unapproved.

## 7. What this does not prove

- **`storage.rules` is not tested anywhere.** There is no Storage rules suite in this repository,
  and the green `firestore-rules` job says nothing about this file. The Storage emulator was
  deliberately not adopted for it: F-a established that the emulator has no per-evaluation document
  ceiling at all, so it cannot serve as budget evidence.
- **The scope proofs are proofs about firebase-tools' code, not about a deploy.** They show which
  targets are selected and that the hook refuses when selected. They do not show a real deploy being
  stopped, because demonstrating that safely is not possible with this CLI. The production deploy in
  §1 will be the first time the guard is exercised end to end, and it is expected to pass it.
- **The guard is not a security boundary.** It stops an unscoped deploy, a Storage deploy with no
  explicit allow, and a deploy aimed at the wrong project. It does not stop an operator who exports
  `OPTIQON_ALLOW_DEPLOY=storage` permanently, and it does not stop direct API calls past the CLI.
- **Firestore and Hosting gained no protection.** That is the design, not an oversight: a correctly
  scoped `firebase deploy --only firestore` is exactly as possible as it was before.
- **Nothing about the bucket.** No bucket exists. Location, uniform bucket-level access, public
  access prevention, billing and the rules deploy are all still ahead, and the bucket's location is
  immutable once chosen.
