# Riskgrind — fysisk Room-migrering 8 → 9 på `c1f9837c` (CPH2645)

Datum: 2026-09-11. Gäller PR #9 (Mission 2, typade profiler).
Status: **framlagd, ej godkänd.** Ingen enhet rörs förrän Lars säger ja till den här grinden.

Den här grinden **ärver ingenting från Mission B**. Mission B installerade en app som inte
ändrade databasens form; den här installationen skriver om schemat i din telefon. Det är en annan
sorts risk och får ett eget beslut.

*Reviderad 2026-09-11 efter ChatGPT:s REVISE. Tre påståenden i första versionen var fel och är
rättade: den hårdkodade versionCode:n (§5), felfallet som lät som automatisk rollback (§6) och
migreringsögonblicket som antog profillistan (§5.3). Sprängradien i §1 är dessutom större än
första versionen sa — tre databaser, inte en, och två Android-användare.*

---

## 1. Vad som faktiskt står på spel — mätt, inte antaget

Appen har **tre** databasfiler, en per Mission-1-lagringsrot, plus egna preferensfiler och
retained-audio per rot. Alla tre finns på enheten idag (läst read-only via `run-as`, `09:31`):

| Fil | Rot | Storlek | db senast skriven | WAL |
|---|---|---|---|---|
| `optiqon_voice.db` | `default` | 57 344 B | 2026-09-09 17:20 | 255 472 B, 2026-09-11 08:32 |
| `optiqon_voice_u1.db` | `u1` | 57 344 B | 2026-09-11 09:21 | 412 032 B, 2026-09-11 09:26 |
| `optiqon_voice_u2.db` | `u2` | 57 344 B | 2026-09-09 18:34 | 214 272 B, 2026-09-09 21:22 |

Var och en innehåller samma sex tabeller: `dictations`, `profiles`, `text_replacement_rules`,
`post_processing_prompts`, `lifetime_stats`, `outbox`.

**Dessutom: paketet är installerat för två Android-användare.** `User 0` *och* `User 10` har
`installed=true` med var sin `ceDataInode`, alltså var sin komplett datakatalog. Allt ovan gäller
User 0. User 10:s data går inte att läsa med `run-as` från User 0 och är okänt för mig
(**verifieringsglapp V5**, se §4). Den datan migreras först när appen körs som User 10.

**Faktiskt innehåll per rot**, från de dumpar som redan ligger på enheten sedan
2026-09-09 21:12–21:23 (skrivna under Mission B:s godkända gate, inte av mig nu):

| Rot | `db_user_version` | profiler | regler | diktat | ord |
|---|---|---|---|---|---|
| `default` | 8 | 1 | 0 | 0 | 0 |
| `u1` | 8 | 1 | 0 | 12 | 35 |
| `u2` | 8 | 1 | 0 | 1 | 9 |

Det här är materiellt för beslutet: **det som står på spel är litet.** Tre profiler, noll
ersättningsregler, tretton diktat om sammanlagt 44 ord. Första versionen av den här grinden
beskrev "hela din diktamenshistorik" utan att ha mätt den.

Men siffrorna är **från 2026-09-09**, och `u1` skrevs så sent som `09:26` idag. De är alltså
inte en giltig pre-migration-snapshot — se §4.

**Det finns ingen filnivå-backup att ta.** `AndroidManifest.xml:18` sätter
`android:allowBackup="false"`, så `adb backup` hämtar ingenting. Se §3 för vad jag faktiskt
undersökte och varför inget av det räknas som backup.

## 2. Vad som är irreversibelt, exakt

När ett v9-bygge en gång har öppnat en databasfil står `user_version = 9` i den filen. Varje
v8-APK vägrar då öppna den — Room har ingen destruktiv fallback åt något håll.

- **Rollback via nedgradering finns inte.** Att installera den gamla APK:n igen ovanpå en
  migrerad databas ger en app som kraschar vid första databasanropet.
- Den enda vägen "tillbaka" från ett trasigt v9-läge vore avinstallation eller rensad data,
  alltså **att kasta bort exakt det data grinden ska skydda**. Det ligger utanför den här
  grinden och kräver ett separat, uttryckligt godkännande från dig i stunden.

