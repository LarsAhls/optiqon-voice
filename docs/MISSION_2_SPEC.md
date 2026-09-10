# Mission 2 — typed profiles and a capability registry

**Status:** **approved by Lars 2026-09-10**, including `NOTES` = `SummarizeMode.LIGHT` and the
irreversibility of the Room 8→9 column. Being built; no further stops.
**Written:** 2026-09-10, against head `6a67184`.

This exists because Mission 2 had no spec anywhere in the repo. Building it from the name alone
would have meant inventing the mission. Read it as a yes/no: once approved, the whole of it is
built in one pass without further stops.

## Why

`SystemPromptBuilder.inferStyleForApp` (`:138-156`) guesses the tone of the text from the
foreground app's package name and label. It is the only tone logic in the app, and it guesses
wrong in exactly the cases a dictation user hits:

- mail written in a browser or a webview — no "mail" in the package name, so no professional tone
- chat in a webview or a less common client — falls through to no hint at all
- notes taken inside a chat app — gets the casual chat hint

The guess is invisible and unfixable from the UI: there is no way for the user to say what they
are writing. Mission 2 lets the profile declare it, and demotes the package guess to a fallback.

The same mission fixes a real defect that has the same root — a decision about what the app can
do, written out in several places with different answers. See **The profile card defect** below.

## What is built

### 1. `ProfileKind` — a declared kind per profile

```kotlin
enum class ProfileKind { GENERAL, EMAIL, CHAT, NOTES, SOCIAL, VERBATIM }
```

Added to `Profile` (`domain/model/CoreModels.kt:6-21`) as
`val profileKind: ProfileKind = ProfileKind.GENERAL`.

**A flat enum, not a sealed hierarchy.** Every variant would carry identical fields, and
`ProfilesScreen` rests on `Profile.copy()` in a dozen places — a sealed type would turn each of
those into a `when`. The name is `ProfileKind`, not `ProfileType`, to avoid reading as a sibling
of `ProfileSupport.ProfileVerdict`, which is about Android's work profile and unrelated.

**Six values, not five.** `inferStyleForApp` holds **four** tone strings today — mail, chat,
docs/notes and social — and each gets a declared home (`EMAIL`, `CHAT`, `NOTES`, `SOCIAL`) so the
fallback `when` can keep its branches verbatim. `GENERAL` means "keep guessing from the app", the
current behaviour. `VERBATIM` means "no tone hint at all" and is the one kind that adds a
capability answer rather than a string.

### 2. `ProfileKinds` — the preset registry

New file `domain/model/ProfileKinds.kt`, in the house idiom — `object X { val ALL: List<T> }`
plus a `byId()`-style lookup, as in `ProviderPresets` (`domain/provider/ProviderPreset.kt:27-72`)
and `BuiltInPrompts` (`domain/processing/BuiltInPrompts.kt:16-48`).

Each entry carries a label, the tone string for that kind, and preset values for the existing
mode fields. **The four tone strings move verbatim** out of `inferStyleForApp` — not reworded,
so the emitted prompt text for a given tone is unchanged.

**No DI multibinding.** `@IntoSet`/`@IntoMap` appear nowhere in this codebase and this mission
does not introduce them.

### 3. `ProfileCapabilities` — four named things, each with a reason when it can't

New file `domain/capability/ProfileCapabilities.kt`:

- `POST_PROCESSING` — the LLM pass runs at all
- `REPLACEMENT_RULES` — the profile's replacement rules apply
- `STYLE_CONTROLS` — style/rewrite/summarize settings reach the model
- `TONE_HINT` — a tone line is emitted

`evaluate` is a **pure** function of `Profile` + a `CapabilityEnvironment` value object (the
configured `llmBaseUrl` and `llmApiKey`, blank or not). No `Context`, no `suspend`, no DataStore,
no Android types. That is deliberate and load-bearing: it is what lets Mission 2 be tested with
the libraries already present and **add no new test dependency** (`mockk`, `turbine` and
`hilt-android-testing` are all absent today and stay absent).

Each capability resolves to available, or unavailable **with a reason** — the reason is what the
UI prints, which is how the card stops lying.

## Hard upgrade invariant

`GENERAL` must produce a **byte-identical** system prompt to today's build, for every combination
of the other profile fields. It is the default for every migrated row, so no existing tester's
output changes on upgrade. Asserted directly in `ProfileKindPromptTest`, not argued.

**No heuristic reclassification.** A profile named "Mail" does not become `EMAIL` by itself.
Every existing row migrates to `GENERAL` and stays there until the user says otherwise.

## Persistence — Room 8 → 9

One column, following `migration5To6` (`di/DatabaseModule.kt:99-104`) exactly:

```kotlin
private val migration8To9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE `profiles` ADD COLUMN `profileKind` TEXT NOT NULL DEFAULT 'GENERAL'")
    }
}
```

- `OptiqonVoiceDatabase.version` 8 → 9 (`data/db/OptiqonVoiceDatabase.kt:27`).
- Appended to `ALL_MIGRATIONS` (`DatabaseModule.kt:172-180`).
- `app/schemas/9.json` is **generated by the build** (`room { schemaDirectory(...) }`,
  `app/build.gradle.kts:182-184`) and committed. Never hand-written: a wrong `identityHash`
  produces an error that reads like a migration bug and costs a cycle to trace.
