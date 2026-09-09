# Obevakad nattkörning — OPTIQON Voice, mot betaskick

**Datum:** 2026-09-09 · **Autonomi:** A4 inom fyra namngivna scope · **Risk:** Gate
**Gren:** `claude/firebase-activation-gate-m1-iy96xc` @ `f358dfe`
**Mål:** flytta appen så nära "kan lämnas till betatestare" som går utan Lars.

---

## 0. Globala regler för obevakat läge

Gäller alla fyra scope och har företräde framför varje enskilt scope.

### Aldrig, under några omständigheter

- Mata in API-nycklar, lösenord, PIN eller andra inloggningsuppgifter i något fält.
- Ändra säkerhets- eller systeminställningar: tillgänglighetstjänst, overlay-behörighet,
  utvecklarinställningar. (adb är ändå oprivilegierat här — `settings put` kastar SecurityException.)
- `am force-stop se.optiqon.voice` — F13 visar att det slår av tillgänglighetstjänsten, och att
  slå på den igen är ett mänskligt steg. Ett force-stop skulle döda hela nattens UI-arbete.
- Avinstallera, rensa data, installera äldre APK, rulla tillbaka destruktivt.
- Signeringsrotation, nyckelceremoni, ny permanent nyckel, distribution.
- Merge av PR — **Lars mergar, jag mergar aldrig i detta projekt.**
- Live-skrivningar mot Firestore: inga `--apply`, ingen `firebase deploy`, ingen regeldeploy,
  ingen SHA-registrering, ingen providerkonfiguration, ingen Cloudflare.
- Radera testarens Auth-användare eller dokument.
- Bekräfta Android-behörighets- eller samtyckesdialoger.

### Alltid

- Allt arbete committas och pushas löpande på grindgrenen. Ingen merge, inga PR-ändringar.
- Löpande logg i `docs/gate-m1/NIGHT_2026-09-09.md` — vad som gjordes, vad som bevisades,
  vad som inte bevisades. Ett stopp är ett resultat, inte ett misslyckande.
- **Kostnadstak: högst sex riktiga dikteringar totalt** under hela natten, över alla scope.
  Varje diktering räknas och redovisas.
- Vid stopp inom ett scope: avsluta scopet, skriv ned exakt var det tog stopp och varför,
  och **gå vidare till nästa scope**. Aldrig improvisera runt ett stopp.
- Tappas enheten (låst skärm, adb offline, urladdad): allt UI-arbete upphör omedelbart och
  arbetet fortsätter i Scope 4, som inte behöver hårdvara.
- Ingen ny bred planreview, ingen scope-utvidgning. Det som inte står här görs inte.

### Stoppvillkor som avslutar hela natten

Oväntad dataförlust, identitetsmismatch, obehörig åtkomst, fel projekt eller fel enhet, en
krasch som inte går att återställa utan mänskligt steg, eller varje avvikelse som skulle kräva
en av punkterna under "Aldrig". Då stannar allt och rapporten skrivs.

---

## Scope 1 — F19: nyckeln ska följa med (stänger även G3 steg 10)

**Varför först:** det är Lars uttryckliga önskemål, det är den sista blockeraren för steg 10:s
UI-halva, och — avgörande för en obevakad natt — **fixen gör att onboardingen kan slutföras utan
att någon nyckel matas in**, vilket är det enda som hindrat mig från att stänga steget själv.

**Arbete**
1. Läs `OnboardingViewModel`, `SecurePreferencesStore`, `StorageOwnership` igen och fastställ
   exakt var förifyllningen hör hemma.
2. RED: enhetstest som visar att en befintlig verifierad nyckel i aktuell rot inte når
   `OnboardingUiState.apiKey` och att `canVerify` är falskt.
3. GREEN: förifyll fältet från aktuell rot; låt en befintlig verifierad nyckel uppfylla
   `canVerify` / `canLeaveConnectStep` utan ny provider-runda.
4. `./gradlew :app:testDebugUnitTest` grön.
5. Bygg och `adb install -r` på `01132f9f` (samma debugnyckel, uppdatering på plats).
6. Verifiera i UI: gå igenom onboardingen utan att skriva något, nå hemskärmen, bekräfta att
   de 5 dikteringarna syns och att digesten fortfarande är `4D2AE1E8…2FF47D3`.
7. Skriv in steg 10 som **PASS** i resultattabellen om och endast om det faktiskt syns.

**Stopp om:** fixen kräver en Room-migrering, en regeländring, eller att en provider-runda körs
för att verifiera en redan lagrad nyckel. Då dokumenteras förslaget och scopet avslutas.

**Kostnad:** noll dikteringar.

---

## Scope 2 — F14 och F18: de två som drabbar en testare direkt

**F14 — blockorsaken visas aldrig.** `blockedMessage(reason)` beräknas och kastas bort;
`BubbleView` ritar bara ikoner. En testare vars nåd löpt ut ser en röd bubbla utan förklaring.

