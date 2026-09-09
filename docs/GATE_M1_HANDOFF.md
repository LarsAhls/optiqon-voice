# Gate M1 — handoff to a local session

Durable state for continuing the Firebase activation Gate. Not a plan; the plan is settled.
This exists because the cloud session that did the rules work cannot authenticate to Firebase,
and the remaining preflight is all provider readback.

## Where the work is

- Branch `claude/firebase-activation-gate-m1-iy96xc`, commit **`78f366a`**, branched from the
  Mission 1 head `1b8d0a5`. In sync with origin. **Do not restart Mission 1 and do not redo
  the rules work.**
- `firestore.rules` — the Mission 1 set, and the only file `firebase.json` names.
  sha256 `42f7fc81fcd2d69facc7ade25128f914f94860b8e3c47d66b608ba74e1fa101b`
- `firestore.future.rules` — the full F1 set, preserved, not deployed.
  sha256 `1c7c12cdff9b431adaf4ee06af8c1e1d6f81ebfd993f4cf51542ce12eb816ab8`
- `npm run test:rules` — **121 passing, 21 suites.** 19 of the 25 assertions in
  `revocation.test.mjs` fail against the pre-`78f366a` rules, which is what makes the guard
  load-bearing rather than decorative.

## Why this could not be finished in the cloud session

Established by running the commands, not by assumption:

- No Firebase MCP server is available in that session.
- Firebase APIs are reachable (`firebase.googleapis.com` and friends answer through the proxy),
  but `firebase login` refuses: *"Cannot run login in non-interactive mode."* There is no TTY.
- The CLI's own suggestion, `login:ci`, produces a long-lived token that would have to be
  pasted in, and would put a credential for Lars's Google account inside an ephemeral cloud
  container. That is a new credential, which the Gate boundary excludes, so it was not done.
- `dl.google.com` is egress-blocked (403 at the proxy), so no Android SDK and no Google Maven:
  the Android Gradle Plugin cannot resolve, which means the app build cannot even be
  *configured* there, let alone run. Any Gradle change made there would be unverifiable.

## Readback, 2026-09-09 — and why it changed the plan

Done from a local session authenticated as `lars@optiqon.se`. All four values came back.
Two of them changed what happens next.

| | |
|---|---|
| Project | `optioqon-voice`, number `642507220744`, GCP org `optiqon.se` |
| Firestore | **`(default)`** — the named-database trap does not apply. Native mode, `europe-north2` (Stockholm). Created 2026-09-07, **empty** |
| Active ruleset | Firestore's auto-generated **deny-all**. Nothing has ever been deployed |
| Android app | `1:642507220744:android:78fc7b80e32b84262baebd`, package `se.optiqon.voice`, **no SHA registered** |
| Hosting | default site `optioqon-voice` → `optioqon-voice.web.app`. assetlinks: **404 Site Not Found** |
| P4 | **Not done.** That machine had neither `ANDROID_HOME` nor a connected device |

**The project id carries a typo — `optioqon`, not `optiqon` — and a Firebase project id cannot
be renamed.** It matters more than a cosmetic slip because it becomes the email sign-in link's
domain: a login link pointing at `optioqon-voice.firebaseapp.com` reads as a misspelling of the
company's own name, which is the exact shape people are taught to distrust in sign-in mail. The
project name and public-facing name are spelt correctly; only the id, the one immutable part,
is wrong.

Decision: **a new project with the correct id.** Nothing is built on the old one yet — empty
database, no rules, no SHA, no client config — so this is the cheapest it will ever be. Once
the Gate runs, the id is baked into every installed client's `google-services.json`, and
changing it then means re-registering the app and every user.

**Correction to this document.** It previously said Hosting serves
`/.well-known/assetlinks.json` for the default domain automatically, so nothing needs
publishing by hand. That is not true of a project in this state: the path returns
**404 Site Not Found**. Two causes are possible and both have to be cleared —
no SHA is registered, so there are no fingerprints to generate the file from; and the Hosting
site may never have had a release, in which case the whole domain 404s. Which one it is can
only be settled by registering the SHA and testing again. If it still 404s, an initial Hosting
release is required, and that is a live action needing its own approval. Until it serves,
email-link sign-in cannot reach the app at all.

