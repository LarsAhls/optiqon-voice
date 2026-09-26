> **PARKED PLAN — DO NOT ACTIVATE YET**
>
> This plan is approved as the future Voice Capture implementation plan, but it MUST NOT be used as an active implementation Mission until the current Storage/feedback/private-beta track is completed and verified.
>
> Activation preconditions:
> 1. Finish and verify the current feedback/private-beta work first.
> 2. Update the project delivery state so Voice Capture is explicitly the active/prioritized Mission.
> 3. Re-verify repo/device/provider state before starting G0.
> 4. Obtain any exact Gate approvals required by G0 or later live/external actions.
>
> Until those conditions are met, this file is reference-only and MUST NOT authorize code changes, provider/Drive/GCP writes, or Mission reprioritization.

# VOICE_CAPTURE_IMPLEMENTATION_PLAN — OPTIQON Voice

`document_id: optiqon-voice-capture-implementation-plan`  
`lifecycle: execution-plan-candidate`  
`authority: mission-and-gate-plan`  
`product_source: VOICE_CAPTURE_SPEC.md`  
`consensus_round: R3`  
`last_compiled: 2026-09-26`

## 1. Syfte

Planen ska göra det möjligt för Claude Code att genomföra **långa autonoma implementera–testa–reparera-pass** med minimal Lars-intervention och utan att detaljstyra teknisk HOW.

Planen äger:
- sequencing,
- Gate boundaries,
- entry/exit,
- hard invariants,
- verifieringsnivå per fault class,
- approvals,
- stop conditions,
- handoff-evidens.

Planen äger **inte**:
- produktkrav som redan ägs av `VOICE_CAPTURE_SPEC.md`,
- implementationens klass-/filstruktur,
- onödigt specifika bibliotek eller codecval,
- rätt att göra live/provider/Drive/GCP-writes utan separat Gate-approval.

## 2. Viktig sequencing mot nuvarande projekt

Denna plan får inte starta som kodmission bara för att dokumenten finns.

Vid sammanställning är Voice Capture en separat framtida produktmission. Före implementation måste `PROJECT_STATE.md` uttryckligen visa att Voice Capture är aktiv/prioriterad Mission eller att Lars har fattat ett materiellt reprioriteringsbeslut.

Nuvarande Storage/feedback-Gate-approvals får aldrig återanvändas som Voice Capture-approval.

## 3. Verifierad review-startstate

Claude R1/R2 verifierade read-only mot repo `aa823027e655e8b0468b55368c8bd94c24eae9ec`:

- repo har befintlig `CLAUDE.md` med live/stale checkout-regler,
- JVM-baseline rapporterad som 458 tests, 0 failures, 2 expected skips,
- ingen befintlig `androidTest`-infrastruktur,
- CI kör JVM/rules/gate men ingen emulator,
- diktatets Room är v9,
- diktat har en ASR- och en LLM-konfigurationsplats,
- `AudioRecorder` skriver rå PCM och befintlig WAV-konvertering använder en väg som inte är lämpad för lång mötescapture,
- `AccessSession`/process restart skapar en relevant identitets-/capture-livscykelgräns,
- BubbleService äger befintlig diktatmikrofonväg.

All denna state ska verifieras igen vid Mission START; repo kan ha ändrats.

## 4. Claude-autonomi — vad planen avsiktligt lämnar öppet

Claude får inom en Gate själv välja och ändra:
- klass-/paketstruktur,
- codec/container inom verifierade API-krav,
- segmentformat,
- state-machine-representation,
- Room schema för separat capture-store,
- work scheduling,
- adapterinterfaces,
- fake-/test-harness-design,
- cache/progressive playback-strategi,
- dependency injection-seams,
- internal retry/backoff,
- Compose-komponentstruktur,

så länge:
- Gate-invariants uppfylls,
- befintligt Diktat inte ändras utanför godkänd konfliktbox,
- ingen material-change trigger uppstår,
- verification faktiskt bevisar DoD,
- repo-`CLAUDE.md` följs.

