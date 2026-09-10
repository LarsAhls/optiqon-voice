# G1 + G2 runbook — Authentication and the first Hosting release

Gate boxes from plan rev. 4 (§Mission A). **Nothing here has been run.** This file is the
checklist Lars executes when the box is explicitly opened ("kör G1+G2"); Claude prepared the
inputs in Mission L1 and verifies the readback afterwards. Both boxes touch only the new
project **optiqon-voice-47498**; the old project `optioqon-voice` is never opened.

Account for every console step: **lars@optiqon.se** (project owner).
Console: [Authentication](https://console.firebase.google.com/project/optiqon-voice-47498/authentication) ·
[Hosting](https://console.firebase.google.com/project/optiqon-voice-47498/hosting) ·
[Firestore rules](https://console.firebase.google.com/project/optiqon-voice-47498/firestore/rules)

## Preconditions (Claude verifies before the box opens)

- [ ] Branch with Mission L1 merged or checked out locally (the app code that shows Google
      as primary and e-mail link as secondary; the `public/` hosting files; `firebase.json`
      hosting block). Check: `git log --oneline -1` on the branch and
      `ls public/.well-known/assetlinks.json public/signin/index.html`.
- [ ] `public/.well-known/assetlinks.json` lists the debug certificate SHA-256
      `234E2833E3E74D721B316C0DB5E87E112B3F74ED5F76D34E5D3208C504429EED` (the key of the
      installed build). The beta key is added in G4, not here.
- [ ] D4 confirmed: continue-URL is the default Hosting domain
      `https://optiqon-voice-47498.web.app/signin`; no work on `voice.optiqon.se`.
- [ ] Active Firestore ruleset id noted **before** the box (console > Firestore > Rules >
      history, or `npx --no-install firebase firestore:rules:list --project optiqon-voice-47498`).
      It must be the same id after G2.
- [ ] `firebase login` on this machine is lars@optiqon.se:
      `npx --no-install firebase login:list`.

## G1 — Authentication (console, lars@optiqon.se)

1. **Get started** on the Authentication page (initialises Identity Platform config; today it
   returns `CONFIGURATION_NOT_FOUND`).
2. **Sign-in method → Google → Enable.** Support e-mail: lars@optiqon.se. Save. This creates
   the web OAuth client that the Android app needs (`default_web_client_id`).
3. **Sign-in method → Email/Password → Enable**, and inside it **Email link (passwordless
   sign-in) → Enable.** Save.
4. **Settings → Authorized domains:** confirm `optiqon-voice-47498.firebaseapp.com` and
   `optiqon-voice-47498.web.app` are present (they are added by default). Do **not** add
   `voice.optiqon.se` (D4).
5. **Project settings → Your apps → se.optiqon.voice → download `google-services.json`.**
   Replace `app/google-services.json` (git-ignored, never committed). Then run:

   ```bash
   bash scripts/gate/check-google-services.sh app/google-services.json optiqon-voice-47498
   ```

   Expected: every line `OK`, in particular `oauth_client.length >= 1` and a web OAuth client
   (client_type 3). Then confirm the build now generates the id:

   ```bash
   ./gradlew :app:processDebugGoogleServices --quiet && grep -c default_web_client_id app/build/generated/res/processDebugGoogleServices/values/values.xml
   ```

   Expected: `1`. If the count is `0`, stop: the OAuth client is missing from the file.
6. **Readback → `docs/gate-m1/GATE_2_READBACK.md`** (template below). The readback is
   taken from the console pages, not from a script: there is no `gcloud` on this machine and
   no long-lived token may be created for it. Record values, not screenshots.

**Rollback G1:** disable the providers again (0 users exist). The downloaded
`google-services.json` can be discarded; the previous file had 0 OAuth clients and is
equivalent to "not configured".

### GATE_2_READBACK.md template

```markdown
# Gate 2 readback — Authentication (G1), <date>

Console account: lars@optiqon.se · project: optiqon-voice-47498

| Item | Value seen in console |
|---|---|
| Authentication initialised | yes / no |
| Google provider | enabled / disabled; support e-mail: … |
| Email/Password provider | enabled / disabled |
| Email link (passwordless) | enabled / disabled |
| Authorized domains | optiqon-voice-47498.firebaseapp.com, optiqon-voice-47498.web.app, (nothing else) |
| Users | 0 |
| google-services.json check | `check-google-services.sh` exit 0, oauth_client.length = … |
| default_web_client_id generated | yes (count 1) |

No client ids, API keys or tokens are recorded here.
```

## G2 — first Hosting release (CLI, lars@optiqon.se)

Deploys **only** `public/` (assetlinks + the static `/signin` fallback page). Never
`--only firestore`, never a bare `firebase deploy`.

```bash
npx --no-install firebase deploy --only hosting --project optiqon-voice-47498
```

Postflight (read-only GETs, run from the repo root):

```bash
bash scripts/gate/g2-postflight.sh optiqon-voice-47498
```

Expected `G2 postflight: PASS`, i.e. for both `…web.app` and `…firebaseapp.com`:
`/.well-known/assetlinks.json` → 200, `content-type: application/json`, content equal to the
repo file; `/signin` → 200; `/__/auth/links` reachable; the Digital Asset Links API lists
`se.optiqon.voice` for the site.

Then the two manual checks the script only reminds about:

- [ ] Hosting console shows exactly **1** release on channel `live`.
- [ ] Active Firestore ruleset id is **unchanged** from the precondition note.

**Rollback G2:** there is no earlier Hosting release to roll back to. The fallback is
`npx --no-install firebase hosting:disable --project optiqon-voice-47498` (site returns to
"Site Not Found"; the `/__/auth/*` endpoints are unaffected) or a redeploy of a corrected
`public/`. Nothing in Firestore or Authentication is touched by either.

## What G1+G2 do not do

- No installation on the phone, no bootstrap run, no Firestore write. Those are G3. D6 is
  settled (72 h, decided 2026-09-09); the offline grace is the code constant `BETA_GRACE_MS`
  in `AccessGate.kt`, and a different value would be a one-constant edit before the G3 build.
- No SHA registration beyond what already exists (debug SHAs registered earlier); the beta
  key SHA is a G4 step.
- No custom domain.
