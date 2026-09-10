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
