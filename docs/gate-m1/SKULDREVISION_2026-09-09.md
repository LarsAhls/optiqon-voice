# Skuldrevision — OPTIQON Voice, 2026-09-09/10

Backloggens post *"Vibe-code technical debt audit"* genomförd som Scope 4 i nattkörningen.
Ren läsning av `app/src/main` plus testträdet och byggkonfigurationen. Inga arkitektur-
ändringar, ingen upprensning för sin egen skull. Ett fynd (nr 4) var en defekt i kod som
skrevs samma natt och rättades direkt; allt annat är oförändrat.

Rankningen är efter **förväntad framtida kostnad**, inte efter hur illa koden ser ut.

**Uppdatering 2026-09-10:** fynd 1, 2, 3, 4 och 5 är åtgärdade och stängda. Tre av revisionens
egna rader är dessutom rättade: fynd 1 påstod "noll migrationstester", men steget 7→8 var redan
täckt; fynd 4:s rekommenderade verifiering (grep i release-DEX) visade sig osund — den svarar
grönt på en trasig release; och fynd 5 hade fel om *vad* som förlorades — inte flushen, utan
bevarandet. Fynd 4 och 5 har flyttats hit från *Fix when touched*. Arbetet med fynd 5 blottade
dessutom **ett nytt fynd (nr 9)**: samma förlust sker på transkriberingsvägen, och det är
oklarerat. Detaljer under respektive fynd.

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

### 3. Skärmbildstesterna kan inte gå sönder ✅ STÄNGT 2026-09-10

**Evidens (som den löd vid revisionen).** `app/build.gradle.kts:207` sätter
`systemProperty("roborazzi.test.record", "true")` — hårdkodat, inte villkorat. Varje anrop är
`captureRoboImage("build/outputs/roborazzi/…")` (`ScreenshotTest.kt:146`, `:182`, `:217`,
`:241`, `SignedOutScreenTest.kt:56`, `WaitingScreenTest.kt:50`), aldrig `compare` eller
`verify`. Utdata går till `build/`, och det finns inga incheckade referensbilder någonstans i
repot.

**Allvarlighet / sannolikhet:** medel / hög. **Faktisk defekt i testsviten.**

**Påverkan.** Sex tester som ser ut som visuell regressionstäckning spelar bara in en ny bild
varje körning och jämför mot ingenting. De kan aldrig faila på en visuell regression. Beviset
är den här natten: **de två layoutdefekterna på feedbackskärmen — den dubblerade rubriken och
den bekräftelsemening som klipptes av navigeringsfältet — passerade en helt grön svit och
hittades först på skärmbild från riktig enhet.**

---

**Bekräftad.** Med 1 dp:s ändring av `horizontal`-paddingen i `AccountScreenContent` och bygget
i sitt dåvarande läge (`roborazzi.test.record` hårdkodat till `true`): **6 av 6 tester PASSED.**
De kunde inte gå sönder. Samma mutation med jämförelse påslagen ger 6 FAILED.

**Åtgärd.** Inspelning ligger nu bakom `-Proborazzi.record`; allt annat jämför. De tolv bilderna
är incheckade i `app/src/test/screenshots/` i stället för i `build/`, och all fotografering går
genom `captureBaseline`, som är enda vägen till den katalogen. Bygget fick också en
`inputs.dir(...)` på bildkatalogen — utan den är en redigerad guldbild UP-TO-DATE och sviten
förblir grön, en tystare variant av samma defekt.

Roborazzis egna förval behövde inga tillägg för att vara stränga: `Added` (guldbild saknas)
failar lika hårt som `Changed`, så en ny skärm utan incheckad bild går rött av sig själv.

**Ett latent hinder som revisionen inte nämnde** och som fixen tvingade fram: tre tester i
`ScreenshotTest` fotograferade två gånger till samma filnamn. I inspelningsläge var det
osynligt — den andra bilden skrev över den första — men i jämförelseläge mäts den första bilden
mot ett läge skärmen ännu inte nått. Uppdelat i `show` + `shoot`.

**Spärrar** — `ScreenshotBaselineTest`, 4 tester. En vanlig visuell regression failar numera av
sig själv; det som behöver spärras är uppsättningen, alltså de sätt den kan smyga tillbaka till
inspelning utan att något ser trasigt ut:

| Test | Vad det spärrar |
|---|---|
| `the build compares by default and records only when it is asked to` | `roborazzi.test.record` hårdkodas inte igen, och `roborazzi.test.verify` finns kvar |
| `no screen is shot into a directory nobody compares` | Ingen testkälla skriver till `build/outputs/roborazzi` eller anropar `captureRoboImage` förbi `captureBaseline` |
| `every baseline belongs to a screen that is still shot` | Ny skärm utan bild, och kvarglömd bild efter borttaget test — båda faller ut, och felmeddelandet säger vilket |
| `every screen that is shot resolves its typefaces first` | Ingen skärm fotograferas utan `PrimeTypefaces()` (se nedan) |

