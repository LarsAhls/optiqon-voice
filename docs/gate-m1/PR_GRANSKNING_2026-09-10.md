# PR-granskning 2026-09-10 — #9, #10, #12

Läsande granskning. **Ingen merge, ingen commit av körande kod, inget bygge, ingen `firebase`,
ingen enhet.** Spec-avvikelser eskaleras här — de adjudiceras inte.

Underlag: `docs/MISSION_2_SPEC.md`, `docs/DECISION_SHEET_2026-09-10.md`,
`docs/gate-m1/G3_PREP_2026-09-10.md`, `docs/gate-m1/GATE_2_READBACK.md`, samt diffarna på
`feat/mission-2-typed-profiles` (`dc8ac67`), `chore/minsdk-28` (`568ae79`) och
`fix/signin-offline-message` (`794f0de`).

---

## Sammanfattning

| PR | Dom | Blockerar merge? | Vad som återstår |
|---|---|---|---|
| **#9** Typade profiler + Room 8→9 | **Konform i sak, tre öppna beslut** | Ja — besluten är Lars, inte mina | Tre beslut nedan + en säkerhetsfix (Å1) |
| **#10** `minSdk 28` | **Matchar D8 exakt** | Nej | Två stale `minSdk = 26`-referenser, båda avsiktligt orörda |
| **#12** Offline-text vid inloggning | **Ingen regression, korrekt klassning** | Nej | Två fynd, båda backlog-material |

**Uppdatering 2026-09-10:** #10 (`4792200`) och #12 (`29d873a`) är mergade efter Lars merge-lyft;
`main` står på `649b799`. #9 mergas enligt planen efter G3 och efter den
fysiska 8→9-verifieringen på enhet — den ordningen är specens egen
(*"Room v9 is not in the G3 build … Mission 2's APK is installed after G3"*).

---

## PR #9 — `feat/mission-2-typed-profiles` (`dc8ac67`, bas `c8b6477`)

19 filer, +1752/−37, MERGEABLE/CLEAN, fyra CI-checkar SUCCESS.

### Bas-rekonciliering mot `main` (`dcdcd13`) — **ren**

```
$ git log --oneline c8b6477..dcdcd13
dcdcd13 docs(gate-m1): the tester account is still revoked, and that is a decision (#13)
cffaa81 docs: G3-forberedelse, sittning 3-lista, D2/D3 som beslutade (#11)

$ git diff --stat c8b6477 dcdcd13
 docs/BETA_SIGNING_AND_DISTRIBUTION.md |  19 +++--
 docs/gate-m1/G3_PREP_2026-09-10.md    | 150 ++++++++++++++++++
 docs/gate-m1/SITTING_3_CHECKLIST.md   | 104 +++++++++++++
 3 files changed, 266 insertions(+), 7 deletions(-)
```

Två commits, tre docs-filer, **noll rader körande kod**. Grenen behöver ingen rebase, och
CI-grönt på `dc8ac67` säger fortfarande något om `main`.

### Verifierat mot spec

**`GENERAL` ger byte-identisk systemprompt — verifierat i koden, inte ur PR-texten.**
Invarianten har två halvor och båda håller:

- `SystemPromptBuilder.declaredToneSection` returnerar `null` direkt för `GENERAL`, så ingen
  `Tone:`-sektion emitteras.
- Anropet till `inferStyleForApp(appContext)` i `buildAppContextSection` är fortfarande
  `GENERAL`-grindat, så app-gissningen finns kvar oförändrad.

`ProfileKindPromptTest` asserterar exakt dessa två halvor — dels att ingen stilkombination av
`GENERAL` (`OutputStyle × RewriteMode × SummarizeMode × emoji`, över `null`, `mail`, `chat`,
`other`) introducerar en tonsektion, dels att en `GENERAL`-profil **fortfarande** får sin
app-stilledtråd. Det är asserterat, inte argumenterat, precis som specen kräver.