The same missing SHA also blocks **Google sign-in**: Credential Manager requires a registered
SHA-1.

## What is still missing

1. The new project: id, number, Firestore `(default)` in `europe-north2`, Android app
2. assetlinks status once a SHA is registered
3. P4: the signing certificate of the installed build

## Read-only readback

Every command below reads. None writes. Command names verified against firebase-tools 13.35.1.

```bash
firebase login                                     # real browser, no token pasting
firebase projects:list                             # → project ID and project number
firebase apps:list ANDROID --project <id>          # → app id; must be se.optiqon.voice
firebase apps:android:sha:list <appId>             # → already-registered fingerprints
firebase firestore:databases:list --project <id>   # → is it (default), or a named database?
firebase firestore:databases:get '(default)' --project <id>   # → region, Native vs Datastore
firebase hosting:sites:list --project <id>         # → the default site
```

The active ruleset has no CLI read command in 13.35.1 — take it from the console, Firestore →
Rules → history, and save it verbatim. It is the rollback target and nothing should be
deployed before it exists somewhere.

**Named-database trap.** `firebase.json` has no `firestore.database` key, so a deploy targets
`(default)`. If the existing database is a named one, the reviewed rules land on a database
nobody uses, **no error is raised**, and the app keeps running on whatever rules are actually
live. This is the only silent failure mode in the whole deploy. Check it before anything else.

## Client config

```bash
firebase apps:sdkconfig ANDROID <appId> --project <id> > app/google-services.json
```

Redirect it to the file. Do not print it into a conversation: it carries the project's API key.
It is gitignored and must stay that way.

## P4 — signing inventory, before any install

Needs a machine with `ANDROID_HOME` and the physical device. The repo already has the right
tool; reuse it rather than inventing a command, since it normalises colons and case:

```bash
adb shell pm path se.optiqon.voice
adb pull <path> /tmp/installed.apk
.github/scripts/verify-apk-signer.sh /tmp/installed.apk \
  23:4E:28:33:E3:E7:4D:72:1B:31:6C:0D:B5:E8:7E:11:2B:3F:74:ED:5F:76:D3:4E:5D:32:08:C5:04:42:9E:ED
```

That fingerprint is this repo's `debug.keystore` (`androiddebugkey`, `CN=Debug, O=Sasayaki`),
verified. Exit 0 means an in-place upgrade preserves the device's data. Any other result means
a different key: **do not uninstall.** `android:allowBackup="false"` means `adb backup` captures
nothing, so on a non-rooted device there is no supported way to save the profiles, keys and
history first. Use a separate device instead.

## The remaining code change

The App Link filter for the email sign-in link is **not** applied yet, deliberately.

Since Dynamic Links was retired, Firebase Auth builds the link on the project's default
Firebase Hosting domain — `<project-id>.firebaseapp.com` — not on the continue URL. The
manifest today only has a filter for `voice.optiqon.se/signin`, which is the continue URL and
never receives the link. So email-link sign-in cannot currently reach the app.

Firebase Hosting is *supposed* to serve `/.well-known/assetlinks.json` for that default domain,
built from the SHA-256 fingerprints registered on the project's Android app. On the old project
it returned 404 — see the correction above. Verify, never assume:

```bash
curl -s https://<project-id>.firebaseapp.com/.well-known/assetlinks.json
```

It must name `se.optiqon.voice` and the fingerprint of the build under test.

The fix is a second `<intent-filter>` on host `<project-id>.firebaseapp.com`, path prefix
`/__/auth/links`. The host should come from `project_id` in `google-services.json` via a
`manifestPlaceholder` rather than being hardcoded, so no checked-in project id becomes a guess
on another machine. `MainActivity.offerSignInLink()` needs no change — it reads `intent.data`
generically and validates with `auth.isSignInWithEmailLink()`.

The existing `voice.optiqon.se` filter carries `autoVerify="true"` and will report as
unverified, since no assetlinks file is published there. That is expected, not a fault to chase.

Build and run `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug` after making it —
that change was never compiled anywhere.

## Boundary

Still ungranted, and unchanged: rules deploy, provider changes, SHA registration, installation,
new credentials, data deletion, merge. Readback only until the Gate box is filled and approved.