**Flakigheten revisionen förutsåg var verklig — och den var inte flakighet.** Raden "räkna med
initial flakighet i teckensnittsrendering" slog in, men orsaken var deterministisk: båda
typsnittsfamiljerna levereras som *en* variabel fontfil som instansieras per vikt, och
typsnittscachen är nycklad på resursen utan dess variationsaxlar. Den vikt som efterfrågas
först vinner därför för resten av JVM:en. Följden: exakt samma kod gav 0 skilda pixlar i full
svit och 2197 respektive 3722 när klassen kördes ensam — grön här, röd där. Fixen är
`PrimeTypefaces()`, som löser upp alla femton typstilar i fast ordning innan skärmen ritas,
mätt på nollstorlek och klippt. **Ingen av de tolv incheckade bilderna ändrades av det**, vilket
är beviset på att det är en stabilisering och inte ett nytt utseende; alla fyra körformer
rapporterar nu 0 skilda pixlar.

**Tröskeln är mätt, inte vald.** Första CI-körningen svarade på den fråga den här maskinen inte
kan svara på: Linux avviker från de Windows-inspelade bilderna med **2–36 pixlar av 376 980**,
allt i glyfkanter. Jämförelsen tillåter nu 75 — dubbelt den värsta plattformsskillnaden och en
fjärdedel av den minsta uppmätta regressionen. Båda talen står i koden bredvid konstanten.
CI-körning  är grön på ubuntu mot de Windows-inspelade bilderna, så tröskeln är
verifierad över plattformsgränsen och inte bara räknad.

**Bevis att testerna kan gå sönder.** Åtta avsiktliga mutationer i tio körningar, en åt
gången, varje gång återställd:

| Mutation | Utfall |
|---|---|
| 1 dp `horizontal`-padding, bygget i sitt gamla läge (record) | **6 PASSED** — RED-beviset för själva fyndet |
| 1 dp `horizontal`-padding, jämförelse påslagen | 6 FAILED, 297–3807 pixlar |
| 1 dp `vertical`-padding | 6 PASSED — inte en testsvaghet: kolumnen är centrerad och kortare än skärmen, så symmetrisk vertikal padding flyttar ingen pixel |
| `Pine80` ett steg (`0x7E`→`0x7D`) | **6 PASSED, 0 skilda pixlar** — se begränsningen nedan |
| `Pine80` två steg (`0x7E`→`0x7C`) | FAILED, 21 276 / 22 802 pixlar |
| `roborazzi.test.record` hårdkodad till `"true"` igen | `the build compares by default…`, ensam |
| en fotografering riktad tillbaka mot `build/outputs/roborazzi` | `no screen is shot into a directory nobody compares`, ensam |
| en guldbild raderad | `every baseline belongs to a screen…` **och** Roborazzis `Added` på skärmens eget test |
| `PrimeTypefaces()` borttagen ur en klass | `every screen that is shot resolves its typefaces first`, plus 2197/3722 pixlars drift |
| 1 dp `horizontal`-padding **med tröskeln 0,0002 på plats** | 6 FAILED, tystast 297 mot en gräns på 75 |

**Det som inte täcks, uttryckligen.** Två hål, båda uppmätta:

- **Färgdrift under 1/255.** Roborazzis förvalda komparator tillåter ett kanalavstånd på 0,007.
  Ett steg på primärfärgen gav `pixelDifferences=0`. Uppsättningen fångar layoutdrift och
  synlig färgändring, inte kanalbrus. Två steg fångas.
- **Regressioner tystare än 75 pixlar.** Priset för att kunna spela in på Windows och jämföra på
  Linux. Marginalen till den minsta uppmätta regressionen är fyra gånger, till det värsta
  plattformsbruset två. Ingen annan spärr täcker mellanrummet.

**Vad som medvetet lämnades.** Bildlistan i `every baseline belongs to a screen that is still
shot` är handskriven i stället för härledd: namnen når `captureBaseline` via hjälparparametrar
och går inte att läsa av källorna tillförlitligt, och en lista som *måste* redigeras är själva
poängen — den failar åt båda hållen. Feedbackskärmen, som var upprinnelsen till fyndet, har
ingen guldbild här; den ligger utanför de tolv skärmar sviten fotograferar och är eget arbete.

**Effort:** utfört. **Regressionsrisk:** ingen i produktionskod — ingen produktionsfil ändrades.
Hela sviten: **374 tester, 0 fel, 2 hoppade** (var 370). Commits `685d0ac` och `faf6cc0`.

---

### 4. R8:s keep-regler räknas upp per paket, så reflektionsberoende klasser utanför dem tystnar bara i release ✅ STÄNGT 2026-09-10

