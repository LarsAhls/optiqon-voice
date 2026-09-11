# Riskgrind — fysisk Room-migrering 8 → 9 på `c1f9837c` (CPH2645)

Datum: 2026-09-11. Gäller PR #9 (Mission 2, typade profiler).
Status: **framlagd, ej godkänd.** Ingen enhet rörs förrän Lars säger ja till den här grinden.

Den här grinden **ärver ingenting från Mission B**. Mission B installerade en app som inte
ändrade databasens form; den här installationen skriver om schemat i din telefon och kan inte
ångras med en nedgradering. Det är en annan sorts risk och får ett eget beslut.

---

## 1. Vad som faktiskt står på spel

Databasen `OptiqonVoiceDatabase` på enheten innehåller sex tabeller:

`dictations`, `profiles`, `text_replacement_rules`, `post_processing_prompts`,
`lifetime_stats`, `outbox`.

Det är hela din diktamenshistorik, dina profiler, dina ersättningsregler och dina prompter.
Inget av det ligger någon annanstans.

**Det finns ingen väg att säkerhetskopiera det.** `AndroidManifest.xml:18` sätter
`android:allowBackup="false"`, så `adb backup` hämtar ingenting. Appen har ingen export-funktion.
Telefonen är inte rootad, så `/data/data/se.optiqon.voice/databases/` går inte att läsa via adb.
Det här är grindens kärna: **om migreringen går sönder finns ingen kopia att gå tillbaka till.**

Att bygga en export-funktion först är ett giltigt alternativ och är det enda som faktiskt
avskaffar risken. Det är en egen uppgift, inte en del av den här.

## 2. Vad som är irreversibelt, exakt

När ett v9-bygge en gång har öppnat databasen står `user_version = 9` i filen. Varje v8-APK
vägrar då öppna den — Room har ingen destruktiv fallback åt något håll. Det betyder:

- **Rollback via nedgradering finns inte.** Att installera den gamla APK:n igen ger en app som
  kraschar vid första databasanropet, inte en app som fungerar som förut.
- Den enda vägen "tillbaka" från ett trasigt v9-läge vore avinstallation eller rensad data,
  alltså **att kasta bort exakt det data grinden ska skydda**. Det ligger utanför den här
  grinden och kräver ett separat, uttryckligt godkännande från dig i stunden.

Själva ändringen är liten och additiv:
`ALTER TABLE profiles ADD COLUMN profileKind TEXT NOT NULL DEFAULT 'GENERAL'`.
Ingen tabell tas bort, ingen kolumn ändrar typ, ingen rad klassas om. En profil som heter
"Mail" blir inte `EMAIL`.

## 3. Vad som redan är bevisat — och vad det inte är

Bevisat i CI och lokalt (svit 458/0/2 på `8c6e213`):

- `ProfileKindMigrationTest` (4) bygger en v8-fil ur det **exporterade** v8-schemat, alltså den
  form förra releasen verkligen skickade, kör migreringen och läser raderna efteråt.
- `MigrationChainTest` (5) kör hela kedjan, `DowngradeGuardTest` (1) bevisar att nedgradering
  vägras, `OutboxMigrationTest` (3) täcker grannsteget.

Vad det **inte** bevisar: att *din* installation överlever. Robolectrics SQLite är inte
telefonens, och din fil har historik ingen fixtur har. Det är hela skälet till att det här steget
finns.

## 4. Förutsättningar innan enheten rörs

1. PR #9 är grön i CI och granskad. *(Reparationscommitten `8c6e213` är pushad; CI-status
   kontrolleras direkt före steg 5.)*
2. Du har läst punkt 1 och 2 ovan och accepterar att det inte finns någon kopia.
3. Du har enheten i handen och kan se skärmen under körningen.
4. Enheten är `c1f9837c` (CPH2645), den canonical-enheten. `01132f9f` är auxiliary och ingår inte.

## 5. Vad jag skulle göra, i ordning

Varje steg är läsbart och avbrytbart. Jag stannar och rapporterar vid första avvikelse.

| # | Handling | Effekt på enheten |
|---|---|---|
| 0 | `adb devices`, `adb shell dumpsys package se.optiqon.voice \| grep versionCode` | **Läser bara.** Bekräftar att installerad kod är `2026091000` och att rätt enhet svarar. |
| 1 | `sh gradlew :app:assembleDebug` (utan `-PversionTag`) | **Rör inte enheten.** Ger `versionCode = 2026091100` (dagens datum × 100), alltså högre än det installerade — installationen blir en uppgradering, inte en nedgradering. |
| 2 | `adb install -r <apk>` | **Skriver.** Appen byts ut. Databasen är fortfarande v8 i det här ögonblicket. |
| 3 | Du startar appen och öppnar profillistan | **Migreringen körs här**, vid första databasanropet. Det är den oåterkalleliga sekunden. |
| 4 | `adb logcat` filtrerat på appen, samt visuell kontroll av att dina profiler, regler och historik finns kvar | **Läser bara.** |

**Ingår inte, och kommer inte att ske utan att du ber om det i stunden:** ingen avinstallation,
ingen `pm clear`, ingen `force-stop`, ingen ändring av systeminställningar, ingen ominstallation
av en äldre APK ovanpå en migrerad databas.

## 6. Om det går fel

Utfallen, ärligt:

- **Migreringen faller vid steg 3** → appen kraschar vid start. Databasen står kvar orörd på v8
  (Room kör migreringen i en transaktion). Den gamla APK:n fungerar då igen. Det här är det
  *goda* felfallet.
- **Migreringen lyckas men data ser fel ut** → v9 är skrivet. Ingen nedgradering finns. Vi
  diagnosticerar på plats med logcat och det appen visar; jag rör ingenting destruktivt.
- **Enheten tappar anslutningen mitt i** → migreringen är en transaktion i telefonen och påverkas
  inte av adb. Vi kopplar upp igen och läser av.

## 7. Vad grinden begär av dig

Ett av tre:

- **Ja** — kör steg 0–4 på `c1f9837c` som de står.
- **Nej, inte nu** — PR #9 kan mergas utan det här steget om du lyfter merge separat; migreringen
  når då din telefon först vid en vanlig release, med samma risk men utan att du står bredvid.
- **Bygg export först** — jag lägger en uppgift på att ge appen en databasexport, så att steget
  kan tas med en kopia i handen. Långsammast, och det enda som faktiskt tar bort risken.

Min rekommendation är **Ja**, med nackdelen namngiven: det finns ingen kopia, och om något går
snett i själva skrivningen är det enda återställningsläget ett som kastar data. Skälet att ändå
rekommendera det är att migreringen är additiv, testad mot det verkliga exporterade v8-schemat,
och att du står bredvid med skärmen påslagen — vilket är strikt bättre än att samma migrering når
telefonen i en release när du inte gör det.
