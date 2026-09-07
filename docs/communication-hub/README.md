# OPTIQON Voice — kommunikationshub, källgranskning och roadmap

**Status:** Discovery / designunderlag, 2026-09-07. **Ägare:** Lars. **Ingen implementation, provider-provisionering, live-skrivning eller merge är godkänd.**

## Läsordning och källägarskap

1. `FEEDBACK_PORT_AND_PRODUCT_CONTRACT.md` — huvudunderlag för avsedd funktion, portningsanalys, integritet, säkerhet, datamodell, återkopplingssemantik och Claude Code-triage. Läs detta före den äldre referensen.
2. `DESIGN_HANDOFF.md` — komplett avlämning till Claude Design. Används för nästa designrunda och Lars/ChatGPT-review. Ingen Claude Code-implementation följer automatiskt av designen.
3. `IMPLEMENTATION_ROADMAP.md` — föreslagen beroendeordning, kostnads-/genomförbarhetsgate, Missions, verifiering och ännu öppna beslut. Detta är en roadmap, inte en godkänd Gate-plan.
4. `BACKLOG.md` i reporoten — kort durable backlogg, inklusive Android Developer Console limited distribution och tillförlitliga beta-uppdateringar.
5. **Källbilagan `FEEDBACK_FUNCTION_SPEC.md`** — Lars fullständiga referensspecifikation från den andra produkten. Den tillhandahålls i det separata kompletta dokumentpaketet som åtföljer denna avlämning. SHA-256: `dd568332bdaa59b45c1d1df4f2f392b96c8fc748c3fe6199949ec092b4da0d6c`; 750 rader. Originalet är inte omtolkat eller förkortat i den bilagan. Om källan saknas i en senare session ska den hämtas från den medföljande bilagan, inte rekonstrueras ur minnet. Lägg inte till någon annan produkts live-data eller nycklar i Voice.

## Vad som har granskats

Referensen beskriver en React/Supabase-baserad feedbackfunktion med en flytande ingång, create/read för användare, administrativ triage utanför appen, status + motivering som svarskanal, push, e-post, skärmdumpar, offlinekö, olästmarkering och ett strukturerat 'hämta feedback'-protokoll. Dokumentets egen status anger att den andra produktens live-databas inte kunde läsas. Vi har därför granskat det dokumenterade beteendet och dess uttalade kodrisker, inte verifierat faktisk live-drift.

De värdefulla produktprinciperna är en lågfriktionsväg att lämna feedback, tydlig kvittens, bestående egen historik, en kort mänsklig motivering för varje materiellt beslut, återkommande granskning av parkerat arbete och verklig återkoppling först när en ändring är tillgänglig. Dessa förs vidare till Voice.

De kända bristerna får inte portas: klientstyrt adminfilter, privilegierad endpoint utan korrekt rollkontroll, namn som identitet, offentlig bildlagring, förlustbenägen offlinekö, automatisk 'läst' vid panelöppning, överskriven beslutsmotivering, avsaknad av historik, fri status-JSON och fullständiga opaginerade mobillistor. Referensens absoluta 'radera aldrig' ersätts av en transparent retention-/raderingspolicy som inte förlorar obearbetade ärenden och respekterar användarens rättigheter. Bilder är evidens men inte automatiskt bevis på rotorsak. Privata ärenden får inte kopieras till publika GitHub-issues.

## Beslutade riktningar och öppna frågor

Riktning: egen OPTIQON-upplevelse, Firebase Auth + Firestore som minsta centrala kandidat, Firebase App Distribution för högst tio testare, ingen Supabase eller extern supportplattform, ingen obligatorisk ny månadsavgift. Egen AI-chatbot och kunskapsbank förbereds i design men byggs senare. Google Play avvaktas. Den kostnadsfria begränsade Android Developer Console-vägen ligger som framtida research/backlogg, inte som en godkänd registrering.

Öppet för Lars: om identitet måste verifieras före all första användning eller endast centrala funktioner; hur e-postalternativet ska hanteras när Spark-gränsen på fem inloggningslänkar/dag nås; om privata skärmdumpar behövs redan i V1; retention/radering och integritetsinformation; exakt Firebase-projekt och adminidentitet; samt hur eventuella inkompatibla debugsignaturer ska migreras utan oförutsedd dataförlust. Ingen betalplan, ny extern tjänst eller konto-/säkerhetsåtgärd väljs automatiskt.

## Nästa arbetsordning

Lars lämnar `DESIGN_HANDOFF.md` och relevanta referensfiler/skärmbilder till Claude Design. När designen är klar granskas hela resultatet kritiskt tillsammans med ChatGPT och materiella ändringar samlas. Därefter inspekterar Claude Code aktuellt repo, PR/branch/worktree, tester och målmiljö och tar fram exakt implementations-/Gate-plan enligt roadmapen. Befintlig PR #1 ska inte växa till en full kommunikationsplattform. Ingen publicering, merge, signering eller live-konfiguration är en följd av att dessa dokument skapats.

## Verifiering av denna dokumentationsleverans

Detta är en dokumentationsändring på `docs/communication-hub-roadmap`, baserad på main `e1957c153e8f7f54b0e2a25186173c53374c00c9`. Kontrollera aktuella refs innan fortsatt arbete. Källans SHA är verifierad mot den uppladdade filen. Inga riktiga användaruppgifter, API-nycklar eller externa providermiljöer har använts. Ingen kodimplementation eller fysisk smoke har utförts genom dessa dokument. Produkt-/säkerhetskraven gäller inte retroaktivt som verifierad appfunktion.
