# Riskgrind — fysisk Room-migrering 8 → 9

Datum: 2026-09-11. Gäller PR #9 (Mission 2, typade profiler).
Status: **framlagd, ej godkänd för `01132f9f`.** §1–§7 gäller `c1f9837c` (CPH2645); §8 lägger fram enhetsbytet till OnePlus 8 Pro `01132f9f`. Ingen enhet rörs förrän Lars godkänner.

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

---

## 8. Tillägg 2026-09-11 (kväll) — enhetsbytet till OnePlus 8 Pro `01132f9f`

Två saker har ändrats sedan §1–§7 skrevs, och båda ändrar beslutet.

1. **`c1f9837c` svarar inte längre på ADB.** Enda anslutna enheten är `01132f9f`.
2. **`01132f9f` är en ren testtelefon.** Lars har angett att inget annat görs på den. Det var
   okänt när grinden skrevs och ändrar kostnadssidan helt: ett migreringsfel där kostar 5 diktat
   på ett testkonto, inte hans primära diktamenshistorik.

**Föreslaget beslut: `01132f9f` blir fysisk acceptance device för PR #9. `c1f9837c` körs inte nu.**

Motiveringen är att enheterna svarar på olika frågor och bara den ena grindar en merge:

- *Fungerar migreringen mot riktig Android-SQLite på riktig hårdvara?* → `01132f9f` svarar fullt
  ut. Den har en **riktig v8-databas** (1 profil, 0 regler, 5 diktat / 34 ord, riktigt
  Firebase-konto `APPROVED`, riktiga nycklar) — inte en syntetisk fixtur.
- *Överlever Lars personliga data?* → bara `c1f9837c` svarar, och den frågan infinner sig ändå
  vid nästa vanliga release. Att köra den manuellt sänker inte risken för den datan.

### 8.1 Glapp som kvarstår med den här enheten

- **SDK 33, inte 36.** `01132f9f` kör Android 13; `c1f9837c` kör Android 16. Android 16:s
  SQLite-build förblir otestad.
- **En populerad rot, inte tre.** `signedout` och `u1` är 4 096 B / tomma.
- **User 10 (*Island*) finns även här** och rörs inte — samma glapp som V5.
- **`c1f9837c` är fortsatt utan backup-möjlighet** (§3 gäller oförändrat).

### 8.2 Två tekniska fynd som §5 inte kände till

**Fynd 1 — en appstart migrerar exakt EN databasfil, den aktiva rotens.**
`StorageModule.kt:46-48` ger en enda `@Singleton StorageRoot`; `StorageOwnership.kt:42,60-87`
returnerar en rot utan att iterera; `DatabaseModule.kt:203-214` bygger Room mot
`storageRoot.databaseName`, singular. Vilande rötter migreras först när respektive konto blir
aktivt, vilket kräver `seal()` + `exitProcess(0)` (`AccessSession.kt:141-152`,
`ProcessRestarter.kt:29-43`) och alltså en ny process. Det bekräftar §5 steg D och §6:s sista
punkt. På `01132f9f` är aktiv rot `default` — den populerade filen migreras alltså vid första
start, vilket är önskvärt.

**Fynd 2 — `DUMP_STATE` mot en vilande rot MIGRERAR den roten.**
`app/src/debug/java/se/optiqon/voice/debug/AndroidSyntheticSink.kt:82-85` bygger en andra
Room-instans mot en godtycklig `root.databaseName` med samma `DatabaseModule.ALL_MIGRATIONS`.
**Efter installationen är en `--es root signedout`-dump alltså inte en läsning** — den öppnar
filen och kör 8→9 på den. Steg E i §5 ("upprepa steg A:s dump per rot") är därför inte
read-only efter installationen, vilket §5 antog. Det gäller bara debug-byggen; ingen release-väg
rör den koden.

Migreringen är registrerad i `ALL_MIGRATIONS` (`DatabaseModule.kt:186-195`, `:207`), och
`fallbackToDestructiveMigration` finns **inte** någonstans i `app/src` — enda träffen är prosa i
`DowngradeGuardTest.kt:24` som dokumenterar att den togs bort.

### 8.3 Uppmätt baseline för `01132f9f` (read-only, inga skrivningar gjorda)

| | |
|---|---|
| Serial / modell | `01132f9f` / OnePlus 8 Pro, `IN2023`, `OnePlus8Pro_EEA` |
| Android / SDK | 13 / 33, `IN2023_13.1.0.591(EX01)` |
| Installerad | `v20260910` / `2026091000`, minSdk 26, targetSdk 35 |
| Signer | `234E2833…29EED` — **identisk** med den låsta APK:ns |
| appId / `run-as` | 10289 / fungerar |
| firstInstallTime | User 0: 2026-09-09 21:48:14 · User 10: 2026-09-09 22:08:35 |
| lastUpdateTime | 2026-09-10 10:35:02, installer `pc` |
| pkgFlags | `[DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA]` |
| DB-filer | `optiqon_voice.db` 57 344 B · `optiqon_voice_signedout.db` 4 096 B · `optiqon_voice_u1.db` 4 096 B |
| Aktiv rot | `default`, `default_owner` = `active_uid` = `qHWdyX…`, `access_status=APPROVED` |
| Innehåll (`default`) | `db_user_version=8`, 1 profil, 0 regler, 5 diktat / 34 ord |
| Dump på enheten | `files/debug/state.txt` 2026-09-09 23:28 — **stale**, samma glapp som V4 |

`2026091000 → 2026091100` är en uppgradering med samma signer ⇒ `install -r` utan `-d`.

### 8.4 Procedur på `01132f9f`

Identisk med §5 i sak, med fynden ovan inarbetade:

| # | Handling | Effekt |
|---|---|---|
| 0 | Verifiera APK SHA-256 / versionCode / signer mot approval box | Läser bara |
| A | `am broadcast …DUMP_STATE` för `default`, `signedout`, `u1`, sedan `run-as cat` | Skriver `files/debug/state*.txt`. **Med gammal APK migrerar detta ingenting** — dess `ALL_MIGRATIONS` slutar på v8. Verifiera `db_user_version=8` på alla tre. |
| B | `adb logcat` **innan** installationen | Läser bara |
| C | `adb install -r <godkänd apk>` | Skriver. Inget `-d`, ingen uninstall, ingen clear-data, ingen force-stop. |
| D | Normal appstart | **`optiqon_voice.db` migreras här** (`OptiqonVoiceApp.kt:39-47` → `ProfileRepository.ensureDefaults()`) |
| E | Postflight-dump av **`default`**, jämför mot A | Skriver samma debug-fil igen |
| F | *(egen beslutspunkt)* dump av `signedout`/`u1` | **Migrerar dem** (fynd 2). Görs bara med uttryckligt ja. |

Hard stop, uteslutningar och "ingen recovery improviseras" gäller oförändrat från §5–§6.
User 10 på `01132f9f` rörs inte. `c1f9837c` rörs inte. PR #9 mergas inte.

### 8.5 Vad grinden begär nu

Gällande approval box är scopad till `c1f9837c` och säger uttryckligen
"Ingen annan artefakt eller device ingår". **Ett nytt Gate-beslut krävs för `01132f9f`.**
Artefakten är oförändrad: code HEAD `40b4899`, APK
`scratchpad/optiqon-voice-40b4899-v20260911.apk`, SHA-256
`c8362d93935bfaa9c7573f45e41b1b5078206c8e324842d24929471cd2ffbc33`, versionCode `2026091100`,
signer `234e2833…29eed`.
