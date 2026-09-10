# Skuldrevision — OPTIQON Voice, 2026-09-09/10

Backloggens post *"Vibe-code technical debt audit"* genomförd som Scope 4 i nattkörningen.
Ren läsning av `app/src/main` plus testträdet och byggkonfigurationen. Inga arkitektur-
ändringar, ingen upprensning för sin egen skull. Ett fynd (nr 4) var en defekt i kod som
skrevs samma natt och rättades direkt; allt annat är oförändrat.

Rankningen är efter **förväntad framtida kostnad**, inte efter hur illa koden ser ut.

**Uppdatering 2026-09-10:** fynd 1 och 2 är åtgärdade och stängda. Fynd 1:s rubrik är dessutom
rättad — den påstod "noll migrationstester", men steget 7→8 var redan täckt. Detaljer under
respektive fynd.

---

## Fix now

### 1. Åtta schemaversioner, sju handskrivna migrationer — sex av stegen otestade ✅ STÄNGT 2026-09-10

**Rättelse.** Rubriken löd först "noll migrationstester". Det var fel, och felet var mitt:
`OutboxMigrationTest` täckte redan steget 7→8 — datarräddning, tom outbox och att den migrerade
filen verkligen står på version 8 — och `DowngradeGuardTest` täckte nedgraderingsspärren. Det
som saknades var de **sex stegen före**, 1→2 till 6→7, samt hela kedjan från en version 1-fil.
Rättat här så att nästa läsare inte planerar om arbete som redan var gjort.

**Evidens (som den löd vid revisionen).** `OptiqonVoiceDatabase.kt:27` står på `version = 8`.
`DatabaseModule.kt:25-192` innehåller `migration1To2` … `migration7To8`, alla handskrivna SQL.
`app/schemas/` har alla åtta JSON-scheman exporterade. `room.testing` är redan deklarerad
(`app/build.gradle.kts:309`). `app/src/androidTest` finns inte.

**Allvarlighet / sannolikhet:** hög / medel. **Faktisk defekt eller latent risk:** latent risk.

**Påverkan.** Appen ligger redan på riktiga testenheter med riktig data — enheten i natt hade
tolv dikteringar, profiler och prompts. En felaktig migration tappar den historiken, och Room
kastar dessutom appen i ett tillstånd användaren inte kan ta sig ur utan att avinstallera. Det
är exakt den skada som inte går att ångra i efterhand.

**Åtgärd.** `MigrationChainTest` (5 tester) plus två utbrutna testhjälpare,
`ExportedSchema` och `DatabaseStructure`:

| Test | Vad det spärrar |
|---|---|
| `every step lands exactly on the schema its own version file describes` | Varje steg 1→2 … 7→8 körs på en fil byggd ur `N.json` och jämförs strukturellt mot en referens byggd ur `(N+1).json` — kolumntyper, NOT NULL, primärnycklar, index |
| `a dictation written by version 1 survives every step to version 8` | Hela kedjan med rader i; avslutas genom Room, så Room:s egen schemavalidering mot de kompilerade entiteterna får sista ordet |
| `the lifetime counters are seeded from the rows a version 6 database already had` | 6→7:s aritmetik: bara `SUCCESS` räknas, summor stämmer, `firstDictationAt` = äldsta *lyckade* |
| `the counters start at zero when there is nothing to seed them from` | Tom historik ger en rad med nollor och `firstDictationAt IS NULL`, inte ingen rad |
| `every version from 1 to the one the database declares has a migration and a schema` | En framtida versionshöjning utan migration faller här; versionen läses ur den riktiga databasen, inte ur annoteringen (Room har binär retention) |

**Varför inte `MigrationTestHelper`.** Den läser schemana genom asset-hanteraren, alltså ur den
byggda appens assets. För enhetstester är det debug-variantens assets, så varje debug-install
skulle bära appens hela schemahistorik. Drivrutinsvarianten av samma klass går inte heller:
projektets migrationer implementerar `migrate(SupportSQLiteDatabase)`, och Room 2.7:s
drivrutinsväg kräver `migrate(SQLiteConnection)`. Schemana ligger redan på disk i checkouten och
läses därifrån, vilket är samma mekanism `OutboxMigrationTest` redan använde.

**Bevis att testerna kan gå sönder.** Fem avsiktliga mutationer i `DatabaseModule`, en åt gången,
varje gång fångad av exakt det avsedda testet:

| Mutation | Fångades av |
|---|---|
| `historyVisible` läggs till med `DEFAULT 0` i stället för `1` | kedjetestet (raden försvinner ur historiken) — **inte** strukturtestet, som avsett |
| 6→7 summerar `WHERE status IS NOT NULL` | aritmetiktestet |
| 1→2 skapar indexet under fel namn | strukturtestet |
| 4→5 lägger till felstavad kolumn | strukturtestet + kedjetestet |
| `migration7To8` tas ur `ALL_MIGRATIONS` | versionsspärren + två till |

**Om defaultvärden.** SQLite kräver ett `DEFAULT` när en NOT NULL-kolumn läggs till i en tabell
som redan har rader, så varje kolumn en migration lägger till bär ett default som ingen entitet
deklarerar. En migrerad och en nyskapad databas av samma version skiljer sig därför permanent
där. Room:s egen validator jämför default bara där entiteten deklarerar ett; strukturjämförelsen
gör likadant, och de default som faktiskt avgör vad testaren ser pinnas i stället genom beteende
— en version 1-rad migreras och läses tillbaka. Mutationen `DEFAULT 0` ovan visar att den
spärren håller.

**Sidoeffekt.** Fixturkoden som bygger en databas ur ett exporterat schema fanns i två kopior
(`OutboxMigrationTest`, `DowngradeGuardTest`). Den ligger nu i `ExportedSchema` och båda testerna
använder den — en tredje kopia hade varit värre än att röra gröna tester. Båda är verifierade
gröna efteråt.

**Effort:** utfört. **Regressionsrisk:** ingen produktionskod ändrad — enbart `app/src/test`.
Hela sviten: **363 tester, 0 fel** (var 358).

---

### 2. `runCatching` sväljer `CancellationException` och räknar avbrott som serverfel ✅ STÄNGT 2026-09-10

**Evidens (som den löd vid revisionen).** `AccessRefresher.kt:84`:

```kotlin
val outcome = runCatching { deferred.await() }
    .getOrElse { RefreshOutcome.Failed(it) }
```

`runCatching` fångar `Throwable`, alltså även `CancellationException`. Utfallet blir
`Failed`, och `Failed` ökar `consecutiveFailures` (`:92-95`), som driver backoffen mot
grace-utgång. Samma repo vet redan bättre: `RegistrationRepository.kt:83` och `:134` kastar
uttryckligen om `CancellationException`. Mönstret finns också i `FirebaseSignInClient.kt:56`,
`:71` och `:90`.

**Allvarlighet / sannolikhet:** hög / hög. **Faktisk defekt.**

**Påverkan.** En användare som lämnar skärmen mitt under en kontokoll får det räknat som att
servern inte svarade. Tillräckligt många sådana i rad och appen når `grace_expired` — samma
tillstånd som stänger av diktering, och som F14-arbetet i natt just gjorde synligt. Med andra
ord: appen kan spärra sig själv på grund av navigering. På inloggningssidan är effekten
mildare men lika fel — ett avbrutet Google-flöde renderas som ett inloggningsfel.

**Bekräftad, inte antagen.** Båda halvorna av påverkan kördes som RED innan något rättades.
Ett avbrutet anrop publicerade `Failed` på `lastOutcome` (alltså "kunde inte nå servern" på
skärmen), **och** nästa `FOREGROUND`-trigger svarade `Throttled` — backoffen hade redan
slagit till efter ett enda avbrott. Den andra delen var den osäkra i revisionen, eftersom
uppräkningen ligger efter ett `mutex.withLock` i en coroutine som redan är avbruten;
Mutex:ens okontenderade snabbväg tar ingen suspension, så raden hinner köras.

**Åtgärd.** En gemensam hjälpare, `runCatchingCancellable` i
`domain/access/Cancellation.kt`, som alla fyra ställena nu går genom:

```kotlin
internal suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (throwable: Throwable) {
        coroutineContext.ensureActive()
        Result.failure(throwable)
    }
```

Kontrollen är `ensureActive()`, inte `catch (c: CancellationException) { throw c }` som
revisionen föreslog, och skillnaden är avsiktlig: de två avbrotten är inte samma sak. Är
**den här** coroutinen avbruten finns ingen kvar att lämna ett resultat till, och avbrottet
hör uppåt. Är det **arbetet vi kallade** som dog — en Firebase-task som avbryter sig själv,
en scope som tog sina barn med sig — medan vi själva lever, då är det ett äkta uteblivet
svar som anroparen måste kunna rendera. Ett villkorslöst omkast hade gjort en död läsning
till en anropare som tyst försvinner utan att rapportera något.

**Spärrar** — `CancellationPropagationTest`, 7 tester, varav källspärren nedan:

| Test | Vad det spärrar |
|---|---|
| `a cancelled caller is not handed a failure it could act on` | Hjälparen kastar om när anroparen själv är avbruten, och lämnar inget `Result` |
| `work that dies on its own is a failure, not a cancellation of the caller` | Kontrollen mot ett villkorslöst omkast: död callee + levande anropare = `Result.failure` |
| `an ordinary answer and an ordinary failure are unchanged` | Hjälparen är i övrigt `runCatching` |
| `a check the user walked away from is not reported as a failure` | `lastOutcome` blir inte `Failed` av att skärmen lämnas |
| `a check the user walked away from does not back the next one off` | Nästa trigger släpps igenom — det är den halvan som kan låsa appen |
| `a check that really fails does count, and does back the next one off` | Kontrollen: ett äkta fel räknas fortfarande och bromsar nästa trigger |

**Bevis att testerna kan gå sönder.** Fem avsiktliga mutationer, en åt gången, varje gång
fångad av exakt det avsedda testet:

| Mutation | Fångades av |
|---|---|
| hjälparen blir vanlig `runCatching` (nuläget före fixen) | båda avbrottstesterna + hjälpartestet — 3 tester |
| hjälparen kastar om *varje* `CancellationException` | kontrollen för död callee |
| `AccessRefresher`:s anropsställe går tillbaka till `runCatching` | båda avbrottstesterna (bevisar anropsstället, inte bara hjälparen) |
| `consecutiveFailures` slutar räknas upp vid `Failed` | kontrollen för äkta fel |
| ett av de spärrade filerna går tillbaka till `runCatching` | källspärren |

**Det som inte går att enhetstesta.** Tre av de fyra ställena ligger i
`FirebaseSignInClient`, runt `CredentialManager.create(...)` och `FirebaseAuth` — statiska
fabriker och finala klasser, och modulen har ingen mock-framework (`testImplementation`
innehåller junit, coroutines-test, Robolectric, Roborazzi, MockWebServer, room-testing,
work-testing — inget mockk/mockito). Det finns alltså ingen väg att driva dem från ett
JVM-test. Den spärr som *är* tillgänglig är i stället en källkontroll: testet läser
`AccessRefresher.kt` och `FirebaseSignInClient.kt` från disk och hävdar att ingen av dem
innehåller `runCatching`, med ett felmeddelande som namnger hjälparen. Grov, men den fångar
återfallet, och den går inte att råka ha kvar som grön lögn.

**Vad som medvetet lämnades.** `RegistrationRepository.kt:83` och `:134` har redan ett
korrekt omkast inline och ändrades inte — att röra korrekt kod hade varit scope-glidning, och
de ligger därför inte i källspärrens lista. `BubbleService.kt:880` är ett femte ställe av
samma klass (`runCatching { withContext(...) }` i en suspend-funktion), men det anropas på
den viktiga vägen redan under `NonCancellable`, där ett avbrott inte kan nå det, och
`BubbleService` är fynd 7:s område. Lämnat som eget beslut snarare än smuget in här.

**Effort:** utfört. **Regressionsrisk:** låg. Fyra anropsställen byter idiom; produktionskodens
enda nya beteende är att ett avbrott inte längre blir ett `Failed`. Hela sviten:
**370 tester, 0 fel** (var 363). `compileReleaseKotlin` grön.

### 3. Skärmbildstesterna kan inte gå sönder

**Evidens.** `app/build.gradle.kts:207` sätter `systemProperty("roborazzi.test.record", "true")`
— hårdkodat, inte villkorat. Varje anrop är `captureRoboImage("build/outputs/roborazzi/…")`
(`ScreenshotTest.kt:146`, `:182`, `:217`, `:241`, `SignedOutScreenTest.kt:56`,
`WaitingScreenTest.kt:50`), aldrig `compare` eller `verify`. Utdata går till `build/`, och det
finns inga incheckade referensbilder någonstans i repot.

**Allvarlighet / sannolikhet:** medel / hög. **Faktisk defekt i testsviten.**

**Påverkan.** Sex tester som ser ut som visuell regressionstäckning spelar bara in en ny bild
varje körning och jämför mot ingenting. De kan aldrig faila på en visuell regression. Beviset
är den här natten: **de två layoutdefekterna på feedbackskärmen — den dubblerade rubriken och
den bekräftelsemening som klipptes av navigeringsfältet — passerade en helt grön svit och
hittades först på skärmbild från riktig enhet.**

Det är inte värdelöst: de renderar faktiskt skärmarna, så en krasch i komposition failar. Men
det är rök, inte regression, och namnet lovar det senare.