**`TextProcessor`s bearbetningsboolean är oförändrad.** Gammalt villkor
`!profile.llmEnabled || prefs.llmBaseUrl.isBlank() || prefs.llmApiKey.isBlank()` ersätts av
`!ProfileCapabilities.state(POST_PROCESSING, profile, environment).isAvailable`.
`evaluatePostProcessing` är samma tre klausuler i samma ordning, bara med varsin
namngiven `REASON_*`. Ekvivalent.

**Migrationen.** `migration8To9` =
``ALTER TABLE `profiles` ADD COLUMN `profileKind` TEXT NOT NULL DEFAULT 'GENERAL'``, adderad
till `ALL_MIGRATIONS`; `OptiqonVoiceDatabase.version` 8 → 9; `ProfileEntity` får
`profileKind: String = ProfileKind.GENERAL.name` med `enumValueOrDefault` på vägen ut. Ingen
`fallbackToDestructiveMigration` i någon riktning, ingen heuristisk omklassning av befintliga
profiler — KDoc:en säger det uttryckligen. `app/schemas/…/9.json` har version 9,
`identityHash ad415eec1b2ce953e9da7e3bb880bf53` och `` `profileKind` TEXT NOT NULL`` **utan**
SQL-default, vilket matchar `DowngradeGuardTest`s insert.

**Nedgraderingsvakten är rebasead exakt enligt specens tabell.** Fixturen står på
`version = 9, stampedVersion = 10`, assertionerna på `"10 to 9"` och `assertEquals(10, it.version)`,
inserten får `"profileKind" to "GENERAL"`, och KDoc:en förklarar varför siffrorna är bärande.
`MigrationChainTest.CURRENT_VERSION = 9`. Meddelandeassertionen är **inte** uppmjukad — det
hade varit utanför ramen.

### Öppna beslut för Lars (eskaleras, inte avgjorda) — **avgjorda 2026-09-10, se rutan**

> **Beslut 2026-09-10 (Lars/ChatGPT).** Granskningen är godkänd. B1 **godkänd** — sex värden är
> spec-enligt, PR-beskrivningen är rättad. B2 **avslagen** — `CHAT` och `SOCIAL` får deklarera sin
> `ProfileKind`/kontext men ska **inte** skriva över användarens separata stilfält med `RELAXED`
> och `emojiAllowed = true`; `NOTES = LIGHT` behålls; ingen migrering och ingen befintlig profil
> får ändras av detta. B3 är **inte** ett produktbeslut utan löses spec-konformt i M-C: bevara
> signaturen specen kräver, eller använd en annan intern söm som ger samma kontrakt — eskalera
> endast om det kräver en materiell arkitekturändring. Å1 (nyckelexponeringen) **ska åtgärdas i
> M-C före merge** med minimal representation, t.ex. `hasApiKey: Boolean`; ingen API-nyckel får
> kunna hamna i `toString()`, en Compose-dump eller vanlig krasch-/loggutdata. PR #9 är därmed
> fortfarande **inte** godkänd för merge. Besluten är också inskrivna i PR #9:s beskrivning.



**B1 — Sex `ProfileKind` är *inte* en avvikelse. PR-texten har fel om sin egen PR.**
PR-beskrivningen listar *"Sex värden, inte fem som specen sa"* som avvikelse 1.
`docs/MISSION_2_SPEC.md` säger ordagrant **"Six values, not five."** och ger varje värde ett
deklarerat hem (`EMAIL`, `CHAT`, `NOTES`, `SOCIAL`). Sexvärdesenumet är alltså spec-sanktionerat.
**Inget beslut behövs — men PR-beskrivningen bör rättas innan merge**, annars ärver
beslutsloggen en avvikelse som aldrig fanns.

**B2 — `CHAT`/`SOCIAL` presetar `RELAXED` + `emojiAllowed`. Detta är en verklig avvikelse.**
Specen är explicit: *"`NOTES` presets `SummarizeMode.LIGHT`. This is the **only** place in the
spec where a preset changes visible output."* Grenen lägger till två till:

```kotlin
val CHAT = ProfileKindPreset(
    kind = ProfileKind.CHAT, label = "Chat",
    toneHint = "Use a casual conversational tone. Keep it concise and natural for chat.",
    suggested = SuggestedStyle(outputStyle = OutputStyle.RELAXED, emojiAllowed = true)
)
val SOCIAL = ProfileKindPreset(
    kind = ProfileKind.SOCIAL, label = "Social media",
    toneHint = "Keep it concise and suitable for social media posts.",
    suggested = SuggestedStyle(outputStyle = OutputStyle.RELAXED, emojiAllowed = true)
)
```

Förmildrande, och det ska vägas in: `suggested` appliceras **endast** när användaren aktivt
väljer en typ i profilredigeraren. Den rör aldrig en lagrad profil och migrationen applicerar
den aldrig. Ingen befintlig profil ändrar beteende vid uppgradering.
**Beslut Lars:** godkänn de två presetarna som en medveten utvidgning av sittning 1-beslutet,
eller be om att `suggested` tas bort för `CHAT`/`SOCIAL` så att `NOTES` förblir det enda stället
där en preset ändrar synlig utdata.

**B3 — Odeklarerad avvikelse: `ProfilesViewModel`s konstruktorsignatur ändras.**
Specen säger: *"`ProfilesViewModel`'s constructor signature is preserved, because
`ScreenshotTest.kt:193` constructs it."* Grenen lägger till en fjärde parameter
(`preferences: PreferencesDataStore`), utökar `combine` från tre till fyra flöden, och rör
`ScreenshotTest.kt`. Detta står **inte** i PR-beskrivningens avvikelselista.
**Beslut Lars:** acceptera signaturändringen (den är den enklaste vägen till att UI:t ser
`CapabilityEnvironment`), eller kräv att miljön hämtas utan att konstruktorn breddas.
Notera att B3 och Å1 nedan har samma rot — att miljön bärs in i UI-lagret.

### Åtgärdslista

**Å1 (säkerhet, bör åtgärdas före merge) — `llmApiKey` i klartext i en `data class`.**

```kotlin
data class CapabilityEnvironment(
    val llmBaseUrl: String,
    val llmApiKey: String
)
```

Kotlins genererade `toString()` skriver ut nyckeln. Sprängradien är **större än först antaget**:
`ProfilesUiState` är en `@Immutable data class` som numera *bär* `environment`, så varje
`toString()` av UI-tillståndet — en Compose-tillståndsdump, en kraschlogg, ett testutfall — kan
skriva ut nyckeln. Konstruktionsställen: `ProfileCapabilities.kt:49/57`, `TextProcessor.kt:50`,
`ProfilesViewModel.kt:50`.
**Föreslagen åtgärd, i preferensordning:**
1. Byt `llmApiKey: String` mot `hasApiKey: Boolean`. Endast tomhet konsulteras någonsin —
   `hasProvider` och `evaluatePostProcessing` frågar bara `isNotBlank()`. Detta tar bort nyckeln
   ur typen helt och löser samtidigt halva B3:s obehag.
2. Om nyckelvärdet ändå måste bäras: överskugga `toString()` på `CapabilityEnvironment` så att
   den redovisar `llmApiKey=<set>` / `<unset>`, och lägg ett test som pinnar det.

**Å2 — rätta PR-beskrivningens avvikelse 1** enligt B1.

**Å3 — deklarera signaturändringen** enligt B3 i PR-beskrivningen, oavsett hur Lars beslutar.