Claude ska inte be Lars om trivial HOW.

## 5. Teststrategi — så lite manuell testning som möjligt

### 5.1 Automationspyramid

Prioritet:
1. pure JVM/unit,
2. Room/Robolectric där korrekt,
3. Roborazzi/screenshot,
4. emulator/instrumentation,
5. ADB-driven fysisk telefon,
6. live provider/Drive-probe i exakt Gate,
7. mänsklig subjektiv kontroll bara när maskintest inte kan avgöra kvalitet.

### 5.2 Fysisk telefon

Telefonerna används som testtargets, inte som manuella checklists.

Automatisera där möjligt:
- install/upgrade,
- start/stop,
- debug commands,
- synthetic audio source,
- screen off/on,
- Doze,
- network loss,
- process kill,
- reboot,
- state dump,
- log-filter utan känsligt innehåll,
- artifact assertions.

R1 identifierade två kända måltelefoner. G0 ska verifiera aktuella device IDs/Androidversioner och får inte anta att de är oförändrade.

### 5.3 ADB fault classes

Planerad automation ska kunna skilja:
- `kill -9`/process death,
- `am force-stop`,
- reboot,
- Doze,
- nätbortfall,
- skärm av,
- runtime permission deny,
- låg storage via fake/test seam,
- mikrofonkonflikt,
- identitetsbyte.

De är inte utbytbara bevis.

### 5.4 Manuell testbudget V1

Målet är högst:
- Google/Drive consent + mappval när den Gate körs,
- initial inspelning av Lars privata speaker corpus,
- ett kort real-mic-pass per fysisk telefon,
- ett fast subjektivt clean-fixture-pass per ny clean-provider,
- ett verkligt bordsmöte som release sanity om automatiska akustik-fixtures inte täcker miljön.

Allt annat ska i första hand automatiseras.

## 6. Gemensamma Gate-regler

Varje Gate ska avslutas med:
- exakt branch/ref,
- vad som ändrats,
- tester/kommandon som faktiskt körts,
- utfall,
- fault classes som bevisats,
- vad som **inte** bevisats,
- data-/provider-/kostnadseffekter,
- kvarvarande risk,
- nästa rekommenderade Gate,
- `Next step routing: SAME | NEEDS_ROUTING`.

Ingen Gate stängs med formuleringen "bör fungera".

### 6.1 STOP

Claude stoppar när:
- produkt WHAT måste ändras,
- ny permission/datapart/external exposure krävs,
- Gate-approval saknas,
- crash-loss-bound >5 s krävs,
- Room v9 måste migreras,
- Diktat måste ändras utanför definierad konfliktbox,
- live target/provider inte kan verifieras,
- repoevidens motsäger Gate-planens centrala antagande,
- samma failure upprepas utan ny evidens.

### 6.2 Repair loops

Inom samma Gate får Claude autonomt:
- reproducera,
- instrumentera,
- fixa,
- köra smal test,
- köra relevant bred regression,
- self-reviewa,
- upprepa tills DoD eller STOP.

Ingen ny ChatGPT/Lars-barriär för interna delsteg.

---

# PHASE 0 — PRE-IMPLEMENTATION FEASIBILITY

## G0 — Fresh baseline + risk-reducing spikes

### Mission goal

Eliminera sena blockerare innan featureimplementation och lås endast de tekniska constraints som måste vara kända tidigt.

### Entry

- Voice Capture har uttryckligen prioriterats som aktiv Mission.
- Repo/worktree/branch/base verifierad.
- Repo-`CLAUDE.md` läst.
- Ingen live/external probe körs utan separat G0 A4-underbox.

### In scope — read-only/A3