- Stored as a String via the existing `enumValueOrDefault` (`data/db/entity/ProfileEntity.kt:66-68`),
  so an unknown name from a future build degrades to `GENERAL` instead of throwing. That is the
  forward-compatibility mechanism this mission relies on; no new one is added.
- **No `fallbackToDestructiveMigration` in either direction**, unchanged.

### `DowngradeGuardTest` must be re-based in the same commit

This is the single most likely cause of a mid-mission stop, so it is named here rather than
discovered. `app/src/test/java/se/optiqon/voice/data/db/DowngradeGuardTest.kt` is built on version
8 being the newest version that exists:

| Site | Today | Becomes |
|---|---|---|
| `:129` fixture | `version = 8, stampedVersion = 9` | `version = 9, stampedVersion = 10` |
| `:101` assertion | `contains("9 to 8")` | `contains("10 to 9")` |
| `:111` assertion | `assertEquals(9, it.version)` | `assertEquals(10, it.version)` |
| `:97` fail message | "a version 9 file must not be opened by a version 8 build" | 10 / 9 |
| `:30-31` prose | "There is no version 9 schema (that number is reserved for a later mission)" | rewritten for 10 |
| `:58-74` insert | no `profileKind` | `"profileKind" to "GENERAL"` |

The insert matters because the fresh v9 `CREATE TABLE` has `profileKind TEXT NOT NULL` with **no**
SQL default — the default lives in the `ALTER` for upgrades only.

Left un-rebased, the test does not merely fail: against a v9 build Room sees
`user_version == 9 ==` its own version, skips migration entirely and fails on identity hash
instead, so `:97`'s `fail(...)` never fires either. **The tempting fix — relaxing the message
assertion — would make the repo's strongest persistence guard vacuous while showing green. It is
out of bounds.**

`MigrationChainTest.CURRENT_VERSION` (`:288`) goes 8 → 9 so the chain test upgrades a real file through
the new step against the committed schemas.

## UI

Five targeted edits in `ui/profiles/ProfilesScreen.kt`: a kind picker in the editor, and the card
summary driven by `ProfileCapabilities` instead of raw booleans. `profileSummary` (`:320-321`),
`profileChips` (`:336-338`) and `styleSummary` become `internal` so tests can assert the exact
strings — the precedent is `SystemPromptBuilder`, whose `internal` members exist "so tests can
assert against the exact emitted text instead of duplicating it".

`ProfilesViewModel`'s constructor signature is preserved, because `ScreenshotTest.kt:193`
constructs it.

**Roborazzi:** `app/src/test/screenshots/profiles.png` must be re-recorded
(`./gradlew testDebugUnitTest -Proborazzi.record`) and committed, because the card changes
visibly. A red pixel diff on the first run is expected and is **not** a regression — and
verification is never switched off to make it pass.

## The profile card defect (fixed here, not separately)

`profileSummary` prints "Transcribe only, no cleanup" whenever `llmEnabled` is false, but
`TextProcessor.process` applies replacement rules at `:34`, **before** the `llmEnabled` check at
`:44`. A profile with rules and no LLM does transform text; the card denies it. Symmetrically,
"Cleanup on" is printed from `llmEnabled` alone while the runtime also needs non-blank
`llmBaseUrl` and `llmApiKey`.

This is the same decision `ProfileCapabilities` centralises. Fixing it separately would mean
writing a fifth copy of it, so it is fixed as part of this mission.

## Tests

Five new files, in the house style — Robolectric + in-memory Room + real DataStore + hand-written
fakes, **no mocks**; ViewModels constructed by hand, not via Hilt; Flows read with `.first()` /
`withTimeout`, not Turbine. Template: `data/repository/ProfileSeedingTest.kt:23-60`.

| File | Proves |
|---|---|
| `ProfileKindPromptTest` | the byte-identical `GENERAL` invariant; each kind emits its tone string |
| `ProfileKindsTest` | registry completeness — every enum value has an entry, no duplicates |
| `ProfileCapabilitiesTest` | each capability's reason, including the two card-defect cases |
| `ProfileKindMigrationTest` | a real v8 file upgrades to v9 with every row at `GENERAL` |
| `ProfileCardSummaryTest` | the card's exact strings for rules-without-LLM and toggle-without-key |

Extended: `DowngradeGuardTest`, `MigrationChainTest`, `ScreenshotTest` (baseline only).

## Out of scope

No profile↔app binding, no automatic profile switching, no sampling parameters
(`temperature`/`maxTokens` stay hardcoded in `ChatModels.kt:5-10`), no new table, no onboarding
change, no sync of the new field, no change to the existing enums or to any replacement-rule text.

## Two things to decide — both answered 2026-09-10

1. **Irreversible, explicitly:** once a v9 build is installed, **no v8 APK can open the database
   again** — it throws and leaves the file untouched, by design (no destructive fallback in either
   direction), and no later update undoes it. A rollback to a pre-Mission-2 build means the tester
   loses their profiles, keys and history. This is the one irreversible act in the mission, so the
   column is worth approving deliberately rather than as a detail. **Accepted knowingly.**
2. **`NOTES` presets `SummarizeMode.LIGHT`.** This is the only place in the spec where a preset
   changes visible output. The alternative is `NONE`, leaving condensing always an explicit
   choice. **Decided: `LIGHT`.**

## Sequencing note

Room v9 is **not** in the G3 build. G3 proves the 7→8 upgrade on Lars's actual installation; a v9
build would make that step something other than what was planned. Mission 2's APK is installed
**after** G3, and the 8→9 upgrade is then its own small on-device check.