Själva ändringen är liten och additiv:
`ALTER TABLE profiles ADD COLUMN profileKind TEXT NOT NULL DEFAULT 'GENERAL'`.
Ingen tabell tas bort, ingen kolumn ändrar typ, ingen rad klassas om. En profil som heter
"Mail" blir inte `EMAIL`.

## 3. Backup-möjligheten — undersökt, och utfallet

`adb shell run-as se.optiqon.voice id` **fungerar** (uid=10582, context `u:r:runas_app:s0`).
Appen är `DEBUGGABLE`, så filerna *går* att nå. Ändå finns ingen godtagbar kopia, av tre skäl
som alla är mätta, inte antagna:

1. **Ingen `sqlite3` på enheten.** Den binär som skulle kunna ta en konsistent kopia
   (`.backup`, eller `VACUUM INTO`) finns inte att köra.
2. **Processen lever och skriver.** `u1`:s WAL växte till 412 032 B och rördes `09:26` idag. En
   `cat`-baserad kopia av `.db` + `-wal` + `-shm` från en app som skriver är precis den **råa
   inkonsistenta SQLite/WAL-kopia som inte räknas som backup**.
3. **De konsistenta alternativen kräver något som är förbjudet.** Att tysta skrivaren kräver
   `force-stop`. `VACUUM INTO` kräver en skrivning in i appens datakatalog. Båda står på
   §0-listan.

**Slutsats: ingen konsistent, integritetssäker filkopia är möjlig inom de givna gränserna.**
Det förblir en accepterad risk i den här grinden. En riktig export är en **separat uppgift**,
inte en del av den här — och den enda åtgärd som faktiskt avskaffar risken.

## 4. Bevis före/efter — vad som går att jämföra, och glappen

Appen har redan readback-tooling: debug-mottagaren `AccessDebugReceiver` med
`se.optiqon.voice.debug.DUMP_STATE`, bakom `android.permission.DUMP` (signature|privileged,
hålls av adb shell, kan inte ges till en installerbar app). Två saker är **verifierade** om den:

- **Den finns i den installerade APK:n.** `cmd package query-receivers -a …DUMP_STATE` svarar
  `1 receivers found`, och dumpar för alla tre rötterna ligger redan i `files/debug/`.
- **Den renderar identiskt i båda byggena.** `git diff 18c0ff8 40b4899 -- app/src/debug/` är
  **tom**. Den installerade buildens dump-kod och kandidatens är samma kod, så före/efter kan
  jämföras rad för rad utan att formatet flyttar sig.

`render()` är **enbart digests och räknare** — aldrig nyckelvärden, aldrig diktattext. Fälten är:
`db_user_version`, `profiles` + `profile_names` + `active_profiles`, `rules` + `rule_names`,
`dictations` + `dictation_words` + `dictation_texts_sha256`, 16 `setting.*`-rader,
`asr_api_key_len`/`_sha256`, `llm_api_key_len`/`_sha256`, `default_owner`, `active_uid`,
`access_status`, plus `signer.txt`.

Det täcker i stort sett hela ChatGPT:s lista. Kvarvarande **verifieringsglapp**, namngivna:

- **V1 — `post_processing_prompts`, `lifetime_stats` och `outbox` läses inte alls.** Tre av sex
  tabeller har ingen readback. De migreras med, men ingen räknare bevisar att de överlevde.
- **V2 — `onboarding_complete` finns som `setting.*`, men "access state" täcks bara av
  `access_status` för den aktiva roten.**
- **V3 — dumpen kostar en skrivning in i appens datakatalog.** `dump()` skriver
  `files/debug/state*.txt`. Det är en liten, icke-destruktiv, debug-only fil — men det **är**
  en appdata-skrivning, och den står på §0-listan. Jag har därför **inte** kört den. Se §5, steg
  A: en färsk pre-migration-snapshot kräver ditt uttryckliga ja till just den skrivningen.
- **V4 — de dumpar som finns är från 2026-09-09 och är stale.** `u1` skrevs `09:26` idag.
- **V5 — User 10:s data är oläsbar för mig.** `run-as` från User 0 når den inte. Ingen baseline,
  ingen efterkontroll, och den migreras vid ett tillfälle jag inte styr över.
- **V6 — `firstInstallTime` och signer läses ur `dumpsys`/APK, inte ur dumpen.** Redan gjort,
  se §7-artefaktlistan.