1. Re-verifiera aktuell repoarkitektur/testbaseline.
2. Kartlägg befintliga audio/access/storage/provider seams.
3. Bygg eller kör en minimal **lokal** capture-spike där detta kan göras utan extern mutation.
4. Mät crash-loss-arkitektur på båda fysiska telefonerna.
5. Mät mikrofonkonflikt/samtidighet över relevanta API/enheter.
6. Verifiera lokala Calendar Provider-konton/synkflagga på Lars telefon — metadata endast, inga eventtexter.
7. Utvärdera providerkandidater mot:
   - typed timestamps,
   - diarisation,
   - sessionstabil speaker mapping,
   - långsession/chunking,
   - async job/idempotens,
   - språk,
   - kostnad,
   - databehandling.
8. Om Lars-ID on-device/provider-metod är aktuell: teknisk spike utan att göra funktionen V1-blockerande.

### G0 A4-underbox — separat approval

Kan om Lars godkänner exakt box omfatta:
- begränsad provider-probe mot privat fixture/korpus,
- explicit maxkostnad,
- Drive/Picker API-aktivering eller annan nödvändig testkonfiguration,
- OAuth Android client för **det certifikat som testbuild faktiskt använder**,
- testmapp i Drive,
- välja mapp via mobil picker,
- skapa understruktur + testfil,
- läsa tillbaka och radera testartefakt enligt godkänd cleanup.

G0-underboxen får inte bredas till produktionsmigrering eller generell Drive-deploy.

### Required measurements

#### Crash-loss

- definiera mätmetod,
- kör `kill -9`/motsvarande flera gånger vid olika punkter,
- mät faktisk förlust,
- mål ≤5 s.

#### Memory

- verifiera att vald långsessiondesign inte behöver ladda hela mötesaudio i RAM,
- dokumentera peak/minnesmönster på minst en lång syntetisk session.

#### Calendar

Endast:
- account/calendar identifiers som behövs för feasibility,
- calendar type,
- sync/enabled metadata.

Ingen event content i G0 read-only.

#### Drive

Verifiera den kritiska frågan:
- ger vald mobile Picker + scope faktisk rätt att skapa den tänkta sessionstrukturen under vald root?

### Exit / DoD

Decision record ska låsa:
- crash-loss bound,
- recovery unit-formatprincip,
- minnesconstraint,
- meeting capture boundary,
- providerklass(er) som är tekniskt möjliga,
- diarisation/chunking-kontrakt,
- Drive picker/root feasibility,
- CalendarContract feasibility,
- om Lars-ID får gå vidare till G-ID och med vilken kandidatteknik.

### STOP

- crash-loss >5 s och ingen realistisk väg inom boxen,
- Drive-root-UX kräver material WHAT-change,
- nödvändig provider saknar långsession/speakerstabilitet utan lösbar reconciliation,
- bred permission krävs utöver godkänd produktmodell,
- V1 kräver ny extern datapart som inte godkänts.

---

# PHASE 1 — V1 FOUNDATION

## GF — Foundation + automation harness

### Mission goal

Bygg den tekniska bas som senare Gates kan lita på utan att blanda in full featurefunktion.

### Entry

- G0 stängd.
- Foundation-schema/permissions definierade.
- Exakt Gate-approval finns för manifest/schema/ownership-delta om det träffas av Gate-policy.

### In scope

#### Test infrastructure

- instrumentation eller ADB-driven testyta som kan bevisa real Android fault classes,
- AVD coverage minst API 28 + current, och API 32 där det ger relevant platform boundary,
- debug-only synthetic audio source,
- debug-only machine-readable session state,
- debug commands bakom `DUMP`,
- scripts för device run/failure collection.

CI-emulator är **inte** V1-krav. Lokal emulator/device-evidens ska redovisas som sådan.

#### Feature flag

- default off,
- only Lars provisioning in V1,
- flag off = inga UI/runtimeeffekter enligt spec,
- debug build kan styra flaggan för test,
- ingen admin-claim-reuse.

#### Capture persistence

- separat Voice Capture-persistens per `StorageRoot`,
- lazy creation först när flaggan är enabled och funktionen används,
- egen schema version,
- egen downgrade guard/exported schema om Room används,
- huvud-DB v9 orörd.

#### Provider config foundation

