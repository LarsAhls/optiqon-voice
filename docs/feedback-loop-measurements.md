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

## The freshness proof

`ManifestContractTest` reads `AndroidManifest.xml` as *text*. Compilation inputs do not cover
that, which is how this build produced a green run that never ran, twice. So the manifest and
`res/xml` are now declared inputs of the test task — and the declaration was proven rather than
assumed, by running the same filtered command four times:

| # | Manifest | Gradle reported | Result | Wall clock |
|---|---|---|---|---|
| 1 | intact | `> Task :app:testDebugUnitTest` | pass | 14 m 50 s |
| 2 | untouched | `UP-TO-DATE` | pass | 6 s |
| 3 | `VIBRATE` removed | `> Task :app:testDebugUnitTest` | **FAILED**, on the permission-set assertion | 56 s |
| 4 | restored | `FROM-CACHE` | pass | 7 s |

Run 3 is the one that matters twice over: the task re-ran *because* the manifest is now an input,
and the guard went red on a real defect. Without run 3, neither claim would be evidence. Run 1 is
slow only because a new test file invalidates the compiled test source set; run 2 is what the
steady state costs.

Run 4 is worth staring at. Restoring the manifest returned the task to an input set Gradle had
already seen, and it handed back a cached *result* — a correct cache hit, and simultaneously a
live demonstration of the shape of both recorded incidents. It is exactly the outcome CI now
refuses.

**The caveat that follows from run 4 -- and what measuring it actually showed.** The prediction
was that re-running the CI job on an unchanged commit would produce `FROM-CACHE` and be refused,
even though that cached result is legitimate. Re-running job `unit-tests` on this PR falsified it:
the task executed again and the job passed. The reason is in that run's own log --
`Cache is read-only: will not save state for use in subsequent builds` -- because
`gradle/actions/setup-gradle` writes the cache only from the default branch. A branch build reads
a cache it can never contribute to, so no entry for its own test task exists to be served back.

The refusal can therefore only bite on `main`. It stays fail-closed there on purpose: the check
cannot distinguish a cache hit whose inputs were complete from one whose inputs were not, and the
second kind is what bit this repository twice. If it fires, push an empty commit rather than
relaxing the check. Stated as a measurement, not as a prediction, because the prediction was
wrong.

## Android Lint

| What | Wall clock | Result |
|---|---|---|
| `:app:lintDebug`, first run, writing the baseline | **10 m 12 s** | 25 errors, 114 warnings |

Lint had been configured and never invoked since the project started, so this is the first time
anyone has seen that number. 139 findings is not a crisis and it is not something to fix inside
this Mission either; it is why the CI job starts report-only against `app/lint-baseline.xml`.
The baseline silences exactly today's findings and nothing else, so anything new shows up.

Ten minutes is also why it is its own parallel job rather than another step in the test job: it
costs nothing in wall clock when it runs beside a suite that takes longer.