**Evidens (som den löd vid revisionen).** `app/proguard-rules.pro` håller
`se.optiqon.voice.data.api.model.**` och `se.optiqon.voice.data.db.entity.**`. `FeedbackPayload`
lades i natt i `se.optiqon.voice.domain.feedback` och serialiseras av Gson via reflektion
(`FeedbackPayload.kt:34-37`). Den låg alltså utanför skyddet.

**Allvarlighet / sannolikhet:** hög / medel. **Var en faktisk defekt; rättad i `788658c`
samma natt.**

**Påverkan (den kvarvarande, generella).** Enhetstesterna kör på JVM utan R8. En sådan här
defekt kan alltså aldrig synas i en grön svit — den syns först i en release-APK, i fält.
Konstruktionen "räkna upp paket" gör att nästa reflektionsberoende klass utanför de två paketen
tystnar på exakt samma sätt.

---

**Bekräftad.** Med keep-regeln för `FeedbackPayload` borttagen och ett verkligt release-bygge kört
står alla sex fältnamnen som omdöpta i R8:s egen `mapping.txt` — `message -> a`, `contact -> b`,
`appVersion -> c`, `androidSdk -> d`, `deviceModel -> e`, `createdAtMs -> f`. Samma byggträd,
samma commit: **hela enhetssviten grön.** Det är RED-beviset, och det är också hela fyndets
poäng — defekten finns bara där ingen JVM-test kan se den.

**Revisionens egen rekommenderade verifiering håller inte.** "Grep i release-DEX efter
fältnamnen" — raden ovan, skriven av mig — är osund, och det är uppmätt: med keep-regeln
borttagen och varje fält bevisat omdöpt fanns `contact`, `deviceModel` och `createdAtMs`
fortfarande som strängar i `classes.dex`. De kommer från annat: `FeedbackDocument.kt` skriver
samma namn som handskrivna literaler, och `createdAtMs` är dessutom ett Room-kolumnnamn i det
keepade `data.db.entity`. En grep svarar grönt på en trasig release. `mapping.txt` svarar exakt.

**Invarianten är skarpare än "räkna upp paket".** Gson läser aldrig ett klass*namn* — den
reflekterar över *fält*. Ett reflektionsserialiserat fält är säkert i release om **antingen**
det bär `@SerializedName` (ett strängkonstant R8 inte kan döpa om) **eller** dess klass ligger
under en keep-regel som bevarar `<fields>`. Allt under `data.api.model` är säkert via det första
och behöver därför inte stå på någon lista; `FeedbackPayload` bär ingen annotering alls och
hänger helt på det andra. Det är skillnaden mellan de två som gör regeln bärande, inte paketet.

**Åtgärd.** Svaret är medvetet tvedelat, för att ingen halva räcker. Filen
`app/release-reflection-contract.txt` namnger de typer vilkas fältnamn är ett trådkontrakt, i
**en** fil eftersom två
kontroller måste läsa samma lista: `ReleaseReflectionContractTest` (4 tester, på JVM) kontrollerar
*orsaken* — det statiskt synliga — och Gradle-uppgiften `verifyReleaseReflectionContract`, som
`assembleRelease` och `bundleRelease` är `finalizedBy`, kontrollerar *verkan* i R8:s `mapping.txt`
efter `minifyReleaseWithR8`. Bara den senare kan faila av den verkliga orsaken, och den kan inte
köras på JVM.

En detalj som måste stå skriven: R8 loggar **bara omdöpningar**. Ett bevarat fält har ingen rad
alls, så grönt är *frånvaro* och det som failar är att raden finns. Klassnamnet kontrolleras
inte — en omdöpt klass med bevarade fält är korrekt för Gson och ska inte faila.

Nyckelmängdstesterna revisionen också bad om finns redan och dubblerades inte:
`FeedbackPayloadTest.kt:31` och `FeedbackDocumentTest.kt:41` spärrar båda den exakta
nyckelmängden. De fångar ett glömt fält, aldrig en saknad keep-regel — på JVM finns ingen
keep-regel att sakna. Det står i testets KDoc så att ingen lägger till dem igen.

**Spärrar:**

| Spärr | Vad den spärrar |
|---|---|
| `every type whose field names are a wire contract is under a keep rule that preserves them` | Varje kontraktstyp täcks av en regel som bevarar `<fields>` |
| `the rules these tests read are the rules R8 is given` | `proguardFiles` namnger verkligen regelfilen, och release minifierar fortfarande |
| `no type reaches Gson through a route nobody checked` | Nytt `toJson`/`fromJson`/`@Body`/konverterare i en fil ingen granskat |
| `the contract names types that exist and really do need the rule` | Kontraktstypen finns kvar, och är inte redan omdöpningssäker via `@SerializedName` |
| `verifyReleaseReflectionContract` (Gradle, efter R8) | Ett kontrakterat fält faktiskt omdöpt i `mapping.txt` |

**Bevis att spärrarna kan gå sönder.** Tio avsiktliga mutationer, en åt gången, varje gång
återställd:

| Mutation | Utfall |
|---|---|
| keep-regeln för `FeedbackPayload` borttagen, bygget i sitt gamla läge | **hela sviten grön** — RED-beviset för fyndet; release-sidan failar och namnger alla sex fälten |
| keep-regeln utkommenterad i stället för borttagen | JVM-spärr 1, ensam |
| regeln pekad på ett närliggande men fel paket | JVM-spärr 1, ensam |
| en andra kontraktstyp utan egen regel | JVM-spärr 1, ensam |
| kontraktet namnger en typ som inte finns | JVM-spärr 4, ensam |
| fälten annoterade med fullt kvalificerat `@com.google.gson.annotations.SerializedName` | JVM-spärr 4, ensam |
| nytt `toJson`-anrop i `FeedbackDocument.kt` | JVM-spärr 3, ensam |
| `proguardFiles` pekad på en omdöpt regelfil | JVM-spärr 2, ensam |
| `isMinifyEnabled = false` (plus `isShrinkResources = false`, se nedan) | JVM-spärr 2, ensam |
| kontraktsfilen tömd | rött på båda nivåerna |

`isMinifyEnabled = false` ensamt når inte fram till testet — AGP vägrar vid konfiguration
("Removing unused resources requires unused code shrinking to be turned on"). Mutationen kördes
därför om med båda flaggorna av, och då failar spärren som avsett.

**Mutationerna hittade fyra hål i spärrarna själva**, alla rättade i samma commit. Två av dem är
samma klass av defekt som fynd 3:s saknade `inputs.dir`:

- `testDebugUnitTest` var **UP-TO-DATE** efter att en keep-regel raderats, och **FROM-CACHE** efter
  en källändring. En fil som läses som *data* är ingen deklarerad indata, så spärren kördes inte
  alls och rapporterade grönt. Fyra `inputs.file`/`inputs.dir` tillagda.
- Regexen för keep-regler matchade `-keep` inne i en **utkommenterad** regel. `filterNot` på
  träffen testade träffen, som börjar vid `-keep`, inte raden. Kommentarer strippas nu först.
- `proguardFiles`-kontrollen var uppfylld av **sin egen ställning**: den sökte hela byggfilen
  efter regelfilens namn och hittade `inputs.file(…"proguard-rules.pro")`-raden som lagts till för
  just det här testet. Läser nu argumentlistan med balanserade parenteser.
- `Regex.escape` sveper in mönstret i `\Q…\E` i stället för att backsläsa varje metatecken, så
  ersättningskedjan över stjärnorna träffade aldrig något och **inget wildcard-mönster matchade
  någonting alls**. Den exakta regeln på kontraktet har inga wildcards, så buggen syntes först
  när en paketbred regel prövades. Översatt tecken för tecken nu.

**Det som inte täcks, uttryckligen.** Ett hål, uppmätt: en **för bred** keep-regel fångas inte.
`-keep class se.optiqon.voice.domain.** { <fields>; }` — precis det revisionen sa att man inte
skulle göra — passerar båda nivåerna. Båda kontrollerar att fältnamnen *överlever*; ingen av dem
kan skilja en nödvändig keep från en onödig. Felmeddelandena säger det åt läsaren, och
kommentaren i regelfilen står kvar, men det är granskning, inte en spärr.

**Vad som medvetet lämnades.** `REFLECTION_SITES` är handskriven av samma skäl som bildlistan i
fynd 3: att härleda Kotlin-typer ur källtext pålitligt går inte, och en lista som *måste*
redigeras failar åt båda hållen. Kontraktet spärrar inte heller vilka fält en typ har — det
gör nyckelmängdstesterna.

**Effort:** utfört. **Regressionsrisk:** ingen i produktionskod — ingen produktionsfil ändrades.
Hela sviten: **378 tester, 0 fel, 2 hoppade** (var 374). `assembleRelease` grön med den nya
uppgiften körd. Commit `3975e05`.

---

### 5. `stopRecordingAndWait()` väntar inte, och anroparen avbryter flushen ✅ STÄNGT 2026-09-10

**Evidens (som den löd vid revisionen).** `BubbleService.kt:685-692` stoppar recordern och
avbryter tre jobb, med kommentaren *"Don't cancel recordingJob — let it finish flushing the
file"* — men funktionen returnerar direkt. Den enda anroparen är `onDestroy()` (`:293-297`),
som två rader senare kör `scope.cancel()`. `recordingJob` är startat i just den scopen (`:626`).

**Allvarlighet / sannolikhet:** medel / medel. **Faktisk defekt, men liten yta.**

**Bekräftad — och revisionen hade fel om vad som förlorades.** Defekten är verklig, men
"avbryter flushen" är inte den. `AudioRecorder.record()` skriver genom en **obuffrad**
`FileOutputStream` inne i `use { }`, så byten ligger redan på disk. Det `scope.cancel()` förstör
är **bevarandet**: ingen PCM→WAV-konvertering, ingen `preserveInterrupted`, ingen historikrad,
inget meddelande. Två följder som revisionen inte nämnde: PCM-filen lämnas kvar i `cacheDir`, och
**ljudfokus släpps aldrig** — användarens musik ligger nere tills något annat råkar ta fokus.
Namnet `…AndWait` var det dyraste: det fick raden att passera granskning.