- meeting/clean providerconfig är additiv,
- diktatets `asr_*`/`llm_*` rörs inte,
- secrets reduceras aldrig till UI/log state,
- capabilityfacts hålls testbara.

#### Lifecycle

- capture service/lifecycle boundary,
- root locking,
- account-change contract,
- microphone arbitration seam,
- notification channel/FGS declarations.

#### Manifest

Minsta nödvändiga delta.
Kontraktstest ska minst förbjuda om inte senare Gate ändrar beslut:
- `READ_CONTACTS`,
- `READ_PHONE_STATE`,
- onödiga breda storage permissions.

`READ_CALENDAR` får deklareras om GK-designen kräver det men runtimeprompt får inte visas när flaggan är av.

### Autonomous design freedom

Claude väljer:
- separat Room vs annan lokal persistens om samma invariants bevisas,
- serviceklass/layout,
- arbiter-design,
- debug protocol,
- exact instrumentation harness.

### Required verification

- full JVM-suite med cache-safe run enligt repo-regler,
- befintliga Room migration/downgrade guards gröna i samma run,
- nya persistence tests,
- real Android SQLite create/open/reopen på API 28 + current,
- feature flag side-effect tests,
- manifest contract,
- release build/debug receiver absence,
- process recreation tests,
- secrets absent from logs/state serialization.

### Exit evidence

- reproducible script för emulator/device harness,
- schema artifact,
- feature-flag proof,
- no-dictation-regression evidence,
- known limitations.

### STOP

- v9 måste ändras,
- flagga av kan inte göras side-effect-free,
- root isolation kräver weakening av account model,
- capture service kräver permission som spec förbjuder.

---

# PHASE 2 — V1 CORE CAPTURE

## GC — Crash-safe capture, timeline, import och recovery

### Mission goal

Leverera robust capture primitives som både Meeting och Quick Note kan bygga på.

### Entry

GF green.

### In scope

- long-session audio writer,
- crash-safe recovery units,
- bounded-memory conversion/packaging,
- pause/resume,
- marker timestamps,
- gap timeline,
- low-storage handling,
- import copy,
- incomplete-session rediscovery,
- account/root lifecycle handling,
- microphone conflict handling.

### Hard invariants

- loss bound enligt G0,
- aldrig persistent meeting/Quick Note temp-audio i `cacheDir`,
- V1 markers = timestamps only, no comments/rename,
- dictation never interrupts meeting,
- no `READ_PHONE_STATE`,
- root never changes mid-session,
- external identity/process death falls into crash-recovery semantics.

### Automated device acceptance

På emulator/fysisk enhet där relevant:

1. 60+ min syntetisk capture med bounded memory.
2. `kill -9` under capture; recover inom loss bound.
3. reboot med ofullständig session.
4. screen off.
5. background.
6. pause → screen off → resume from notification.
7. Doze.
8. network off hela capture.
9. low-storage fake path.
10. import source deleted/moved after import.
11. marker ordering.
12. multiple gaps.
13. dictation tries to start during meeting.
14. user-initiated account switch while recording.
15. external identity-change/process restart.
16. app upgrade with unfinished/recoverable session when technically applicable.

### Physical-phone acceptance

Båda måltelefonerna:
- long synthetic run,
- kill/recovery,
- screen off,
- Doze/OEM,
- microphone conflict,
- one short real-mic recording.

### Exit

Capture primitives är green utan provider/Drive.

---

## GQ — Snabbanteckning vertical slice

### Mission goal

Göra Snabbanteckning användbar end-to-end oberoende av Meeting/Drive.

### Entry

GC durable temp capture available.
Existing approved ASR path available for Lars environment.

### In scope

- Quick Note start/stop,
- profile selection,
- collection priority,
- transcription + profile processing,
- durable retry audio,
- local note store,
- title,
- auto clipboard,
- edit,
- `Kopiera igen`,
- 60-day unfiled cleanup,
- bulk,
- search/filter,
- versioned export/import.

### Provider rule

