# OPTIQON Voice — Identity Reference

This file is the durable source of truth for this app's identity. It intentionally does not
duplicate the full rebrand analysis — only what needs to survive beyond the rebrand session.

## Product identity

- **Product name:** OPTIQON Voice
- **Android `applicationId` / `namespace`:** `se.optiqon.voice`
- **Kotlin package root:** `se.optiqon.voice`
- **GitHub repository:** `LarsAhls/optiqon-voice` — a standalone repository (not a fork), the
  successor to the working repo `LarsAhls/sasayaki-Lars` where this rebrand was developed
- **Release/APK filename prefix:** `optiqon-voice` (e.g. `optiqon-voice-v20260904.apk`)

## License and provenance — do not erase

OPTIQON Voice **remains GPLv3 / open source** when distributed. This is a deliberate, standing
decision, not a default:

- The repository root carries a canonical `LICENSE` file (GPLv3, from gnu.org/licenses/gpl-3.0.txt).
- `README.md` carries a short, correct upstream-attribution note: OPTIQON Voice is a rebrand of a
  fork of [pluja/sasayaki](https://github.com/pluja/sasayaki), licensed under GPLv3.
- The original upstream author (`pluja`) and project name (`sasayaki`) **may only** appear where
  legitimate and intentional:
  - the LICENSE file itself
  - copyright/license headers
  - the attribution/provenance note in README (or equivalent legal/docs files)
  - an explicit historical note describing the fork lineage
- Everywhere else — active product branding, install links, release config, runtime identifiers,
  build tooling, notification text, in-app strings — must use OPTIQON Voice identity only.

Do not "clean up" the allowlisted references above in a future pass; they are required by GPLv3
attribution norms and by an explicit decision from the app owner (Lars), not accidental leftovers.

## Release signing — plumbing only, no key material here

- The repository will never contain a production signing key. `debug.keystore` (checked into the
  repo, password `android`, key alias `androiddebugkey`) is for local/debug builds only and must
  never sign a release that is distributed.
- The production release signing key is generated and held by Lars (never by an agent), added to
  this repository's GitHub Actions secrets, and consumed by the release workflow from there.
- The Gradle/CI plumbing *reads* signing config from environment variables / Gradle properties
  with safe absence-handling (falls back to the debug signing config when release secrets are
  absent, never silently mislabels a build as signed-release when it isn't). That local/CI debug
  fallback is acceptable for ordinary build verification, but it is structurally unreachable from
  a **public** release: `release.yml` refuses to build or publish anything unless all five
  `RELEASE_*` secrets are present.
- Both CI workflows that can produce a release-signed APK
  (`.github/workflows/build-signed-apk.yml`, manual smoke build, and
  `.github/workflows/release.yml`, the tag-triggered public release) share the same secret
  contract — `RELEASE_STORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`, `RELEASE_CERT_SHA256` — and fail with a clear `::error::` before any
  build step if any of them are missing. `release.yml` in particular cannot build or publish
  anything without all five present, so a tag pushed before these secrets are configured fails
  the workflow rather than publishing a debug-signed APK as an OPTIQON Voice release.
- Signer identity is verified cryptographically, not by string-matching a keystore alias: both
  workflows call the shared `.github/scripts/verify-apk-signer.sh`, which extracts the built
  APK's signer certificate SHA-256 fingerprint via `apksigner verify --print-certs` and requires
  an exact match against `RELEASE_CERT_SHA256`. A mismatch is a hard failure. `apksigner` itself
  is resolved deterministically (highest installed build-tools version by version-sort), not via
  an unordered wildcard. `RELEASE_CERT_SHA256` is provisioned once the real release key exists and
  its fingerprint has been read locally (never generated or seen by an agent).

## Room schema history — preserved, not discarded

`app/schemas/com.sasayaki.data.db.SasayakiDatabase/` was renamed (not deleted and regenerated) to
`app/schemas/se.optiqon.voice.data.db.OptiqonVoiceDatabase/`, keeping all seven historical version
files (`1.json`–`7.json`) byte-for-byte unchanged — only the containing directory (which Room names
after the database class's fully-qualified name) moved. The JSON content has no class-name-derived
internals, so nothing inside the files needed to change, and preserving them keeps Room's
`exportSchema` migration-history validation intact across the rename. This is a deliberate choice,
not an oversight — do not regenerate or prune this history in a future pass.

## Allowed legacy-name locations (allowlist)

At the end of this rebrand, `Sasayaki` / `sasayaki` / `com.sasayaki` / `pluja/sasayaki` may only
remain in:

1. `LICENSE` (GPLv3 boilerplate does not itself name the project, so this is mostly N/A, but any
   copyright line referencing the upstream project is allowed here)
2. The README attribution/provenance note
3. This file (`docs/OPTIQON_VOICE_IDENTITY.md`), as history/explanation
4. Git history (commits, tags) — history is never rewritten as part of a rebrand

No other file, string resource, identifier, build config, CI workflow, or doc should reference the
legacy name after this mission's PR merges.