**Å4 — profilkortets kapabilitetslögn** (`BACKLOG.md`, "profile card reports capabilities it does
not have") är exakt det som Mission 2:s kapabilitetsarbete ska ersätta. Kontrollera vid merge att
`ProfilesScreen.profileSummary` verkligen läser den utvärderade kapabiliteten och inte fortsatt
`llmEnabled` ensamt — annars är backlog-posten fortfarande öppen efter Mission 2.

### Vad denna granskning **inte** bevisar

PR-beskrivningen anger sviten till **454 / 0 / 2** (baslinje 400/0/2). Den siffran vilar på
PR-texten. CI-checken exponerar inget antal, och ingen körning har gjorts här — uppdraget är
läsande. Sviten och den fysiska 8→9-migreringen på enhet hör till M-C, inte hit.

---

## PR #10 — `chore/minsdk-28` (`568ae79`)

2 filer, +12/−4, MERGEABLE/CLEAN. `app/build.gradle.kts`: `minSdk = 26` → `minSdk = 28` med
kommentar som hänvisar till D8. `docs/BETA_SIGNING_AND_DISTRIBUTION.md` följer med.

**Dom: matchar `docs/DECISION_SHEET_2026-09-10.md:13` ordagrant** —
*"| 4 | **D8** — Android floor | **Raise `minSdk` 26 → 28.** Lars confirms no tester is on
Android 8.0/8.1. |"*. Ingen sidoeffekt i diffen.

**Två stale `minSdk = 26`-referenser som PR:en lämnar orörda — båda korrekt orörda:**

- `docs/gate-m1/PREFLIGHT.md:161` (`minSdk / targetSdk | 26 / 35`) är ett **daterat protokoll**
  från när mätningen gjordes. Ett protokoll skrivs inte om i efterhand.
- `tools/rotationtest/app/build.gradle.kts:17` (`minSdk = 26`) är rotationsmatrisens egen sele,
  som måste kunna nå låga API-nivåer för att testa just det som D8 utesluter. Höjs den förlorar
  matrisen sin poäng.

**Korrigering av ett tidigare påstående från min sida:** "#10 blockerar G4" är för starkt.
`--rotation-min-sdk-version 28` styrs av **enhetens** API-nivå, inte av appens `minSdk`. Det
verkliga beroendet är att #10 bör landa före **A7-bygget**, så att den signerade artefakten och
golvet säger samma sak.

---

## PR #12 — `fix/signin-offline-message` (`794f0de`)

6 filer, +72/−3, MERGEABLE/CLEAN. Lägger `SignInClient.Offline` / `SignInClient.Cancelled`,
`classifyGoogleFailure` i `FirebaseSignInClient`, EN+SV-strängar och en BACKLOG-post om
`clearCredentialState()`.

Klassningen: `failure !is GetCredentialException → failure` (passeras rakt igenom);
`!networkMonitor.isOnline.value → Offline()`; `is GetCredentialCancellationException →
Cancelled()`; annars oförändrad. Det matchar fältobservationen i
`docs/gate-m1/G3_PREP_2026-09-10.md:118-119` — flygplansläget gav
`Activity is cancelled by the user` via GMS status 16, och plattformstexten skrevs ut rått.

**Ingen regression.** `AccountViewModel.kt:278-281` definierar `thenAwaitName()` som exakt
`fold(onSuccess = { }, onFailure = { _message.value = it.message })`. PR:ens inbyggda `fold` är
en strikt utvidgning med `else -> it.message` bevarat, så alla tidigare felvägar skriver
fortfarande samma text.

**Fynd 1 — anslutningskontrollen är overifierad.** `NetworkMonitor` härleder `isOnline` ur
`NET_CAPABILITY_INTERNET` ensamt, **inte** ur `NET_CAPABILITY_VALIDATED`. Ett anslutet men
oanvändbart nät (fångstportal, hotell-wifi) rapporterar därför "online", och användaren får
"Sign-in was not completed" i stället för offline-texten. Det observerade flygplansläget hanteras
korrekt — det här är det angränsande fallet. Backlog-material, inte merge-blockerare.

**Fynd 2 — noll tester.** `gh pr diff 12 --name-only | grep -c test` → `0`. Infrastrukturen finns
redan och används inte: `AccountViewModelTest.kt` har en `FakeSignInClient` (rad 77), en sele som
håller den (raderna 121/144) och etablerat mönster för att mata in typade fel
(rad 450: `h.signIn.sendResult = Result.failure(SignInClient.EmailLinkNotEnabled())`). Två
tester — `Offline` respektive `Cancelled` → rätt sträng — kostar närmast ingenting och pinnar den
enda logik PR:en tillför. **Rekommendation: lägg till dem före merge.** Inga mocks behövs, och
inga får införas.

---

## Två rättade felaktigheter i projektets egna anteckningar

**R1 — assetlinks-404.** `docs/gate-m1/RAPPORT_2026-09-10.md:125` påstod att
`assetlinks.json` fortfarande svarar 404 och därmed blockerar App Links-halvan av G3 steg 2.
Motsagt av `GATE_2_READBACK.md:24-40` (200 på båda värdarna, `Content-Type: application/json`,
innehållet lika med repofilen, `g2-postflight.sh: PASS`, Hosting-release 2026-09-09 13:29:47), av
`public/.well-known/assetlinks.json` (committad i `6a67184`) och av Digital Asset Links-API:t som
listar `se.optiqon.voice`. Påståendet är ärvt från det gamla, felstavade projektet
`optioqon-voice` och mättes aldrig om. **Raden är rättad** i RAPPORT-filen, med den kvarstående
sanningen bevarad: filen serverar i dag endast debug-certets SHA-256, så betacertets SHA-256
måste läggas till **additivt** vid G4-rotationen och Hosting deployas om.

**R2 — release-variantens enhetstester som "obudgeterad skuld".** Påståendet är fel.
`app/build.gradle.kts:348-360` dokumenterar avstängningen som ett **designbeslut**:
Compose-UI-testerna startar `androidx.activity.ComponentActivity`, som bara finns i den
sammanslagna manifesten via `debugImplementation(ui-test-manifest)` och aldrig får ingå i en
release-artefakt. Avstängningen har dessutom en egen kompenserande spärr,
`verifyReleaseReflectionContract`, kopplad som finalizer på `assembleRelease`/`bundleRelease` så
att den körs mot artefakten som faktiskt producerades.
**Ingen versionerad fil under `docs/` bar påståendet** — det stod i den ospårade
rotfilen `OPTIQON_VOICE_CHATGPT_HANDOFF_2026-09-09.md:126` (ignorerad av `.gitignore`) och i
betaplanens §9. Ingen rättning gick alltså att göra i `docs/`; korrigeringen registreras här i
stället, som versionerad sanning. **Kvarstående, giltig del av påståendet:** verifiering av en
**verklig release-artefakt** före extern beta återstår, och det hör hemma i M-G/M-H, inte som
testinfrastrukturskuld.

---

## Vad som återstår, och för vem

| Punkt | Vem | När |
|---|---|---|
| ~~Merge-lyft #10~~ | — | **Mergad 2026-09-10** (`4792200`) |
| ~~Merge-lyft #12~~ | — | **Mergad 2026-09-10** (`29d873a`) |
| ~~Beslut B2 och B3 på #9~~ | — | **Avgjorda 2026-09-10** — B2 avslagen, B3 löses spec-konformt |
| ~~Å2 PR-beskrivningen (B1)~~ | — | **Rättad 2026-09-10** i PR #9:s beskrivning |
| B2:s följdändring: ta bort `suggested` för `CHAT`/`SOCIAL` | Claude, inom M-C | Före #9:s merge |
| B3: spec-konform lösning för `ProfilesViewModel` | Claude, inom M-C | Före #9:s merge |
| Å1 nyckelexponeringen | Claude, inom M-C | Före #9:s merge |
| Fynd 2 på #12: två tester för `Offline`/`Cancelled` | Claude, backlog | Ej merge-blockerande |
| Fynd 1 på #12: `NET_CAPABILITY_VALIDATED` | Claude, backlog | Ej merge-blockerande |
| Fysisk Room 8→9 på enhet + full svit | M-C, efter G3 | Efter G3-closeout |