V1 Quick Note får använda befintlig dictation ASR-konfiguration om det inte kräver att diktatets konfiguration migreras eller ändras.

### Automated verification

- success,
- ASR failure retains audio,
- local save failure retains audio,
- retry success deletes temp audio only after text durable,
- offline queue semantics,
- clipboard write,
- later edit leaves clipboard untouched,
- Copy Again,
- collection priority,
- bulk move/delete,
- 60-day unfiled only,
- export/import roundtrip + hash/version,
- flag off no side effects.

### Human/device

En kort real-mic smoke + verifiering att clipboard workflow faktiskt fungerar på Lars avsedda telefon/PC-kedja om cross-device clipboard är beroende av extern OS/vendorfunktion.

### Exit

Quick Note kan shipas för Lars även om Meeting senare Gate stoppas, om produktrelease väljer det.

---

# PHASE 3 — MEETING TRANSCRIPTION

## GT — Typed meeting transcription, versions och clean

### Mission goal

Bygg provideroberoende meeting processing utan att låta providerintegration styra produktens datamodell.

### Entry

GC green.
G0 provider decision record available.

### In scope

- adapter contract,
- typed raw segments,
- provider original preservation,
- async job state,
- cost-safe idempotency,
- diarisation/chunk reconciliation,
- raw versioning,
- corrected raw,
- primary selection,
- speaker mapping per raw version,
- clean source anchors,
- meeting-specific LLM config,
- retry per stage,
- cost display rule,
- manual speaker naming, audio clip, `Detta är jag`.

### Hard invariants

- provider raw immutable,
- corrected/raw identity mapping version-scoped,
- no silent identity transfer,
- clean source anchored,
- no audio to LLM for clean,
- no silent provider/LLM switch,
- duplicate provider submit bounded and testable,
- cost unknown allowed; cost never blocker.

### Fake-provider acceptance

Build deterministic fakes for at least:
- sync provider,
- async job/poll provider,
- transient failure,
- timeout,
- duplicate-submit window,
- raw succeeds/clean fails,
- chunk boundary speaker continuity,
- missing diarisation capability,
- unknown price,
- known dated price.

### JVM/automated

- serialization/version model,
- adapter capability evaluation,
- idempotency counter proves one logical job,
- retry polls existing job,
- clean anchors survive edit/regenerate,
- primary switch does not move speaker IDs blindly,
- Person N stable,
- `Detta är jag`,
- overlap/uncertainty normalization,
- mixed languages.

### GTL — live provider sub-Gate

A4 only after explicit approval:
- exact provider,
- exact model(s),
- exact corpus,
- max cost,
- data sent,
- cleanup expectations.

Verify once per adapter/model contract:
- real response parsing,
- timestamps,
- diarisation,
- long/chunk behaviour,
- async polling if applicable,
- observed cost if available.

No automatic switch to a second paid provider.

### Exit

At least one adapter satisfies V1 meeting contract.

---

## G-ID — Automatic Lars recognition, non-blocking parallel Gate

### Mission goal

Add automatic Lars labels only if objective evidence reaches a locked threshold.

### Entry

- GT segment/speaker contract stable.
- private corpus prepared once.
- threshold locked **before** evaluation.

### Threshold record

Must state:
- negative corpus size/composition,
- positive corpus size/composition,
- observed false-positive criterion,
- required recall,
- confidence threshold,
- what counts as overlap/noise exclusion.

Default quality posture:
- zero observed confident false Lars labels on locked negative corpus,
- predetermined recall target,
- no claim that this proves zero real-world error.

### Candidate HOW

Claude may evaluate:
- on-device embedding,
- provider known-speaker capability,
- hybrid mapping,

without changing V1 release dependency.

### Privacy

- Lars-only root,
- deletable,
- not exported,
- not in `session.json`.

### Exit

If threshold passes: may merge into V1.
If not: RESULTAT/HANDOFF, V1 continues without it.

---

# PHASE 4 — CONTEXT + DRIVE

## GK — Calendar Provider + user-initiated Contacts

### Mission goal

