# Sittning 3 — G4-signering och App Distribution, kryssbar lista

Underlag till den sittning som följer efter G3. Två spår: **A. nyckeln** (G4) och
**B. distributionskanalen** (D3). Båda kräver dig; ingenting här går genom en agent.

Hårda regler som gäller hela sittningen:

- **Jag genererar ingen nyckel och matar inget lösenord.** Lösenord går i lösenordshanteraren
  — aldrig i chatten, repot, CI-loggar eller ett skalkommando.
- Nyckelfilen, `beta.lineage` och `release-signing.properties` committas **aldrig**
  (`.gitignore` täcker dem redan — `CLAUDE.md`, "No secrets in the repo").
- Efter en lyckad rotation finns **ingen** väg tillbaka. D2 är beslutad (rotation på plats,
  ingen avinstallation) och det är den irreversibla delen.

Beslutade förutsättningar: **D2** rotation på plats, **D8** golv API 28,
**D3** Firebase App Distribution, **identitet** = befintlig Firebase Auth. Projekt
**`optiqon-voice-47498`**, admin **lars@optiqon.se**.

---

## A. G4 — beta-nyckeln (ditt arbete, offline)

Kommandona står ordagrant i `docs/BETA_SIGNING_AND_DISTRIBUTION.md` §4 och §6. Kryssa här.

- [ ] **A1. Skapa nyckeln på arbetsstationen** (inte i ett cloud shell) — `keytool -genkeypair`
      enligt §6 steg 1. Lösenordet direkt in i lösenordshanteraren.
- [ ] **A2. Säkerhetskopiera och *bevisa* kopian** — §6 steg 2. En kopia som inte har signerat
      något är ingen kopia: `keytool -list -v` på kopian, jämför SHA-256, signera en
      slasksAPK med kopian och se samma SHA-256 i `apksigner verify --print-certs`.
- [ ] **A3. Skapa `beta.lineage`** — `apksigner rotate` enligt §4, med repots `debug.keystore`
      som `--old-signer`. Ligger kvar intill nyckeln; krävs vid **varje** framtida signering.
- [ ] **A4. Kontrollera härstamningen** — `apksigner lineage --print-certs -v --in beta.lineage`.
      Förväntat: två certifikat, debug-certet med **`rollback=false`**.
      **Passera aldrig `--set-rollback true`** — §2 negativ iii visar att den gamla nyckeln då
      kan uppdatera appen igen på alla API-nivåer, vilket raderar hela poängen.
- [ ] **A5. Registrera beta-certifikatet i Firebase** — SHA-1 **och** SHA-256, **additivt**
      (debug-SHA:erna ligger kvar). Projekt `optiqon-voice-47498`.
- [ ] **A6. Lägg SHA-256 i `public/.well-known/assetlinks.json` och deploya Hosting**, sedan
      G2-postflight igen (`scripts/gate/g2-postflight.sh`). **Före** första beta-signerade
      installationen — annars tappar App Links-verifieringen fotfästet.
- [ ] **A7. Signera med det guardade flödet, inte för hand:**
      `sh gradlew assembleRelease -PunsignedRelease=true` följt av
      `scripts/signing/sign-release.sh` med `--expected-sha256 <beta SHA-256>` och
      `--lineage beta.lineage --old-ks debug.keystore --old-alias androiddebugkey`
      (härstamningsflaggorna hör ihop — se skriptets eget användningsexempel rad 24-25).
      Skriptet vägrar lämna en utfil om den effektiva signeraren
      inte är den väntade, om signeraren står på deny-listan (debug-SHA:n står där permanent)
      eller om härstamningen saknar den väntade nyckeln som nyaste certifikat.
- [ ] **A8. Verifiera utfilen** — `apksigner verify --print-certs -v`. Förväntat med
      `--rotation-min-sdk-version 28`: **v2 true, v3 true, v3.1 false**, och **beta**-certet
      som effektiv signerare.
- [ ] **A9. Rotationen på telefonen** — `adb install -r` av den signerade APK:n. Kontrollera
      efteråt: `dumpsys package se.optiqon.voice` visar beta-SHA:n som signerare, **past
      signatures** innehåller debug-certet, `firstInstallTime` **oförändrad**, och appen
      startar med data kvar (profiler, regler, historik, nycklar).
      *Den installerade appen står i dag på `apkSigningVersion=2` med `past signatures:[]` —
      det är utgångsläget A9 ska ändra.*

**Stopp i spår A:** om A4 visar `rollback=true`, om A7:s skript vägrar, eller om A9 svarar
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Avinstallera **inte** för att komma vidare — det är
precis det D2 valde bort. Stanna och ta det som ett eget beslut.

---

## B. D3 — Firebase App Distribution (konsolarbete)

Allt i `optiqon-voice-47498`, inloggad som **lars@optiqon.se** på
[console.firebase.google.com](https://console.firebase.google.com/project/optiqon-voice-47498/appdistribution).

- [ ] **B1. Öppna App Distribution** för Android-appen `se.optiqon.voice` och acceptera
      aktiveringen. Spark-nivån räcker — App Distribution kostar inget och kräver ingen
      uppgradering.
- [ ] **B2. Skapa testargruppen** — ett namn som tål att stå i ett skriptargument, t.ex.
      `beta`. Gruppens alias är vad uppladdningen refererar till.
- [ ] **B3. Lägg in testarna med e-post.** Varje testare får en inbjudan och måste acceptera
      den på telefonen innan någon APK syns för hen. **Taket är 10** (`config/limits.maxApprovedUsers`)
      — och de två gränserna är olika saker: App Distribution släpper in någon i *kanalen*,
      Firestore-godkännandet (`m1-bootstrap.mjs approve`) släpper in hen i *appen*. En testare
      behöver båda.
- [ ] **B4. Kontrollera att återkopplingskanalen i App Distribution är AV.** Feedback ska gå
      genom den beslutade privata Help & news-slingan (Mission E), inte genom Googles
      skärmdumpsflöde — annars hamnar testarnas text i ett andra system ingen äger.
- [ ] **B5. Ladda upp den A8-verifierade APK:n** en gång, manuellt i konsolen, och bekräfta att
      App Distribution rapporterar **beta-certifikatets** SHA-256 och rätt `versionCode`.
      Notera versionCode i protokollet — `versionCode = baseCode * 100 + patchCode`, så samma
      dag kräver en patchbump (`-PversionTag=v<YYYYMMDD>.<patch>`).
- [ ] **B6. En testare som inte är du** installerar via inbjudan på en **API 28+**-enhet och
      kommer till pending-skärmen. Det är den första gången hela kedjan nyckel → kanal →
      Firestore-godkännande körs av någon annan än dig.
- [ ] **B7. Kontrollera att ingen APK hamnar i ett publikt GitHub-utlämnande.** Så länge repot
      är publikt används inte GitHub Releases för artefakter (`…DISTRIBUTION.md` §8).

**Medvetet kvar till Mission D, inte sittning 3:** `scripts/dist/distribute.sh` som enda
uppladdningsväg (med monotonicitetskontroll på versionCode och Room-version) och in-app
"sök uppdatering" med sina tre tillstånd (aktuell / ny version / kunde inte kontrollera).
B5 görs därför manuellt denna gång, med avsikt.

---

## Vad listan inte avgör

Play App Signing. Att ladda upp härstamningsroterade APK:er till Play lyder under andra regler
(`…DISTRIBUTION.md` §7) och beta-nyckeln är **inte** designad som en Play-uppladdningsnyckel.
Det är ett eget beslut när Play blir aktuellt.
