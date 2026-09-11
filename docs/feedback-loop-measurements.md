# What the feedback loop actually costs

Measured on this machine (Windows 11, `jbr-21.0.11`) in a worktree created from `main` at
`151f018`, against the shared Gradle caches. Every number below was taken by running the thing,
not estimated. They are here because the design decisions in M-V1 rest on them, and because a
decision whose evidence is not written down gets re-litigated from intuition six months later.

## The build

| What | Wall clock | What Gradle reported |
|---|---|---|
| `:app:compileDebugKotlin`, first ever run in a new worktree | **2 m 41 s** | `compileDebugKotlin` FROM-CACHE, `kspDebugKotlin` FROM-CACHE; 3 executed, 14 from cache |
| `:app:compileDebugKotlin`, warm, nothing changed | **15 s** | 17 actionable tasks: 17 up-to-date |
| `:app:compileDebugKotlin`, warm, one comment added to one file | **4 m 30 s** | 2 executed (KSP + Kotlin), 15 up-to-date |
| `:app:testDebugUnitTest`, compilation all from cache | **17 m 25 s** wall | tests 458, failures 0, ignored 2; the report's own duration is 5 m 29.61 s |

Two of these decided the design.

**The 15 s is the floor, and it buys nothing.** That is Gradle starting, configuring, reusing the
configuration cache and concluding it had nothing to do. Any gate that shells out to `gradlew`
spends a quarter of a minute before it has checked anything at all.

**The 4 m 30 s is the case a commit gate actually meets.** A commit has, by definition, changed a
source file, and changing one file re-runs KSP across Hilt and Room. A gate at that price is not a
gate; it is a thing people learn to pass `--no-verify` to. This is stronger than the plan assumed:
it rules `compileDebugKotlin` out of the commit gate, and it also undermines the idea of using it
as the agent's inner loop, because the inner loop is exactly the changed-file case.

**A brand-new worktree was served `compileDebugKotlin` FROM-CACHE.** Worth stating plainly: a
fresh checkout is not a fresh build. This is the same mechanism as the two false-green incidents
this repository has already recorded, reproduced live, and it is why CI now refuses a test result
that did not execute.

## The gate

| What | Wall clock |
|---|---|
| `sh scripts/verify-fast.sh` on a staged set | **1.4 s** |
| `sh scripts/verify-fast.sh --all`, every tracked file, Windows git-bash | **1 m 37 s** |
| A real `git commit`, hook included, end to end | **2.0 s** |

The gate that runs in front of every commit costs two seconds. `--all` is a diagnostic mode and a
CI job, not something a human waits for; most of that 1 m 37 s is process spawn overhead on
Windows, which is why it belongs on a Linux runner.

## What this ruled out

- Gradle in the commit gate, in any form. Not a preference: 15 s minimum, 4 m 30 s realistically.
- Duplicating CI locally. CI already runs on every push to every branch, on someone else's
  hardware.
- The test-count floor as the primary answer to false green. A cached run reports the right count,
  so it would have caught neither recorded incident. It is kept as a secondary guard against a
  different fault -- a test class dropped by a filter or a rename.