**Uppmätt RED före fix.** Med produktionskoden i sitt defekta läge (bevarandet startat i
tjänstens egen scope): **5 av 6 nya tester faller**, och det som faller är att ingenting alls når
bevarandet — inte ens PCM:en städas. Den sjätte, källkodsspärren, passerade rätt: inkopplingen var
redan rätt, beteendet var fel.

**Åtgärd.** Nedrivningen är lyft till `service/UnfinishedRecording.kt`:

- `UnfinishedRecording` — vad som återstår av inspelningen, hämtat innan fälten nollas.
- `cancelKeepingRecording(serviceScope, survivingScope, unfinished)` — lämnar över och avbeställer
  sedan tjänstens scope. `onDestroy` har inget eget `scope.cancel()` kvar.
- Den överlevande scopen är **applikationens** `@Singleton CoroutineScope` som redan fanns i
  DI-grafen (`di/AccessModule.kt:60-63`), nu injicerad i `BubbleService`.
- Kroppen kör under `NonCancellable` och `join`:ar inspelningskoroutinen genom
  `runCatchingCancellable` (fynd 2:s lärdom), inte `runCatching`.
- Den dubblerade bevarandesvansen i `stopRecordingAndPreserve(reason)` är borta — båda vägarna
  går genom samma funktion. Två kopior är hur en av dem blir avbrytbar igen.
- `abandonRecordingAudioFocus()` sker **synkront** i `takeUnfinishedRecording()`, medan tjänsten
  fortfarande finns — scopen som annars skulle bära det är den som avbeställs.

Användaren får nu en `FAILURE`-rad med `RecordingStoppedException`:s text, och ljudet behålls
enligt hens egna historik- och retentionsinställningar.

**Spärrar.** `app/src/test/java/se/optiqon/voice/service/RecordingTeardownTest.kt`, 6 tester.
Fixturen modellerar recordern som den faktiskt beter sig — obuffrad ström i `use { }` som skriver
sin sista buffert på väg ut — och kör nedrivningen på en enkeltrådad "Main"-dispatcher, precis
som `onDestroy`. Det är vad som gör den gamla defekten deterministisk i stället för en kapplöpning.

| Test | Vad det håller fast |
| --- | --- |
| *a recording outlives the service that was carrying it* | ljudet **och** längden når bevarandet |
| *the recorder is allowed to finish writing before the file is read* | `join`:en — inspelningens sista buffert finns med |
| *the working files go and the recorder is let go of* | PCM + WAV raderas, `onDone` exakt en gång |
| *nothing recorded is not an error, and still cleans up* | tom inspelning blir ingen historikrad |
| *a cancellation arriving mid-preserve does not lose the audio* | `NonCancellable` |
| *the service really does route its destroy through this teardown* | inkopplingen: `onDestroy`, scope-valet, ljudfokus, `@Singleton`-scopen |

`BubbleService` kan inte drivas här — `@AndroidEntryPoint`-Service med `WindowManager`-overlays
och riktig `AudioRecord`, och modulen har inget `hilt-android-testing`. Därför är hälften en
källkodsspärr. Ingen av hälfterna räcker: beteendetesterna skulle passera mot en funktion ingen
anropar, och källkodsspärren mot en funktion som tappar ljudet.

**Mutationer — en i taget, var och en återställd.**

| # | Mutation | Utfall |
| --- | --- | --- |
| 1 | bevarandet startas i tjänstens scope (= det gamla läget) | **5 av 6 faller** |
| 2 | `recordingJob?.join()` tas bort | 2 faller (svansen försvinner) |
| 3 | `NonCancellable` → `EmptyCoroutineContext` | 1 faller |
| 4 | avbeställ scopen **före** överlämningen | **ÖVERLEVDE** — se nedan |
| 5 | `runCatchingCancellable` → `runCatching` | 1 faller (fynd 2:s spärr) |
| 6 | `pcm.length() > 0L` tas bort | 1 faller |
| 7 | `pcm?.delete()` tas bort | 2 faller |
| 8 | `onDestroy` skickar `scope` som överlevande scope | 1 faller (källkodsspärren) |
| 9 | `onDestroy` får tillbaka sitt eget `scope.cancel()` | 1 faller |
| 10 | längden tappas på väg in (`0L`) | 1 faller |
| 11 | `abandonRecordingAudioFocus()` tas bort | **ÖVERLEVDE först** — se nedan |

**Två mutationer överlevde, och båda ändrade något.**