Add low-friction speaker/event context with minimal permission.

### Entry

G0 proved relevant local calendars exist; otherwise GK calendar branch is skipped and Calendar API is deferred.

### In scope

- READ_CALENDAR runtime flow only for enabled Lars feature,
- events around session time,
- attendees,
- event linking/editing,
- candidate ranking,
- user-initiated contact pick,
- no contact indexing,
- no calendar writes.

### Automated verification

- permission deny,
- permission revoke,
- multiple calendars,
- no matching event,
- overlapping events,
- attendee-less event,
- candidate ranking,
- selected contact only,
- flag off no permission prompt.

### Emulator/device

Seed calendar/contact test data where platform supports it.
One Lars-device read smoke with intentionally non-sensitive test event if needed.

### Exit

Context candidates are additive; meeting works without them.

---

## GD — Drive archive, sync, retention och playback

### Mission goal

Make Drive a reliable archive without making sync success equivalent to local processing success.

### Entry

- G0 Drive picker/root probe green.
- exact OAuth client/cert for current build verified.
- GT data model stable.
- explicit Gate/A4 approval for live Drive test root and operations.

### In scope

- separate Drive authorization request,
- root binding to storage-root,
- stable session folder/identity,
- resumable upload strategy,
- persistent upload/job state,
- remote verification,
- `session.json` v1,
- idempotent metadata updates,
- uid-bound workers,
- local retention,
- Keep on device,
- local delete vs everywhere delete,
- playback when local audio absent,
- offline availability state,
- optional manual offline download.

### Drive invariants

- minimum scope from G0 decision,
- no silent cross-account Drive use,
- no local cleanup before remote verified,
- retention never deletes remote,
- remote delete result verified,
- session metadata update is idempotent,
- marker metadata is timestamps only in V1,
- local retention/biometry/secrets not in `session.json`.

### Fake transport tests

- resumable interrupted upload,
- duplicate worker,
- uid change mid-run,
- remote file exists but wrong size,
- checksum mismatch,
- metadata update conflict,
- delete partial failure,
- playback network loss,
- local purge after verification only.

### Live test-root acceptance

Within exact approved box:
1. authorize,
2. bind test root,
3. create session structure,
4. upload audio + raw + clean + session metadata,
5. interrupt network and resume,
6. read/verify remote metadata,
7. purge local copy,
8. Play from Drive,
9. update title/speaker/primary transcript and verify `session.json`,
10. delete local only,
11. delete everywhere test session and verify remote outcome.

### Data-sync implementation freedom

WorkManager/resumable sessions are strong default, but `FOREGROUND_SERVICE_DATA_SYNC` absence is a preference, not a product invariant. If Claude chooses another mechanism it must show better platform/verification evidence and stay within approvals.

### Exit

Drive behaves as durable archive for approved V1 test root.

---

# PHASE 5 — V1 UX + CROSS-CUTTING ACCEPTANCE

## GU — Library/UI integration

### Mission goal

Integrate Quick Note + Meeting into coherent app UX without changing Diktat navigation unnecessarily.

### In scope

- home entry points,
- feature-flag visibility,
- meeting record screen,
- note result,
- shared library,
- filters/search,
- collection management,
- meeting detail,
- clean default view,
- status/error/retry surfaces,
- speaker resolution,
- playback + transcript highlighting,
- Drive/local state,
- delete dialogs,
- accessibility/content descriptions.

### Screenshot tests

Roborazzi baseline changes must be reviewed intentionally; never record mode by accident.

Scenarios:
- flag off baseline existing UI unchanged,
- flag on home,
- recording,
- paused,
- post-capture processing,
- clean view,
- Person N unresolved,
- offline/waiting,
- needs action,
- Drive-only,
- delete confirmation,
- Quick Note result,
- bulk selection.

### Exit

UI state is deterministic and errors are actionable.

---

## GE — V1 cross-cutting E2E

### Mission goal

Prove only the fault classes that require multiple Gates together.

### Entry

