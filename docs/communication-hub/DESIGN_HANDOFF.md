# OPTIQON Voice — avlämning till Claude Design

**Version:** Discovery 1.0, 2026-09-07. **Status:** Redo för designarbete och Lars granskning. Ingen implementation eller publicering är godkänd.

## Uppdrag och beslutad riktning

Utforma en sammanhängande, premiumpräglad och mobilvänlig kommunikationsfunktion för OPTIQON Voice. Användaren ska enkelt kunna få hjälp, lämna feedback, följa sin återkoppling, läsa nyheter och uppdatera appen. Lars ska kunna kommunicera personligt och konsekvent med testarna. Designen ska även förbereda en egen kunskapsbaserad AI-assistent utan att låtsas att den redan fungerar.

Utgå från `FEEDBACK_PORT_AND_PRODUCT_CONTRACT.md` och `IMPLEMENTATION_ROADMAP.md` i samma katalog. Den fullständiga referensspecifikationen från den andra produkten följer med som separat källbilaga. Bevara dess fungerande feedback–bedömning–beslut–motivering–återkoppling-loop, men kopiera inte dess webblayout, Supabase-teknik, namnmatchning, offentliga bilagor eller andra kända brister. Källan är inte bevis på verifierad live-drift.

Befintlig app och premium-shell är primär visuell referens. Aktuellt granskat repo: `LarsAhls/optiqon-voice`, PR #1 `feat/m1-shell-onboarding`, historiskt HEAD `d1d85b08196b4e9723cac3e35c2167faad283087`. Inspektera eller använd tillgängliga skärmbilder och befintliga komponenter när Design har åtkomst. Om faktisk åtkomst saknas, begär endast nödvändiga referenser och redovisa vad som inte har kunnat inspekteras. Gissa inte fram ett befintligt designsystem. Befintlig dark theme, OPTIQON-markering, typografi, färger och komponenter ska återanvändas och utökas, inte ersättas med ett nytt varumärke.

## Designgränser

Målgrupp: högst tio inbjudna testare initialt. Ingen obligatorisk ny månadsavgift, ingen Supabase och ingen extern supportplattform. Firebase Auth + Firestore är den föredragna minsta centrala grunden, men exakt schema, integration och drift beslutas senare. Firebase App Distribution är betakanalen; Google Play är pausat. Designen får inte förutsätta ett betalt AI-abonnemang, en redan fungerande AI-backend, ett eget CMS, en egen APK-nedladdare eller en permanent server.

Google-inloggning och verifierad e-postlänk är de avsedda identitetsvägarna. Namn krävs, telefon är frivillig, lösenord ska inte införas. E-postlänkarnas begränsade gratisutskick måste visas ärligt vid behov. Den exakta produktgränsen för obligatorisk inloggning i hela appen respektive endast centrala funktioner är ännu ett Lars-beslut. Designa så att befintlig fungerande lokal diktering inte behöver raderas eller brytas av en tillfällig identitetsstörning.

Inga nya skärmar får i smyg kräva åtkomst till ljud, dikteringshistorik, urklipp, fokuserad text eller användarens Groq-nyckel. Användaren väljer uttryckligen eventuell diagnostik. Stöd rimliga Androidversioner och tillverkare; nuvarande minSdk 26 är utgångspunkt, inte ett löfte om verifierad kompatibilitet på varje enhet. Använd dokumenterade Androidbeteenden och tillgänglighetsprinciper, inte Pixel-specifika genvägar eller privata launcher-API:er.

## Informationsarkitektur att pröva

Rekommenderad startpunkt är en enda ingång **Hjälp & nyheter** med två tydliga huvuddelar:

**Hjälp & feedback:** hitta guider, ställ fråga, föreslå förbättring, rapportera problem, se Mina ärenden och läsa/svara i ett ärende. Den framtida AI-hjälpen hör hemma i samma frågeflöde och ska kunna lämna över till Lars, inte skapa en tredje inkorg.

**Nyheter:** release notes, produktnyheter och viktiga meddelanden. En publicerad release kan erbjuda Visa nyheter och Uppdatera. En nyhetsmarkering, en installbar uppdatering och ett privat svar är olika tillstånd och får inte blandas ihop.

Detta är en rekommenderad struktur, inte ett krav att rita exakt två stora flikar. Pröva om en bättre, enklare variant ger samma tydlighet. Visa högst tre materiellt skilda navigationsalternativ och välj en huvudrekommendation. Förklara varför den inte skapar fler konkurrerande inkorgar. Den befintliga dikteringsbubblan förblir fokuserad på inspelning och text. En eventuell feedbackgenväg öppnar kommunikationsytan; skapa inte en ny permanent flytande supportoverlay ovanpå andra appar enbart av designskäl.

