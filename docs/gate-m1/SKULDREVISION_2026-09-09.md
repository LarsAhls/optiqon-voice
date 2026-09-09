# Skuldrevision — OPTIQON Voice, 2026-09-09/10

Backloggens post *"Vibe-code technical debt audit"* genomförd som Scope 4 i nattkörningen.
Ren läsning av `app/src/main` plus testträdet och byggkonfigurationen. Inga arkitektur-
ändringar, ingen upprensning för sin egen skull. Ett fynd (nr 4) var en defekt i kod som
skrevs samma natt och rättades direkt; allt annat är oförändrat.

Rankningen är efter **förväntad framtida kostnad**, inte efter hur illa koden ser ut.

---

## Fix now

### 1. Åtta schemaversioner, sju handskrivna migrationer, noll migrationstester

**Evidens.** `OptiqonVoiceDatabase.kt:27` står på `version = 8`. `DatabaseModule.kt:25-192`
innehåller `migration1To2` … `migration7To8`, alla handskrivna SQL. `app/schemas/` har alla åtta
JSON-scheman exporterade. `room.testing` är redan deklarerad (`app/build.gradle.kts:309`).
Ingenting i `app/src/test` nämner `MigrationTestHelper`, och `app/src/androidTest` finns inte.

**Allvarlighet / sannolikhet:** hög / medel. **Faktisk defekt eller latent risk:** latent risk.

**Påverkan.** Appen ligger redan på riktiga testenheter med riktig data — enheten i natt hade
tolv dikteringar, profiler och prompts. En felaktig migration tappar den historiken, och Room
kastar dessutom appen i ett tillstånd användaren inte kan ta sig ur utan att avinstallera. Det
är exakt den skada som inte går att ångra i efterhand.

**Billigaste tillräckliga verifiering.** `MigrationTestHelper` mot de åtta exporterade
schemana: skapa v1, migrera hela vägen till v8, läs tillbaka en rad per tabell. Robolectric
räcker, ingen enhet behövs. Det är just för att schemana redan är exporterade som testet är
billigt.

**Rekommendation.** Bygg den. **Effort:** en halv dag. **Regressionsrisk:** ingen — testet rör
ingen produktionskod.

### 2. `runCatching` sväljer `CancellationException` och räknar avbrott som serverfel

**Evidens.** `AccessRefresher.kt:84`:

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

**Billigaste tillräckliga verifiering.** Ett enhetstest som avbryter den anropande coroutinen
under `refresh()` och hävdar att `consecutiveFailures` inte ökade.

**Rekommendation.** Byt till `try/catch` med `catch (c: CancellationException) { throw c }`
före den breda grenen, på alla tre ställena. **Effort:** en timme. **Regressionsrisk:** låg;
den ändrar bara vägen där anroparen redan är på väg bort.

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