**F18 — arbetsprofil ser ut att fungera men gör det inte.** Tal och API-kostnad hamnar tyst på
det andra kontot i telefonen. Måste minst varnas för, helst blockeras vid start.

**Arbete**
1. F14: ytdra orsaken i UI:t på den väg som redan finns (`showError`/Toast eller motsvarande),
   med test som binder orsak till visad text.
2. F18: upptäck att processen kör i en hanterad profil och visa en tydlig, blockerande text.
   Rent klientsidigt, ingen behörighet som kräver godkännande.
3. Enhetstester gröna, bygg, `adb install -r`.
4. Verifiera F14 på enheten via `CLOCK_OFFSET`-hooken (nåden löper ut → texten ska synas), och
   återställ `CLOCK_OFFSET 0` och `FAIL_REFRESH false` efteråt — och **verifiera** återställningen.
5. Verifiera F18-skyddet genom att starta appen för user 10 och läsa av skärmen.

**Stopp om:** F18-upptäckten kräver en ny behörighet i manifestet som Android ber användaren om,
eller om F14 inte går att visa utan att röra bubblans tillståndsmaskin i grunden.

**Kostnad:** noll dikteringar.

---

## Scope 3 — Feedbackkanalen, byggd men inte skarpsatt

Det som gör en beta värd att köra. Kön, ägarskapsregeln och felhanteringen finns redan och är
testade; `OutboxSender` är ett tomt gränssnitt och det finns ingen nyttolast och ingen knapp.

**Arbete**
1. Nyttolast och serialisering för ett feedbackmeddelande, lagd på den **befintliga**
   outbox-tabellen.
2. En `OutboxSender`-implementation som svarar på den enda fråga gränssnittet ställer: är felet
   permanent?
3. En enkel feedbackyta i appen — text, valfri kontaktuppgift, ingen dikteringstext och inget
   ljud som bifogas automatiskt.
4. Regler: **skrivs som förslag i en separat fil, aldrig i `firestore.rules` och aldrig
   deployad.** `firebase.json` namnger bara `firestore.rules`, så en förslagsfil kan inte gå
   live av misstag.
5. Regeltester mot den lokala emulatorn (`npm run test:rules` — emulator, inte skarpt projekt)
   som visar vad förslaget öppnar och vad det stänger.
6. Enhetstester för nyttolast och sändare.

**Stopp om:** en Room-migrering krävs (outbox-tabellen visar sig inte vara generisk nog), eller
om något steg skulle kräva en skarp regeldeploy för att kunna verifieras. Då byggs det som går,
och resten dokumenteras som ett paket för Lars.

**Kostnad:** noll dikteringar. Ingen skarp skrivning — sändaren körs bara mot emulator eller fake.

---

## Scope 4 — Analys utan hårdvara (alltid tillgänglig, aldrig blockerad)

Detta scope är också reservläget: tappas telefonen fortsätter natten här.

1. **F15-rotorsaksjakt i källan.** Bubblan som försvinner medan båda tjänsterna lever. Läs
   `BubbleService`, `TextInjectorService`, `FanMenuController` och fönsterhanteringen; leta efter
   den väg där överlägget tas bort utan att tillståndet ändras. **Ingen fix installeras utan att
   den kan verifieras** — och verifiering kräver troligen att tillgänglighetstjänsten slås av och
   på, vilket är Lars steg. Resultatet är en namngiven hypotes med filrader, inte en blind fix.
2. **Skuldrevisionen ur `BACKLOG.md`** — 5–10 evidensbaserade fynd, rankade, i de grupper
   backloggen redan definierar. Ren läsning, inga ändringar.

**Kostnad:** noll dikteringar, noll enhetsrisk.

---

## Vad Lars måste göra innan han lämnar datorn

1. **Slå på "Stay awake" igen** i Utvecklarinställningar. Just nu är
   `mStayOnWhilePluggedInSetting=0`, vilket betyder att skärmen släcks och låset går på — och då
   dör allt UI-arbete i Scope 1 och 2. Detta är den enda tekniskt nödvändiga åtgärden.
2. Lämna telefonen **upplåst och ansluten** med USB. Batteriet står på 36 % och laddar.
3. Godkänna vilka av de fyra scopen som gäller.

Inget annat behövs. Alla fyra scope är konstruerade så att de går i mål eller stannar av sig
själva utan att någon behöver svara på något.

---

## Vad natten inte kan leverera

- Ingen betanyckel, ingen signering, ingen distribution — G4 kräver en oåterkallelig
  nyckelceremoni med egen godkännanderuta.
- Ingen skarp regeldeploy, så feedbackkanalen blir byggd och emulatortestad, inte live.
- Steg 2:s e-postlänkshalva förblir obevisad: den kräver SHA-registrering i Firebase-konsolen.
- F15 blir sannolikt diagnos, inte fix.
- Inga nya beslut i D2, D8, distributionskanal, integritetstext eller nyckelmodell — de är Lars.