**Billigaste tillräckliga verifiering.** Kör en gång i record-läge, checka in bilderna, slå
sedan om till compare och ändra en padding — testet ska bli rött.

**Rekommendation.** Antingen checka in guldbilder och slå på jämförelse, eller döp om dem till
det de är (`…SmokeTest`) och sluta räkna dem som täckning. Det första är bättre. **Effort:** en
halv dag. **Regressionsrisk:** låg, men räkna med initial flakighet i teckensnittsrendering.

---

## Fix when touched

### 4. R8:s keep-regler räknas upp per paket, så reflektionsberoende klasser utanför dem tystnar bara i release

**Evidens.** `app/proguard-rules.pro` håller `se.optiqon.voice.data.api.model.**` och
`se.optiqon.voice.data.db.entity.**`. `FeedbackPayload` lades i natt i
`se.optiqon.voice.domain.feedback` och serialiseras av Gson via reflektion
(`FeedbackPayload.kt:34-37`). Den låg alltså utanför skyddet.

**Verifierat, inte antaget.** Release-bygge kört: i `mapping.txt` kom `FeedbackBuildInfo` —
samma källfil, ingen keep-regel — ut som `m5.a` med fälten `a` och `b`. Med den tillagda regeln
överlever `FeedbackPayload`s sex fältnamn som strängar i `classes.dex`.

**Allvarlighet / sannolikhet:** hög / medel. **Var en faktisk defekt; rättad i `788658c`.**

**Påverkan (den kvarvarande, generella).** Enhetstesterna kör på JVM utan R8. En sådan här
defekt kan alltså aldrig synas i en grön svit — den syns först i en release-APK, i fält.
Konstruktionen "räkna upp paket" gör att nästa reflektionsberoende klass utanför de två paketen
tystnar på exakt samma sätt.

**Billigaste tillräckliga verifiering.** Grep i release-DEX efter fältnamnen, vilket är precis
vad som gjordes.

**Rekommendation.** Behåll uppräkningen men flytta beviset: ett litet test som
serialiserar/deserialiserar varje reflektionsberoende typ och jämför nyckelmängden, plus en
release-DEX-kontroll i utsläppsskriptet. Rätta inte genom att keepa hela `domain`. **Effort:**
några timmar. **Regressionsrisk:** låg.

### 5. `stopRecordingAndWait()` väntar inte, och anroparen avbryter flushen

**Evidens.** `BubbleService.kt:685-692` stoppar recordern och avbryter tre jobb, med
kommentaren *"Don't cancel recordingJob — let it finish flushing the file"* — men funktionen
returnerar direkt. Den enda anroparen är `onDestroy()` (`:293-297`), som två rader senare kör
`scope.cancel()`. `recordingJob` är startat i just den scopen (`:626`).

**Allvarlighet / sannolikhet:** medel / medel. **Faktisk defekt, men liten yta.**

**Påverkan.** Stoppas tjänsten mitt i en inspelning — "Turn off for now", en systemstopp, ett
notifikationsstopp — avbryts flushen. Ljudet blir varken WAV, dikteringsrad eller bevarat fall.
Användaren får ingen text och inget meddelande om att något gick förlorat. Namnet
`…AndWait` säger dessutom motsatsen till vad koden gör, vilket är den dyra delen: nästa läsare
tror att väntan redan finns.

**Billigaste tillräckliga verifiering.** Enhetstest som stoppar tjänsten under inspelning och
hävdar antingen en bevarad fil eller ett uttryckligt meddelande.

**Rekommendation.** Antingen gör den till det den heter (flusha under `NonCancellable` innan
`scope.cancel()`), eller döp om den till `stopRecordingWithoutWaiting()` och skriv ned att
ljudet förloras med flit. Båda är ärliga; dagens läge är det inte. **Effort:** några timmar.
**Regressionsrisk:** medel — den rör nedstängningsvägen för mikrofonen.

### 6. Två tjänster kopplade genom processglobala `companion object`-fält

**Evidens.** `TextInjectorService.kt:96-104` håller `instance`, `keyboardListener`,
`isKeyboardVisible` och `focusedAppPackage` som föränderliga companion-fält.
`BubbleService.kt:280`, `:283` och `:944` läser och skriver dem. Ingen av dem är knuten till
en livstid som Android faktiskt garanterar.

**Allvarlighet / sannolikhet:** hög / medel. **Latent risk — och F15 är dess första instans.**