## Flöden som ska utformas

### Första installation och befintlig användare

Visa hur ny användare introduceras till appen, identifierar sig, ansluter sin egen Groq-nyckel och får nödvändiga behörigheter utan teknisk överlast. Återanvänd redan färdig onboarding i PR #1. Lägg till en relevant manuell vägledning för Androids restricted settings vid sideloadad Accessibility, före den behörighetsbegäran som annars blockeras. Steget ska kunna hoppas över när det inte behövs eller redan är löst. Visa säker App info-genväg och återkomst. Visa också en tydlig BankID-varning innan Accessibility aktiveras och en väl synlig Home-genväg till relevant Androidinställning för att användaren själv ska kunna stänga av/sätta på tjänsten. Ingen automatisk säkerhetsväxling och inget löfte om BankID-kompatibilitet utan test.

Befintlig användare efter uppdatering ska komma tillbaka till sitt fungerande läge med samma profiler, nycklar och historik. Visa endast riktade kompletteringar för verkligt nya krav. Ingen full onboarding på nytt. Visa identitets-/nätverksfel utan att i onödan blockera lokal diktering. Inkludera verifieringslänk som gått ut, länk skickad men ej använd, återförsök, redan länkat konto, kontokonflikt och återinloggning.

### Guider och framtida AI

Visa en enkel, sökbar Help-yta med installation, behörigheter, BankID, Groq, profiler, felsökning och uppdateringar. Samma guider ska kunna användas i onboarding. Designa plats för Lars egen installationsvideo med en riktig in-app-spelare när materialet finns och en användbar textguide under tiden. Ingen påhittad videolänk.

Utforma frågeflödet så att det senare kan använda en egen AI med versionshanterad kunskapsbank och källhänvisningar. Visa hur ett svar kan ange källa, osäkerhet och erbjuda 'Skicka till OPTIQON'. Visa tillståndet 'AI-hjälp kommer senare' eller ett fungerande icke-AI-sökflöde i första versionen. Skapa inte falska chattmeddelanden som påstår att en modell redan är inkopplad. AI ska inte automatiskt fatta produktbeslut, använda privilegierade verktyg eller få tillgång till användarens privata dikteringar.

### Skicka och följa feedback

Visa ingångarna Förbättringsförslag, Rapportera problem och Ställ en fråga. De använder en gemensam ärendemodell. En enkel kompositör med text, relevant teknisk kontext och framtida valfri bilaga räcker. Låt användaren granska vad som skickas. Skärmdumpar ska kunna väljas/redigeras senare, men inte kräva en betald lagringstjänst i V1. Visa text-only-fallback och frivilligt deltagande i diagnostik.

Visa tydlig skillnad mellan lokalt sparad väntande feedback, pågående sändning, bekräftat mottagen feedback och misslyckad sändning. Ingen falsk 'Tack, mottaget' innan servern har bekräftat. Vid offline ska användaren se att texten finns kvar och kan försöka igen. En avbruten sändning får inte innebära att texten försvinner. Visa Mina ärenden med titel, datum, tydlig status och senaste relevanta svar. Ärendedetaljen ska innehålla användarens ursprungliga ord, kompletteringar, mänskligt svar, motivering och statusförändringar i begriplig ordning. Bevara tidigare förklaringar. Användaren kan följa upp i samma ärende.

Föreslagen publik livscykel: Mottaget, Under granskning, Planerat, Pågår, Levererat, Parkerat, Inte planerat. Pröva om färre tillstånd ska synas i normalvyn. Status och ett mänskligt svar ska komplettera varandra, inte skapa en komplicerad separat ärendehantering. Parkerat och Inte planerat behöver respektfull förklaring. Levererat får endast användas när förbättringen verkligen är verifierad och tillgänglig för relevant användare, inte när en PR bara är mergad. Undvik opålitliga leveransdatum.

### Nyheter och uppdateringar

Visa en ren nyhetslista, en release-detalj med kort sammanfattning och valfri fördjupning samt en gemensam uppdateringsvy. Settings ska innehålla Appversion och Sök efter uppdatering. Samma kontroll används från Settings, nyheter och notis. Designa tillstånden kontrollerar, senaste versionen, uppdatering tillgänglig, inte inloggad som behörig testare, nätverksfel, nedladdning, installationsbekräftelse, avbruten installation och installerad. En nyhet är inte bevis för att APK:n kan installeras. Visa inte 'senaste versionen' vid ett kontrollfel. Android äger installationsbekräftelsen. Inga löften om tyst installation.