GC, GQ, GT, GK (if applicable), GD, GU green.
G-ID optional.

### Required automated/device scenarios

1. Record offline → stop → reboot/process death → reconnect → transcribe → clean → Drive.
2. Account switch attempt during recording → stop/save contract → correct root.
3. External identity/process change during recording → recover within loss bound, no cross-root leak.
4. Existing v9 user upgrades app with flag off → Diktat unchanged.
5. Flag off user launches app → no new runtime prompts/db/work/network.
6. Meeting capture while dictation bubble exists → no meeting interruption.
7. Raw succeeds → clean fails → clean retry only.
8. Drive upload partial failure → local data retained.
9. Local retention after verified remote only.
10. Drive-only meeting → Play with no manual pre-download.
11. Primary transcript switch → clean source/version relation remains correct.
12. Phone reboot with queued provider/Drive work → resumes only for correct uid/root.

### Final physical-device fault classes

- both target phones,
- screen-off long run,
- kill/recovery,
- OEM background,
- real microphone short pass,
- microphone conflict,
- one phone-call/interruption sanity if this cannot be validly emulated.

### Human subjective acceptance

Only:
- fixed real meeting fixture clean readability,
- speaker clip usefulness,
- one real-world sanity session if needed.

### V1 closure report

Must explicitly state:
- automatic Lars-ID shipped or not,
- Calendar local path active or skipped,
- exact Drive/provider adapters validated,
- remaining V1.1 work,
- manual actions actually required,
- what is still not proven for broader beta.

---

# PHASE 6 — V1.1

## G9 — Speaker-profile privacy + feasibility

### Mission goal

Choose a privacy-appropriate representation for persistent other-person recognition before implementing it.

### Entry

V1 stable.

### Read/research

- biometric/privacy implications,
- on-device vs provider representation,
- audio sample vs embedding,
- encryption,
- consent wording,
- deletion,
- false-match behavior,
- backup feasibility.

### Spike

Allowed locally; any provider/private voice probe requires explicit Gate/A4 approval.

### Exit

Decision record with:
- representation,
- enrollment rules,
- confidence policy,
- deletion,
- backup boundaries,
- test corpus.

### STOP

No implementation if the design cannot meet explicit opt-in and deletion/restore requirements.

---

## G10 — Local persistent people + identity management

### In scope

- explicit `Kom ihåg rösten`,
- multi-segment enrollment,
- local profile store,
- high-confidence matching,
- suggestions/corrections,
- rename/delete,
- duplicate merge with explicit confirmation,
- historical display-name layer,
- no auto-training from wrong matches.

### Automated verification

- consent/enrollment state,
- incomplete enrollment,
- false-match remains Person N,
- correction does not train,
- delete stops future recognition,
- rename updates identity layer only,
- merge preview count + confirmation,
- raw unchanged.

### Device

Private fixture corpus via automated test runner.

---

## G11 — Optional encrypted backup + restore

### Mission goal

If V1.1 includes speaker-profile backup, prove restore rather than upload-only status.

### Gate

Exact Drive/crypto/live test box required.

### Requirements

- opt-in,
- encryption,
- no secret in repo/log,
- backup status only after remote verification,
- restore on clean local profile store,
- delete semantics,
- local use continues when backup fails.

### STOP

Do not show `Säkerhetskopierad` until a restore path is actually demonstrated.

---

## G12 — Drive reindex + advanced provider comparison

### Part A — archive reindex

- fresh/empty local capture index,
- connect existing V1 Drive root,
- enumerate/reconstruct sessions from `session.json`,
- missing artifact handling,
- resume partial reindex,
- no archive rewrite required for ordinary V1 sessions.

### Part B — provider comparison

- second configured adapter,
- separate immutable raw,
- side-by-side text differences,
- speaker-attribution differences,
- primary selection,
- cost metadata richer than V1 where available.

### Part C — playback optimization

True streaming may be implemented if it materially improves V1 playback; it is not required if current Play-on-demand path is good.

### Acceptance

