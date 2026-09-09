# Gate M1 — provider preflight (read-only), 2026-09-09

Every value below was read back from the provider with the local `firebase` CLI
(firebase-tools 13.35.1, logged in as `lars@optiqon.se`) or, where the CLI has no read
command, through the Firebase/Cloud Resource Manager REST APIs using that same existing
login. Nothing was written. No new credential was created.

## The new project

| Field | Value | Source |
| --- | --- | --- |
| Project ID | **`optiqon-voice-47498`** | `firebase projects:list` |
| Project number | **699805184613** | `firebase projects:list` |
| Display name | `optiqon-voice` | `firebase projects:list` |
| Parent | organization **248986435570** | `cloudresourcemanager.v1/projects` |
| Lifecycle | `ACTIVE` | same |
| Created | 2026-09-09T05:41:39Z | same |
| Labels | `firebase=enabled`, `firebase-core=disabled` | same |

**Correction to the intake.** The Gate brief said the project ID was `optiqon-voice`. It is
not. Firebase appended a suffix at creation: the ID is `optiqon-voice-47498` and only the
*display name* is `optiqon-voice`. This is load-bearing, not cosmetic — the App Link host is
`<project-id>.firebaseapp.com`, so the manifest filter must resolve to
`optiqon-voice-47498.firebaseapp.com`. Reading the host from `project_id` in
`google-services.json` via a `manifestPlaceholder`, as the plan already specifies, makes this
correct automatically; hardcoding `optiqon-voice` would have been silently wrong.

## The old project — untouched

| Display name | Project ID | Project number |
| --- | --- | --- |
| `Optioqon-Voice` | `optioqon-voice` | 642507220744 |

Read only. Not configured, not deployed to, not deleted.

## Android app

| Field | Value |
| --- | --- |
| App ID | `1:699805184613:android:35a5860f14504f1e130c8c` |
| Package name | **`se.optiqon.voice`** — matches the build |
| State | `ACTIVE` |
| Display name | not set |
| Registered SHA fingerprints | **none** (`No SHA certificate hashes found.`) |

Zero fingerprints is the expected state for a project created two hours ago, and it is why
`/.well-known/assetlinks.json` cannot yet name the debug key. SHA registration is still
outside the Gate box.

## Firestore

| Field | Value |
| --- | --- |
| Database | `projects/optiqon-voice-47498/databases/**(default)**` — the only one |
| Type | `FIRESTORE_NATIVE` |
| Location | `europe-north2` |
| Created | 2026-09-09T05:47:25Z |
| Delete protection | `DELETE_PROTECTION_DISABLED` |
| Point-in-time recovery | `POINT_IN_TIME_RECOVERY_DISABLED` |
| Version retention | 3600s |

**The named-database trap is cleared.** The database is `(default)`, which is exactly what
`firebase.json` targets when it carries no `firestore.database` key. A rules deploy will land
on the database the app actually uses.

Delete protection being off is worth naming: nothing in the Gate box deletes a database, but
the guard that would stop an accidental one is not armed. Enabling it is a write, so it is
not done here.

## Active ruleset — the rollback target

| Field | Value |
| --- | --- |
| Release | `projects/optiqon-voice-47498/releases/cloud.firestore` |
| Ruleset | `projects/optiqon-voice-47498/rulesets/**4937759b-d4a7-4e95-b59a-22d363dac881**` |
| Created | 2026-09-09T05:47:28Z — the only release that has ever existed |
| Content | 163 bytes, sha256 `ecf30f940747dcc3c5ba4993093e9a11ac9fc5df7e14b2a1512d2446923d84eb` |

Saved verbatim at [`rollback-ruleset-4937759b.rules`](rollback-ruleset-4937759b.rules). It is
the console's locked default — `allow read, write: if false` on `/{document=**}`. So the
rollback target denies everything: reverting to it is safe for data but takes the app fully
offline. There is no earlier, more permissive ruleset to fall back to instead.

The handoff said this value had to be copied out of the console by hand. It did not:
`firebase-tools`' own `lib/gcp/rules.js` exposes `listAllReleases` / `getRulesetContent`, and
they read through the existing login.

## Hosting

| Site ID | Default URL |
| --- | --- |
| `optiqon-voice-47498` | `https://optiqon-voice-47498.web.app` |

The auth-link domain is therefore `optiqon-voice-47498.firebaseapp.com`.

### assetlinks: 404 here too — and it narrows the cause

Both `https://optiqon-voice-47498.firebaseapp.com/.well-known/assetlinks.json` and the
`.web.app` equivalent return **HTTP 404 with Firebase's "Site Not Found" splash page**. The
parallel session found the same on the old project and left two possible causes open: no SHA
registered, or no Hosting release ever made.

This reproduction narrows it. "Site Not Found" is the page an *un-released* Hosting site
serves for every path — it is not a per-file 404. A project with a fingerprint registered but
no release would look identical, so the SHA cause is not excluded; but a project that was
auto-serving assetlinks would not show this page at all. So an initial Hosting release is
strongly indicated, and registering the SHA alone may well not be enough.

Not proven: only registering the SHA and retesting settles it. Both are writes, both outside
the box. Step 6 of the Gate box must therefore be prepared to need a Hosting release, which
is its own approval.

## Client config file — verified without exposing it

`C:\Users\ahlst\Projects\optiqon-voice\google-services (1).json`,
sha256 `7f0c26ec245cb48228d7954fcd72815fca098d180481df15b5be929f107ef9a5`.

Parsed locally; only identity fields were read out. Every one matches the provider readback:

- `project_id` = `optiqon-voice-47498`
- `project_number` = `699805184613`
- `mobilesdk_app_id` = `1:699805184613:android:35a5860f14504f1e130c8c`
- `package_name` = `se.optiqon.voice`
- `storage_bucket` = `optiqon-voice-47498.firebasestorage.app`
- 1 API key present (value not printed), 0 OAuth clients

It belongs to the right project and the right app. **Zero OAuth clients** is the thing to
notice: email-link sign-in does not need one, but Google Sign-In would, so nothing here
silently enables a provider.

### Open risk — the file is not gitignored where it sits

`.gitignore:43` ignores `app/google-services.json`. The downloaded file is at the *repo root*
under a different name, so `git status` lists it as untracked and a bare `git add .` would
commit an API key. It has not been moved, renamed or committed here: that is a write on a
file carrying a secret, and it is Lars's call. See the manual action below.

## Local repo state — reconciled, no drift

- Branch `claude/firebase-activation-gate-m1-iy96xc`, HEAD `207b95e`, clean apart from the
  untracked config file above.
- `git diff 78f366a HEAD` touches `docs/GATE_M1_HANDOFF.md` only. The reviewed rules work is
  byte-identical to what was reviewed.
- Checksum reconciliation: `sha256sum` on the working tree disagrees with the handoff's
  recorded values, because a Windows checkout stores CRLF. Hashing the git blobs reproduces
  the handoff exactly — `firestore.rules` `42f7fc81…101b`, `firestore.future.rules`
  `1c7c12cd…16ab8`. **No drift.** The discrepancy is line endings, not content.

## P4 — signing inventory: blocked, device not attached

- `adb` is present at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; `local.properties`
  points at that SDK. This *is* the right machine.
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` are unset in this shell — cosmetic, `local.properties`
  covers Gradle.
- `adb devices -l` → **no devices attached.** So `pm path se.optiqon.voice` cannot run and the
  installed build's signing certificate is still unknown.

P4 is the only Gate input that a session on this machine cannot supply on its own.