Visa diskret information efter uppdatering utan att tvinga användaren att läsa alla release notes eller genomgå onboarding igen. Uppdateringen ska inte starta mitt under aktiv diktering. En release kan vara oläst även när den är installerad och en uppdatering kan fortfarande finnas även om release notes markerats lästa.

### Oläst och notiser

Utforma en enda förklarlig intern olästindikator med lämpliga delmarkeringar för privata svar och nyheter. Den ska inte nollställas bara för att användaren öppnar hela hubben. Lässtatus ska följa faktiskt presenterat innehåll eller ett avsiktligt Markera som läst. Visa hur en notis öppnar rätt ärende/nyhet och hur användaren återkommer till föregående arbete. Inkludera notiser avstängda, försenad push, app stängd, återstart och flera enheter. Androids launcher-prick är OS-/launcherberoende, inte ett garanterat eget rött utropstecken eller siffra.

Personliga svar, nyheter och lokalt färdiga transkriberingar är separata notiskategorier. Ingen privat feedbacktext i låsskärmsförhandsvisning som standard. En privat notis får aldrig riktas till fel användare. Färdig transkribering öppnar den egna sparade texten, inte supportinkorgen. Längre mötesinspelningar och bakgrundsjobb är en framtida separat produktfunktion; designa en möjlig resultatdestination men påstå inte att detta redan finns.

### Lars administration och Claude Code

Skapa en enkel konceptvy av Lars arbetsflöde, inte nödvändigtvis en full administrativ mobilapp: alla nya/ändrade ärenden, komplett liggare, parkerade, dubbletter och privata användarsvar. Visa hur 'Hämta feedback' först ger läsning och rekommendationer, sedan en tydlig mänsklig beslutspunkt. Visa en förhandsgranskning av föreslagen statusändring/svar och en bekräftad publiceringshandling. En användare ser bara sitt eget ärende, Lars alla. Interna tekniska anteckningar och andra användares identitet får inte synas för vanliga användare. Ett GitHub-issue kan samla flera ärenden utan att deras privata innehåll publiceras. Ingen automatisk användaråterkoppling bara för att Claude analyserat eller kodats något.

## Leverabler från Claude Design

1. En kort designprincip och rekommenderad informationsarkitektur, med kritisk jämförelse av högst tre relevanta alternativ och tydligt val.
2. En klickbar eller sammanhängande prototyp med användarflödena ovan, inklusive ny användare, befintlig användare och återkommande användning. Visa separata mobilskärmar snarare än en stor illustration.
3. En skärminventering och navigationskarta som visar hur nya vyer ansluter till befintliga Home, Profiles, Settings och onboarding, med identifierade ändringar i stället för en generell omdesign.
4. Komponent- och tillståndsspecifikation: knapphierarki, formulär, status, oläst, typografi, färganvändning, notiser, textlängder, tomma/laddande/fel/offline-lägen och tillgänglighet. Återanvänd OPTIQON-designsystemet.
5. Färdiga förslag på svenska mikrotexter och motsvarande lokaliseringsstruktur för engelska. Undvik teknisk jargong, vaga kvittenser och löften som implementationen inte kan hålla.
6. En tydlig uppdelning mellan V1 som kan byggas utan AI och framtida AI-/mötesfunktioner. Alla framtida element ska märkas som sådana.
7. En kort adversarial self-review: var kan användaren tappa ett meddelande, misstolka status, fastna i inloggning/behörigheter, få en felriktad notis eller tro att en uppdatering är installerad? Korrigera materiella brister före leverans.
8. En designhandoff som Claude Code senare kan läsa, med komponentreferenser, skärmflöden, tillstånd, acceptanskriterier och nödvändiga tillgångar. Ingen implementation, backendkonfiguration eller säkerhetsmodell beslutas av den visuella prototypen.

## Granskningsordning och stopp

Design får utforska och föreslå förenklingar, men ändrat produktmål, obligatorisk ny kostnad, nytt konto-/säkerhetskrav, datainsamling eller ny extern tjänst ska lyftas som beslut, inte antas godkänt. Om befintlig app/design inte kan läsas ska bristen redovisas; använd inte gissad current state. Inga riktiga användaruppgifter, API-nycklar eller privata feedbackmeddelanden i prototypen. Använd syntetiska exempel.

När designen är klar granskar Lars den tillsammans med ChatGPT. Samla materiella avvikelser och gör vid behov en revisionsrunda. Först efter accepterad design ska Claude Code göra sin repo- och arkitekturplan. Den fasindelade ordningen finns i `IMPLEMENTATION_ROADMAP.md`. Ingen kodimplementation, Firebase-provisionering, signering, live-skrivning eller merge ingår i detta designuppdrag.