- **Nr 4 är nu ett dokumenterat icke-krav.** Att byta plats på överlämningen och `serviceScope
  .cancel()` håller hela sviten grön, eftersom inspelningskoroutinen `join`:as ändå. Ordningen
  är alltså **inte** det som bär fixen — scope-valet är det. KDoc:en påstod ordningen, och är
  rättad: mätningen falsifierade den egna kommentaren.
- **Nr 11 blev en ny spärr.** Att ta bort `abandonRecordingAudioFocus()` höll varje
  beteendetest grönt — ljudfokus vilade på ingenting. `AudioManager` inne i en Service modulen
  inte kan instansiera går inte att driva här, så spärren läser källan. Den lades till **medan
  mutationen satt kvar**, så dess RED är uppmätt, inte antagen.

**Det som inte täcks, uttryckligen.**

- `onDestroy` i sin helhet, och hela tjänstens livscykel. Mellanlagret mellan
  `cancelKeepingRecording` och en verklig systemstopp är oprövat.
- Att `preserveInterrupted` faktiskt skriver raden — `preserve`-kroken är en parameter här.
  `TranscriptionManager` returnerar dessutom tidigt och behåller **ingenting** när
  `!historyEnabled && retryEntryId == null`; med historiken av är tystnaden avsiktlig, och det
  är inte den här fixens sak att ändra.
- `RecordingStoppedException`:s text är teknisk engelska, som övriga undantag i kodbasen, och
  hamnar som den är på en historikrad användaren ser. Inte lokaliserad. Samma sak gäller
  `AccessRevokedException`, så detta är konsekvent snarare än nytt.
- **Nedstängning under transkribering tappade fortfarande ljudet** — uppmätt, uppskrivet som
  fynd 9, och stängt 2026-09-10 på samma sätt.

**Vad som medvetet lämnades.** En `SurvivingScope`-wrapper som hade gjort det till ett
kompileringsfel att skicka tjänstens egen scope övervägdes och avvisades: den flyttar bara
frågan till var wrappern konstrueras, och kostar en typ i produktionskoden för att ersätta en
spärr som redan finns. `takeUnfinishedRecording()` är fortfarande privat i en klass som bär för
mycket — det är fynd 7, inte det här.

**Effort:** utfört. **Regressionsrisk:** medel — den rör nedstängningsvägen för mikrofonen, som
revisionen varnade för. Hela sviten: **384 tester, 0 fel, 2 hoppade** (var 378). Commitar
`9147b6e` och `fd847f9`.

---

### 9. Nedstängning under transkribering tappar ljudet på exakt samma sätt ✅ STÄNGT 2026-09-10

**Nytt fynd, uppmätt under arbetet med fynd 5.** `onDestroy` är nu säker medan tillståndet är
`Recording`. Det är det inte medan det är `Transcribing` eller `PostProcessing`.
`takeUnfinishedRecording()` returnerar `null` i de lägena, och `cancelKeepingRecording` avbeställer
scopen som äger `transcriptionJob`. Koroutinens `catch (e: CancellationException)` läser
`processingStoppedBy`, som är `null` när det är en nedstängning och inte ett återkallat tillstånd
— alltså bevaras ingenting — och `finally` raderar WAV:en. Samma tysta förlust som fynd 5, på en
annan väg.

**Allvarlighet / sannolikhet:** medel / låg-medel. Fönstret är kortare än en inspelning, men allt
användaren sa ligger i det.

**Varför det inte åtgärdades här.** WAV:en finns bara som lokal variabel inne i
`transcriptionJob`. Att låta någon utanför behålla den kräver att huvudflödet — den lyckade
transkriberingsvägen — byggs om, vilket är en annan ändring än fynd 5 och bör bedömas som en
sådan. Att göra det "på vägen" hade gjort fynd 5:s fix omöjlig att granska.

**Billigaste tillräckliga verifiering.** Samma söm som fynd 5 fick, fast på den vägen: lämna
WAV:en och `durationMs` till en överlevande scope när nedstängningen kommer, och pröva det med
samma fixtur.

---

#### Vad som gjordes

**Det svåra var inte bevarandet utan äganderätten.** WAV:en raderas av `finally` i exakt den
koroutin som nedstängningen avbeställer. Ett flaggvärde hade krävt att två sidor kommer överens,
på vilka trådar de nu råkar ligga. I stället **döps filen om** ut ur den döende koroutinens
räckhåll (`takeAudioFromCancelledJob` i `UnfinishedRecording.kt`): efteråt är sökvägen den
känner tom, och ingen behöver komma överens om någonting. Båda sökvägarna ligger i `cacheDir`,
så det är en katalogoperation och ingen kopiering; `copyTo` bakom `IOException` finns bara för
fallet som inte kan inträffa, eftersom att tappa en diktering är värre än att kopiera några
hundra kilobyte.

`preserveUnfinishedRecording` grenar nu på **vilken sorts** oavslutad inspelning den fick:
finns det PCM är vägen byte för byte lika sträng som förut, annars är WAV:en det användaren sa.
Tjänsten skriver ned var WAV:en ligger så snart den finns (`processingWav`,
`processingDurationMs`), `takeUnfinishedRecording()` växlar på tillståndet i stället för att
bara hantera `Recording`, och jobbets `finally` slutar peka på en fil den är på väg att radera.

