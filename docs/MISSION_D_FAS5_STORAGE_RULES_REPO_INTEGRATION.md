# Mission D Fas 5 — the verified Storage rules move into the ordinary repo path

**Date:** 2026-09-11 · **Scope:** repo/config only. No provider action of any kind was taken.

F-a proved the approved Cloud Storage ruleset works against the real Rules engine
(`docs/F_A_STORAGE_RULES_VERIFICATION_2026-09-11.md`). Until now that ruleset lived only on two
side branches under deliberately unreachable filenames. Fas 5 moves it onto the main track so
the contract has one home, one name, and one place to review changes against.

## What changed

| | |
|---|---|
| `storage.rules` | **new.** The F-a-verified contract block, byte-identical, under a new header. |
| `firebase.json` | **+3 lines.** One `"storage": { "rules": "storage.rules" }` block. Nothing else. |
| `docs/F_A_STORAGE_RULES_VERIFICATION_2026-09-11.md` | lifted verbatim from `verify/storage-rules-fa`. |
| `docs/BACKEND_OPEN_CONTRACTS.md` | lifted verbatim: the two-document-ceiling section. |

## The contract block is byte-identical, and that was checked, not assumed

`storage.rules` lines 34–101 are SHA-256 `8dd1654e…` — the same bytes as lines 21–88 of
`tests/storage-live/storage.verify.rules` on `verify/storage-rules-fa`, whose published ruleset
F-a read back as bit-identical, and the same bytes as lines 21–88 of `storage.future.rules` on
`spike/storage-rules-pr0`. The terminal `allPaths` deny is likewise carried over verbatim.

Two things about the file are *not* identical, both deliberate and both strictly more restrictive
or non-semantic:

1. **The header comment is rewritten.** It is comment text; it changes no rule. The new header
   states what F-a measured and why a third `firestore.get()` target would be a breaking change.
2. **The three PR-0 probe blocks are dropped.** `/probe-2doc-manycalls`, `/probe-3doc` and
   `/probe-5doc` were measurement scaffolding, explicitly "not part of the contract". They sit
   under their own top-level path prefixes, so removing them cannot affect
   `/case-attachments/**`; those paths now fall to the terminal deny. Strictly narrower.

## Why `firebase.json` now has a `"storage"` key — and what that costs

PR-0 and F-a both kept the key *out* of `firebase.json` on purpose: its absence meant no ordinary
`firebase deploy` could publish a Storage ruleset by accident. Fas 5 removes that guard, because a
rules file the ordinary tooling cannot see is a rules file nobody reviews. The residual risk is
named rather than hidden:

- **Today the guard is not load-bearing.** `optiqon-voice-47498` has no bucket,
  `firebasestorage.googleapis.com` is not enabled, and `billingEnabled` is `false`. A
  `firebase deploy` reaching this file cannot succeed.
- **From the moment a bucket exists, it is.** `firebase deploy` with no `--only` will then publish
  `storage.rules` along with everything else. The provisioning Gate must land its own deploy
  discipline (`--only` scoping, or a CI-side guard) in the same change that creates the bucket.

## Checks run

| Check | Result |
|---|---|
| `npm run test:rules` (Firestore rules + bootstrap, emulator) | **180/180 pass**, 0 fail |
| `storage.rules` compiles — Storage emulator loads it with no complaint | **clean** |
| Negative control: a deliberate syntax error appended to `storage.rules` | **reported** — `Unexpected 'this' in …\storage.rules:108`, so the check above can fail |
| Contract block SHA-256 vs both verified sources | **match** |
| `firebase.json` diff | **+3 lines, storage only**; `firestore`, `hosting`, `emulators` untouched |

Two notes on the compile check. It needs **JDK 21**: under the JDK 25 in Android Studio's bundled
`jbr`, the Cloud Storage rules-runtime jar dies on a `sun.misc.Unsafe` removal and the emulator
then accepts *any* file silently — the negative control caught this, which is the only reason it
is known. And `emulators:exec` exits **0** either way: the oracle is the log line, not the exit
code. Anyone wiring this into CI must assert on the output.

The Android unit tests were not run: this change touches no JVM source. CI runs them on the PR.

## What this deliberately does not do

No `firebase deploy`. No bucket. No Blaze or billing change. No enabling of
`firebasestorage.googleapis.com`. No IAM write. Production state is exactly as PRE-2 left it.

## Next Gate

Storage provisioning, in this order, each with its own readback:

1. Enable billing (Blaze) on `optiqon-voice-47498`, with a budget alert — **needs approval**.
2. Enable `firebasestorage.googleapis.com`; create the default bucket in `europe-north2` (or the
   nearest Storage-supported region) with uniform bucket-level access.
3. Grant `roles/firebaserules.firestoreServiceAgent` to
   `service-699805184613@gcp-sa-firebasestorage.iam.gserviceaccount.com` — **already done in
   PRE-2**, so this step is a readback, not a write.
4. `firebase deploy --only storage`, then read the published ruleset SHA-256 back and compare it
   against `storage.rules`.
5. Re-run the F-a contract probes against the real production project, or accept F-a's ephemeral
   result as sufficient. The two-document ceiling itself does not need re-measuring; what is
   unproven for production specifically is that the bucket, the rules release and the service
   agent are wired to each other.
