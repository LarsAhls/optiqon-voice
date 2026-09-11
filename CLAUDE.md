# OPTIQON Voice — working notes

Short, repo-specific rules. Anything not listed here follows the normal conventions
visible in the surrounding code.

## Two checkouts exist — only one is live

- **Live:** `C:\Users\ahlst\Projects\optiqon-voice` — this repo, tracks `origin/main`.
- **Stale:** `C:\Users\ahlst\StudioProjects\optiqon-voice` — an older checkout that
  still sits on the initial import and has uncommitted changes.

Always confirm the working directory before editing or running git. Do not delete or
rename the stale checkout; reconciling it is a separate, explicitly approved action.

## Documentation is ignored by default

`.gitignore` ignores `*.md` so that scratch notes and handoff drafts dropped in the
repo root never get committed by accident. Versioned documentation is exempted
explicitly: `README.md`, `CLAUDE.md`, `BACKLOG.md`, everything under `docs/`, and
`fine-tuning/README.md`.

New documentation belongs under `docs/`. A new markdown file elsewhere is invisible to
`git status` — if a file you just wrote does not show up, this is why. Use
`git check-ignore -v <path>` to confirm before reaching for `git add -f`.

## No secrets in the repo

Keystores, `release-signing.properties`, `.env*` and `google-services.json` are all
ignored and must stay that way. `google-services.json` is downloaded per machine from
the Firebase console; release signing material is supplied through
`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
`RELEASE_KEY_PASSWORD` as gradle properties or environment variables. When all four are
absent, `release` builds fall back to the committed debug key — see
`app/build.gradle.kts`. Changing the signing identity is a separate approved action.

## Verifying your work

These are the rules a session has to know before it can trust anything it runs. They used to
live in `.claude/HANDOFF.md`, which is gitignored, and in a plan file outside the repo — so a
fresh session reading only the repository could not see them.

### Running Gradle at all

- `export JAVA_HOME='C:\Users\ahlst\.jdks\jbr-21.0.11'` as its **own** command, single quotes.
- `sh gradlew`, **never** `gradlew.bat`.
- `local.properties` (`sdk.dir=…`) and `app/google-services.json` are per-machine and gitignored.
  A fresh checkout or a new worktree has neither, and the build fails on the first with
  "SDK location not found" and silently drops Firebase configuration on the second.

### What a green run is worth

- The baseline is **458 tests, 0 failures, 2 skipped**. The two skips are
  `PostProcessingBenchmark` and `StyleControlDifferentialTest`, which `assumeTrue` themselves
  away unless `OPENAI_ENDPOINT` and a key are in the environment. Two skips is the correct,
  offline result; zero skips means a run reached a paid provider.
- Read the result from `app/build/reports/tests/testDebugUnitTest/index.html`. A `--tests`
  filtered run **deletes** `app/build/test-results/testDebugUnitTest/` — the filtered run
  replaces the directory rather than adding to it, so a full-suite result is destroyed by the
  next narrow one.
- `MigrationChainTest`, `DowngradeGuardTest` and `ProfileKindMigrationTest` must be green in the
  **same** run. Separately green proves less than it looks: they constrain each other.
- No mocks. `mockk` and `turbine` are absent and are meant to stay absent; write a fake.
- Verify CI on the **merge commit**, not on the branch head. `main` is protected — branch and PR,
  always.

### A green run that never ran

This has happened here twice, and both times the suite reported success:

- Gradle served the test task `FROM-CACHE`/`UP-TO-DATE` after a change the task did not declare
  as an input. `app/build.gradle.kts` now declares the screenshot baselines, the keep rules, the
  reflection contract, the build script itself, the main sources *as data*, and the manifest and
  `res/xml` — each one because something was once reported green without running.
- Roborazzi recorded new baselines instead of comparing against the committed ones, so the
  screenshot tests could not fail and did not, while two layout defects shipped.

So: a guard that reads a file as data needs that file declared as a task input, or it is not a
guard. If you add one, prove it — break the input on purpose, watch the run go red, restore it.

`scripts/verify-fast.sh` is the fast, deterministic subset; `git commit` runs it through
`.githooks/pre-commit`. It is not a substitute for the suite — see the header of that script for
what it does and does not cover.

The hook is **opt-in per clone**, because git cannot version `core.hooksPath`. Run
`sh scripts/install-hooks.sh` once after cloning; every worktree of that clone is then covered,
including ones created later. Check it with `git config --get core.hooksPath` — empty means you
are committing with no gate at all. CI runs the same script over every tracked file, so a clone
that skipped this is caught at push rather than never.

Never `--no-verify`, and never anything equivalent. If the gate is wrong, fix the gate.

### What a run does not prove

Say it out loud in the report, every time. Concretely:

- The JVM suite runs against Robolectric's SQLite, not Android's, and against a plain
  `Application` with no AndroidKeyStore. Room migrations and key persistence are *not* proven by
  a green suite.
- Unit tests run un-shrunk. Nothing they assert depends on R8 having kept anything.
- The manifest guard proves the declarations are intact, not that the platform honours them.

### Where a Mission is allowed to close

Acceptance is per fault class, not per device:

- **The emulator is valid final acceptance** for Room against real Android SQLite, permissions,
  lifecycle and process recreation, ordinary UI and navigation, and API-level compatibility.
  AVDs `l1_api28`, `l1_api32` and `l1_api36` cover minSdk through current.
- **A physical phone is required** only where hardware, the OEM shell or another app is part of
  the evidence: OEM background killing, overlay behaviour in the manufacturer's shell, real
  Accessibility injection into other apps, and real ASR from the microphone.

Do not impose a device gate on a fault class that does not need one, and do not close one that
does with an emulator.

### Paid providers

No automatic path may reach one. The benchmark reads its endpoint and key from the environment
and skips itself when they are absent; CI sets neither; the rules suite runs against a local
emulator under a project id that does not exist. The one remaining exposure is a developer shell
that exports `OPENAI_ENDPOINT` and `OPENAI_API_KEY` — `tasks.withType<Test>` forwards them, so a
plain `testDebugUnitTest` in that shell makes real calls. Do not export them for ordinary work.