- reindex roundtrip from real V1 fixture archive,
- comparison data stays version-scoped,
- no segment blending,
- no silent provider switch.

---

## G12K — Conditional Calendar API fallback

This Gate exists only if G0/GK proved local Calendar Provider insufficient.

Requires separate:
- OAuth/privacy review,
- scope approval,
- token lifecycle handling,
- account binding,
- offline/fallback behavior,
- live smoke.

If local Calendar Provider satisfies Lars use case, this Gate is skipped.

---

## G13 — V1.1 end-to-end acceptance

Required:
- all active V1.1 features,
- full V1 regression,
- speaker profile delete/restore,
- Drive reindex from empty local state,
- provider comparison,
- Lars-ID if still part of active scope,
- no feature expansion to other beta users without separate decision.

---

# 7. Acceptance matrix — billigaste giltiga nivå

| Fault class | Lowest valid evidence |
|---|---|
| Retention, 60-day rule, ranking, timeline math, idempotency, versioning, source anchors, cost-rule, `session.json` schema, flag-off side effects | JVM/unit |
| Capture persistence, lazy creation, root isolation | JVM/Robolectric + emulator real SQLite |
| Existing v9 guards unchanged | Existing JVM guards in same full run |
| UI states | Robolectric + Roborazzi |
| Manifest forbidden permissions/debug-only surface | JVM against merged manifest + release artifact check |
| Runtime permission flow, FGS start, process recreation, contact picker | Emulator |
| Loss-bound, long screen-off, Doze/OEM, notification resume, mic arbitration, identity-change during capture | Physical device via ADB |
| Real mic quality | Short physical-device pass |
| Provider format/diarisation/job semantics | Live provider within approved GTL |
| Drive auth/picker | G0 live micro-probe |
| Drive resumable/checksum/delete/play | Fakes + one approved live test-root pass |
| Lars-ID | Automated private corpus after locked threshold |
| Clean subjective readability | Fixed human-reviewed fixture set |

## 8. Branch/PR discipline

- `main` protected; branch + PR.
- Never `--no-verify`.
- Respect repo `CLAUDE.md` build/JAVA_HOME/Gradle rules.
- New guards that read files as data must declare those inputs so Gradle cannot return false green from cache.
- Screenshot baselines are reviewed, never silently re-recorded.
- CI merge commit must be green before merge claim.
- Gate evidence belongs in the smallest durable repo artifact that has clear long-term value; do not litter repo with redundant reports.

## 9. Long-pass autonomy contract

Within one Gate Claude may continue for a long pass when:
1. same Gate/DoD,
2. no new permission/external target,
3. no material product change,
4. risk mode unchanged,
5. branch/environment still correct,
6. evidence narrows the hypothesis,
7. repair loop is productive.

Checkpoint only for:
- Lars decision,
- Gate approval,
- material architecture/product conflict,
- repeated failure without new evidence,
- external target ambiguity,
- recovery boundary.

## 10. Approval budget V1

Expected explicit Gate approvals:
1. **G0 live micro-probe** — provider corpus + Drive picker/root test + any required GCP config.
2. **GF** — manifest/persistence/ownership foundation if Gate policy requires.
3. **GTL** — live meeting provider contract.
4. **GD** — live Drive test-root operations.
5. **G-ID** only if its corpus/provider action is not already covered by another exact box.

Goal: 4–5 approvals, not per-step confirmation.

## 11. V1/V1.1 documentation discipline

After each material Gate:
- update `PROJECT_STATE.md` only when project delivery state truly changed,
- update spec only if Lars approved a material product change,
- update plan only when Gate evidence invalidates sequencing/verification,
- do not convert implementation HOW into canonical product constraints after the fact.

## 12. Final plan close criterion

Planen är genomförd när:
- V1 har closure evidence enligt GE,
- alla live claims har motsvarande live evidence,
- Diktat regression är green,
- manual test burden dokumenterats,
- V1.1 genomförts endast för aktiverat scope,
- inga gamla draft-planer konkurrerar med denna canonical plan.