**Påverkan.** Kopplingen är osynlig för både kompilatorn och testerna: två tjänster med var sin
livscykel delar tillstånd genom statiska fält, och den ena nollar den andras registrering i
sin `onDestroy()`. F15 (se nattloggen) är exakt det utfallet. Fler kommer, och de kommer att se
ut som spökbuggar — allt "lever", inget fungerar.

**Billigaste tillräckliga verifiering.** Rendera livscykelmatrisen i test: riv och återanslut
tillgänglighetstjänsten utan att röra `BubbleService` och hävda att bubblan fortfarande får
tangentbordshändelser.

**Rekommendation.** Inte en omskrivning. Det räcker att flytta *återanslutningen* dit tjänsten
faktiskt kommer tillbaka (`onServiceConnected`) och göra registret till en enda ägd punkt i
stället för fyra fria fält. **Effort:** en dag. **Regressionsrisk:** medel — kräver enhet med
tillgänglighetstjänsten av/på, alltså Lars.

### 7. `BubbleService` bär för mycket, och defekterna samlas där

**Evidens.** 1018 rader (klart störst i `main`). Filen håller överläggets fönsterhantering,
dragmekaniken, fan-menyn, ljudinspelning och nivåmätning, tystnadsdetektion, timers,
tillståndsmaskinen, åtkomstkontrollen och bevarandet av avbrutna inspelningar.

**Allvarlighet / sannolikhet:** medel / hög. **Underhållsskuld, inte defekt.**

**Påverkan.** Det är evidensen som gör fyndet, inte radantalet: F13, F15 och bubbeldefekten i
steg 7 av G3-smoken bor alla i den här filen. Varje ny diagnos börjar med att läsa tusen rader
för att hitta vilken av åtta angelägenheter som gick sönder.

**Rekommendation.** Bryt inte upp den på spekulation. Lyft ut **en** angelägenhet nästa gång
filen ändå ska ändras — inspelningen är den självständigaste och den mest testbara.
**Effort:** en dag per utbrytning. **Regressionsrisk:** medel, därav "när den ändå berörs".

---

## Accept / monitor

### 8. `BubbleService.isRunning` skrivs på två ställen och läses ingenstans

**Evidens.** `BubbleService.kt:60-61` deklarerar `@Volatile var isRunning`, satt till `true` på
`:152` och `false` på `:291`. Sökning i hela `app/src` ger noll läsare (de träffar som finns i
`BubbleView.kt:132`, `:138` är `ValueAnimator.isRunning`).

**Allvarlighet:** låg. **Död kod från iterativa ändringar.**

**Påverkan.** Ingen idag. Risken är att en framtida läsare tar det för en hälsosignal och
bygger logik på ett fält som ingen underhåller — och `BubbleService` kan sluta visa bubblan
utan att tjänsten dör (se fynd 6), så flaggan skulle ljuga just i det fall någon vill använda
den.

**Rekommendation.** Ta bort raden nästa gång filen öppnas. Ingen egen insats.

---

## Vad som granskades och befanns sunt

Redovisas för att revisionens avgränsning ska vara läsbar, inte för att fylla ut.

- **Release- och uppdateringskedjan.** `versionCode` packar datum och patch separat med en
  kommentar om exakt det utsläpp som gick fel tidigare (`app/build.gradle.kts:65-72`).
  Debug-nyckelfallbacken är trestegs, loggar en varning, suffixar `versionName` med
  `-devsigned` och backas av deny-listor i `scripts/signing/sign-release.sh` och
  `.github/scripts/verify-apk-signer.sh`. Etiketten utges inte för att vara spärren.
- **Exporterade komponenter.** `MainActivity` och `BootReceiver` exporterade med avsikt;
  `BubbleService` är `exported="false"`; `TextInjectorService` är skyddad av
  `BIND_ACCESSIBILITY_SERVICE`. Ingen bred exponering.
- **Felsökningshakarna.** `AccessDebugCommands`, `AccessDebugReceiver` och
  `AccessDebugControls` ligger alla i `app/src/debug` och finns inte i en release-APK. Det är
  rätt plats för `SIGN_OUT`, `CLOCK_OFFSET` och `FAIL_REFRESH`.
- **Hemlighetshantering.** Ingen loggrad i `main` skriver nyckel, token eller `Authorization`.
  Nycklarna ligger i `SecurePreferencesStore`. F19-arbetet i natt stängde vägen där en lagrad
  nyckel kunde nå UI-tillståndet.
- **Avbrottshantering i nätverkslagret.** `TranscriptionManager`, `TextProcessor` och
  `RegistrationRepository` kastar alla om `CancellationException` uttryckligen. Regeln är känd
  i repot — fynd 2 handlar om de tre ställen som inte följer den.