**RED uppmätt genom produktionskod innan grenen fanns:** `IllegalArgumentException: Nothing was
ever handed to preservation; the user's audio was dropped silently.` — medan de fem äldre
beteendetesterna och källspärren förblev gröna.

**Mutationsbevis: nio mutationer, en i taget, var och en återställd.** Sju dog direkt:
nya grenen bort, `processingWav = wavFile` bort, `Transcribing`-växlingen bort, `durationMs`
→ `0L`, fynd 5:s `pcm.length() > 0L`, dess `join()` och dess `NonCancellable` — de tre sista
bevisar att den nya `when`-formen inte luckrade upp fynd 5:s fix.

**Två mutationer överlevde, och båda var testets fel, inte kodens.**

- Att lämna över ljudet **utan** att flytta det överlevde: bevarandet hann läsa bytes innan den
  avbeställda förfrågans `finally` raderade dem. Testet mätte den tursamma ordningen av två
  koroutiner. Fixturen väntar nu tills städningen har körts — värsta fallet, inte det vanliga.
- En **tom** arbetsfil som lämnas över som om den vore en diktering överlevde också; ingenting
  nådde den spärren. Ett eget test hävdar nu att överlämningen både rapporterar ingenting och
  *gör* ingenting, för en misslyckad överlämning med effekt är det sämre av de två.

Efter det dör alla nio.

**Uttryckligen otäckt.** `copyTo`-grenen: den kan bara nås om en omdöpning inom samma katalog
misslyckas, och då är filsystemet i ett läge testet inte kan framkalla utan att ljuga om det.
`onDestroy` i sin helhet och tjänstens verkliga livscykel är fortfarande oprövade — samma gräns
som fynd 5 drog, av samma skäl (ingen `hilt-android-testing` i modulen).

**Effort:** utfört (en halv dag, inte den uppskattade dagen — sömmen från fynd 5 bar det mesta).
**Regressionsrisk:** uppskattad hög eftersom den rör huvudflödet; det den faktiskt lade i
huvudflödet är två fälttilldelningar och en nollning. Hela sviten: **386 tester, 0 fel, 2
hoppade** (var 384). Commitar `7a25616` och `14dbad1`.

---

## Fix when touched

### 6. Två tjänster kopplade genom processglobala `companion object`-fält ✅ STÄNGT 2026-09-10 (F15 rättad; enhetsverifiering kvarstår hos Lars)

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

#### Vad som gjordes

**Rekommendationen följdes, båda halvorna.** `KeyboardLink` är registret som en enda ägd punkt,
och återanslutningen sitter nu i `onServiceConnected()` — där tjänsten faktiskt kommer tillbaka.

**Felet var ägandeskap, inte globalitet.** Två tjänster med skilda livscykler måste mötas
någonstans, och ett processglobalt fält är det enda Android erbjuder dem. Det som gick sönder var
att *ingen ägde* mötesplatsen: vem som helst med en referens kunde skriva den, och
`TextInjectorService.onDestroy()` gjorde det — nollade den lyssnare som
`BubbleService.onStartCommand()` hade registrerat, och lämnade en levande bubbla döv utan att någon
kod någonstans skulle registrera den igen. Det är F15.

Nu tillhör registreringen den som gjorde den: `listen()` och `stopListening()` är bubblans, och
ingenting tillgänglighetstjänsten gör rör dem. Vad tillgänglighetstjänsten äger är *tangentbordet*
— den är det enda som kan se fönsterlistan — så `accessibilityConnected()` och
`accessibilityGone()` handlar om tangentbordet, aldrig om vem som lyssnar efter det.
`stopListening()` tar dessutom lyssnaren som argument och nollar bara om den fortfarande är den
registrerade: `START_STICKY`-omstarter överlappar, så den avgående instansens `onDestroy()` kan
köra efter att efterträdaren registrerat sig, och ovillkorlig nollning där hade återskapat F15 med
tjänsterna ombytta. Det är mutation 5.

**Livscykelmatrisen renderas i test**, precis som den billigaste tillräckliga verifieringen sade:
`KeyboardLinkLifecycleTest` river och återansluter tillgänglighetstjänsten utan att röra
`BubbleService` och hävdar att bubblan fortfarande får tangentbordshändelser. Dessutom: ett
tangentbord kan inte stå uppe medan tjänsten som ser det är borta, återkomst säger sanningen
istället för att lita på cachen, ingen lyssnare är inte ett fel, och rapportregeln från
`KeyboardVisibilityReportTest` gäller fortfarande.

