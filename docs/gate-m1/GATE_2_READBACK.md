# Gate 2 readback — Authentication (G1), 2026-09-09

Console account: lars@optiqon.se · project: optiqon-voice-47498 (project number 699805184613)

Console steps 1–4 were clicked by Lars; every value below was read back afterwards, from the
console screenshots and from two read-only machine checks (the public `getProjectConfig`
probe and the Identity Toolkit admin config read with the CLI's own signed-in credentials —
no new credential was created).

| Item | Value seen |
|---|---|
| Authentication initialised | yes (Settings tab reachable; public probe no longer returns `CONFIGURATION_NOT_FOUND`) |
| Google provider | enabled; support e-mail: lars@optiqon.se; admin config: `google.com` enabled with client id + secret present (values not recorded) |
| Email/Password provider | enabled (`signIn.email.enabled = true`) |
| Email link (passwordless) | enabled (`signIn.email.passwordRequired` not set → link sign-in allowed) |
| Authorized domains | localhost, optiqon-voice-47498.firebaseapp.com, optiqon-voice-47498.web.app (nothing else; `localhost` is the console default) |
| Users | 0 |
| google-services.json check | fetched with `firebase apps:sdkconfig ANDROID 1:699805184613:android:35a5860f14504f1e130c8c`; `check-google-services.sh` exit 0, oauth_client.length = 2, web OAuth client (type 3) = 1 |
| default_web_client_id generated | yes (`:app:processDebugGoogleServices` → count 1) |

Previous `app/google-services.json` (0 OAuth clients) kept outside the repo as the G1 rollback
copy. No client ids, API keys or tokens are recorded here.

## G2 — first Hosting release, 2026-09-09

Command (repo root, HEAD `eeed194`, clean tree apart from this readback):
`npx --no-install firebase deploy --only hosting --project optiqon-voice-47498`
→ "found 2 files in public", "release complete", "Deploy complete!". Only the `hosting` target
appeared in the output; nothing about rules or Firestore.

| Check | Result |
|---|---|
| Hosting releases on `live` | exactly 1 (`hosting:channel:list`: live, 2026-09-09 13:29:47, never expires) |
| `/.well-known/assetlinks.json` (both domains) | 200, `Content-Type: application/json`, `Cache-Control: public, max-age=300`, content equals the repo file |
| `/signin` (both domains) | 301 → `/signin/` (query string preserved), `/signin/` → 200 with the static fallback page. Hosting's default directory-index redirect; the app never fetches this URL itself (the intent filter is on `/__/auth/links`), so no behaviour change. `g2-postflight.sh` now follows the redirect and asserts path+query are kept |
| `/__/auth/links` (both domains) | 200 |
| Digital Asset Links API | lists `se.optiqon.voice` for `optiqon-voice-47498.web.app` |
| Active Firestore ruleset before | `f5588727-03da-4205-8763-0c9891d959b1`, updateTime `2026-09-09T06:46:00.449842Z` |
| Active Firestore ruleset after | `f5588727-03da-4205-8763-0c9891d959b1`, updateTime `2026-09-09T06:46:00.449842Z` — unchanged |
| `g2-postflight.sh` | `G2 postflight: PASS` |

Ruleset read via the CLI's own `lib/gcp/rules.js` `listAllReleases` with the signed-in account
(lars@optiqon.se); firebase-tools 13.35.1 has no `firestore:rules:list` command.

Rollback for G2 remains `firebase hosting:disable --project optiqon-voice-47498`. Not used.

## Not done in this box

No install on the device, no bootstrap run, no Firestore write, no SHA registration, no custom
domain, no merge. G3 still needs D6 first.