Ingen generell export- eller instrumenteringsplattform byggs för det här. Glappen redovisas som
glapp.

## 5. Vad jag skulle göra, i ordning

Varje steg är avbrytbart. Jag stannar och rapporterar vid första avvikelse.

| # | Handling | Effekt på enheten |
|---|---|---|
| 0 | `adb devices`, `dumpsys package se.optiqon.voice` | **Läser bara.** Redan gjort, se §7. |
| A | *(kräver eget ja)* `am broadcast -a …DUMP_STATE` × 3 rötter, sedan `run-as cat` | **Skriver `files/debug/state*.txt`.** Enda skrivningen före migreringen. Ger den färska pre-migration-snapshot som V4 saknar. Utan den går vi in med 09-09-siffror. |
| B | Starta `adb logcat` mot appen **innan** något annat | **Läser bara.** Måste stå igång före steg D. |
| C | `adb install -r <apk>` | **Skriver.** Appen byts ut. Databaserna är fortfarande v8 i det ögonblicket. |
| D | **Du startar appen.** | **Migreringen körs vid första Room-/databasöppningen efter start** — inte när profillistan öppnas. Room öppnar filen första gången något injicerat DAO rör den, vilket kan ske i uppstarten innan någon lista visas. Behandla appstarten, inte profillistan, som den oåterkalleliga sekunden. **Bara den aktiva rotens databas migreras här**; `u1`/`u2`/`default` migreras var för sig, först när respektive konto blir aktivt. |
| E | Läs logcat + upprepa steg A:s dump per rot och jämför | **Skriver samma debug-fil igen** (samma villkor som A), i övrigt läsning. |

**Ingår inte, och godkänns inte av den här grinden:** ingen avinstallation, ingen `pm clear`,
ingen `force-stop`, ingen `adb install -d`, ingen ändring av systeminställningar, ingen
ominstallation av en äldre APK ovanpå en migrerad databas.

## 6. Om det går fel — rättat

- **Migreringen faller vid steg D.** Room kör migreringen i en transaktion, så **databasfilen
  bör stå kvar på v8**. Det skyddar datan. Men — och det här sa första versionen fel —
  **appversionen på telefonen är redan utbytt.** Att v8-datan är intakt betyder *inte* att du
  har en fungerande app: den installerade koden är v9-koden, och den kraschar mot en v8-fil.
  Att få tillbaka en app som startar kräver en **separat återställningsåtgärd** — ominstallation
  eller nedgradering av APK:n — som **inte ingår i den här grinden och inte är godkänd**.
  Den åtgärden får sitt eget beslut i stunden, med dig närvarande.
- **Migreringen lyckas men data ser fel ut.** v9 är skrivet. Ingen nedgradering finns. Vi
  diagnosticerar på plats med logcat och steg E:s dump; jag rör ingenting destruktivt.
- **Enheten tappar anslutningen mitt i.** Migreringen är en transaktion i telefonen och påverkas
  inte av adb. Vi kopplar upp igen och läser av.
- **De två icke-aktiva rötterna.** De migreras inte vid steg D. Ett fel där dyker upp senare,
  när du byter konto — inte medan du står bredvid.

## 7. Vad grinden begär av dig

Tre separata ja, inte ett:

1. **Snapshot (steg A)** — godkänner du den enda appdata-skrivningen, `files/debug/state*.txt`,
   för att få en färsk pre-migration-baseline? *(Rekommenderas. Nackdel: det är en skrivning in
   i appens datakatalog, alltså ett undantag från §0 som du gör medvetet.)*
2. **Migrering (steg B–E)** — kör installationen och migreringen på `c1f9837c`.
3. **Ingenting av ovanstående** — PR #9 kan mergas separat; migreringen når då telefonen först
   vid en vanlig release, med samma risk men utan att du står bredvid.

Min rekommendation är **1 + 2**, med nackdelen namngiven: det finns ingen filkopia, och om något
går snett i själva skrivningen är återställningen en separat åtgärd som kan kosta data. Skälen
att ändå rekommendera det är att ändringen är additiv, testad mot det verkliga exporterade
v8-schemat (svit 458/0/2, CI grön), att mängden data som står på spel är uppmätt och liten
(3 profiler, 0 regler, 13 diktat), och att du står bredvid med skärmen påslagen.