**RED uppmätt:** 5 av 11 tester faller mot den gamla formen. Fyra av dem är kopplingsspärrar som
läser källan — ingen enhetstest här kan starta någon av tjänsterna (den ena är en
`@AndroidEntryPoint` som lägger overlays på skärmen, och modulen saknar `hilt-android-testing`), så
att tjänsterna verkligen går genom den ägda punkten måste läsas, inte köras. **Tio mutationer, en i
taget, var och en återställd:** F15 själv återinförd (avgången nollar registreringen), återkomsten
tystad, `accessibilityGone()` tömd, `accessibilityGone()` utan besked till lyssnaren, ovillkorlig
nollning vid avregistrering, avregistrering utan verkan, återkomst som sätter flaggan men inget
säger, rapportregeln slopad, och bubblan som varken registrerar eller avregistrerar sig. **Alla tio
dör, var och en på exakt den spärr den riktar sig mot.**

**Vad detta inte bevisar.** Att det fungerar på enhet. Testerna spelar matrisen mot länken, inte
mot Android: att `onServiceConnected()` verkligen körs när användaren slår på tjänsten igen, och
att `windows` då svarar rätt, kan bara en enhet visa. **Det steget kvarstår hos Lars** — slå av och
på tillgänglighetstjänsten, tryck i ett textfält, se att bubblan kommer.

**De två återstående companion-fälten rördes inte.** `instance` läses genom `TextInjectionBridge`,
som redan är en ägd söm, och `focusedAppPackage` läses bara inifrån `TextInjectorService` självt.
Ingen av dem har F15:s form — ingen annan tjänst nollar dem — så de bröts inte ut på spekulation.

**Effort:** utfört (uppskattat en dag; blev mindre, eftersom bara en av fyra fält faktiskt bar
defekten). **Regressionsrisk:** medel kvarstår till enhetsverifieringen är gjord. Hela sviten:
**400 tester, 0 fel, 2 hoppade** (var 389). Commit `2b4c61a`.

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

### 8. `BubbleService.isRunning` skrivs på två ställen och läses ingenstans ❌ FELAKTIGT FYND — rättat och stängt 2026-09-10

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

#### Rättelse 2026-09-10: evidensen var falsk, och rekommendationen var farlig

**Fältet läses.** Inte direkt, men dess `private set` var den **enda skrivaren** av
`_runningState`, och `runningState` kollas av `HomeScreen.kt:108`. Den flaggan är vad hjälten på
hemskärmen säger (*"The bubble is on"* / *"off"*) och vad som avgör om dess knapp startar
tjänsten eller stoppar den (`HomeScreen.kt:136`). Sökningen som gjordes — läsare av `isRunning` —
kunde inte se det, eftersom kopplingen går genom setteren.

**Att följa rekommendationen hade gått sönder.** Utan skrivaren fryser `runningState` på `false`:
hemskärmen påstår att bubblan är av medan den är på, och knappen erbjuder sig att starta en andra
tjänst. Det är värre än det döda fält fyndet trodde att det beskrev.

**Vad som gjordes i stället — motsatsen till rekommendationen.** Faktumet behölls, dubbletten
togs bort: `setRunning()` är enda skrivaren, flödet enda innehavaren, och ett `var` som *ser*
oläst ut går inte längre att ta bort för sig. Flaggan säger att *tjänsten* lever, inte att
bubblan syns — de kan glida isär, vilket är fynd 6, och det står nu i KDoc:en så att nästa läsare
inte frågar flaggan om fel sak.

**Spärr.** `ServiceRunningStateTest` läser källan för det ingen enhetstest här kan driva (tjänsten
är en `@AndroidEntryPoint` som lägger overlays på skärmen): `onCreate` publicerar `true`,
`onDestroy` publicerar `false`, inget andra föränderligt fält håller samma sak, och tillståndet har
exakt en skrivare. Plus ett beteendetest: ingenting är igång innan en tjänst har sagt det.

**RED uppmätt mot den gamla formen** — två av de tre testerna faller där (det mättes av misstag,
genom att en återställning under mutationsarbetet tog refaktoreringen med sig, och det var en
bättre mätning än den jag hade planerat). **Fem mutationer, en i taget, var och en återställd:**
`setRunning(true)` bort, `setRunning(false)` bort, ett andra `@Volatile var` tillbaka,
begynnelsevärdet `true`, och en andra skrivare insmugen i `onStartCommand`. Alla fem dör, var och
en på exakt den spärr den riktar sig mot.

**Lärdomen om revisionen själv.** "Noll läsare" ur en textsökning är inte samma sak som död kod
när kopplingen går genom en setter eller en `StateFlow`. Det här var det enda av de nio fynden
vars evidens inte höll — de övriga åtta stämde vid mätning.

**Effort:** utfört (minuter, som uppskattat — men innehållet blev ett annat).
**Regressionsrisk:** ingen ny: en fälttilldelning blev ett funktionsanrop, och hemskärmens
läsväg är oförändrad. Hela sviten: **389 tester, 0 fel, 2 hoppade** (var 386). Commit `73e14a6`.

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
