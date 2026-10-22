# P5a — snapshot backup и selective compact

Работа начата21 октября2026. База — проверенный актуальный `origin/master`
`93557e1aa4e0d6d9685f33afc2ac75c8779207bd`, пользовательский merge PR №10.
Содержит P1–P4, Windows fix `ff4981f`, icon demo. Рабочее дерево было чистым,
AGENTS.md в репозитории/родителях не найден. Ветка
`codex/copper-scroll-p5a-copy-compaction`, отдельный Draft PR в master, без merge.
Опубликован [Draft PR №11](https://github.com/fantadrom/CopperScroll/pull/11).
Реализация: commit `9942e45c7aa5569dff4fe8cfda061ef6853b85f4`,
Git tree `941192c024dae36dfc46acf980dda120a8785dba`; последующий commit
добавляет только эту публикационную запись. Master не изменён.
Прочитаны design/format/resolution/test/prototype/P4-extension, актуальные P1–P4
reports, IO/owner/local state/T/VFS/reader/recovery и runtime gates.

**Исторический статус агента при реализации: headless synthetic/model P5a; Android NOT_RUN.**
Текущая приёмка P5a зафиксирована ниже в разделе
[пользовательского подтверждения](#пользовательское-подтверждение-android-22-октября-2026);
прежние результаты/открытые на тот момент условия ниже сохранены как история.
Ни прежние пользовательские PASS, ни build/audit не заменяют P5a runtime. Provider
allowlist по-прежнему пуст. P5 целиком не завершён: rekey с новым ZDK — P5b.

## API, авторизация и ресурсы

- `TransactionCore.authorizeMaintenance()` выдаёт opaque capability из реального
  attached key/role и текущего owner. Самодельная capability/другой core, decoy,
  revoked/Locked/Unknown отклоняются до callbacks, grants и KDF. Поля isMain нет.
- `MaintenanceCoordinator.begin → credentials → confirm → compact` — headless
  протокол. Immutable request связывает opId, source fingerprint/S, выбранные зоны,
  destinations. Proposal владеет только service decoy handle; S1 ticket очищается
  сразу после проверки. Confirmation одноразовая; изменение source требует нового
  запроса/credentials/confirm. Неверный main вместо decoy не login-success.
- `AuthorizeMain → Destinations → Credentials → Confirm → DrainAndFreeze → Preflight
  → Backup → BuildCandidate → VerifyCandidate → PrepareSwitch → AcceptSwitch
  → ResolveAndClose`. Отсутствующие grants дают SetupRequired и закрытие сессии;
  late result не может использовать прежнюю capability. Picker/UI не реализован.
- Pending prepared save/CommitUnknown дают Busy/отказ; очередь не теряется и Unknown
  не replay-ится. Caller завершает/reconciles T до новой команды. После успешного
  drain весь workflow выполняется одним `owner.execute` под core serialization;
  source process/advisory lease не отпускается между зонами. Дополнительный
  maintenance hold сохраняется через abort metadata IO и cleanup после выхода
  основного callback; timeout/recovery/другой worker не освобождают lease в этом окне.
  Admission закрывается внутри core `finally` до отпускания сериализатора.
  Диагностика изменённого S тоже читает файл внутри активного permit, не в cleanup.
  Другой repository/core/coordinator того же документа не получает writer.
  `revoke` идёт без core mutex.
- ResourceOwner разделяет revoke и IO lifetime. Timeout не закрывает FD, активный
  syscall держит lease; cleanup только после возврата execute. `reopen` закрывает
  старый FD/lock и открывает новый под непрерывным process lease, проверяет identity;
  второй FD исходника не открывается. Ошибки cleanup сохраняются suppressed, оба
  owners закрываются; session/service keys очищаются и при pinned Unknown IO-owner.
- `SnapshotCopier` использует тот же owner/RandomAccessStore; самостоятельный
  main-authorized `backup` проверяет открытую поддерживаемую зону, закрытую только
  относительно S. Низкоуровневый copier не выдаёт права decoy на compact.
- `MaintenanceDocuments` — порт раздельных read-only grants/destinations/free space,
  exclusive create, reopen, identity и ограниченного cleanup. В P5a есть только
  Synthetic/JvmSynthetic profiles, нет production SAF adapter/profile.
  `JvmMaintenanceDocuments` использует существующий FileChannelStore в собственных
  тестовых roots. File force/parent force/reopen Linux — граница host модели,
  не сертификат Windows/Android/provider power-loss.

Core хранит один текущий request ticket и один operation-owned service context.
Новый begin (включая другой экземпляр coordinator) инвалидирует старые callbacks/
confirmations и закрывает старую proposal. Повтор credentials закрывает прежнюю
proposal до проверки нового секрета. Admission повторно проверяет ticket/context
под core serializer **до freeze**; поздний отказ старой команды не закрывает новую
main-сессию. Proposal зарегистрирована в ResourceOwner, поэтому revoke во время
KDF/readback или потеря позднего результата всё равно очищают service handle при
quiescent release. Терминальный maintenance очищает key resource и token cache;
Unresolved IO остаётся pinned без прежних session/service keys.

## Данные, независимая проверка и версии

Потоковая копия ровно committedLength S, буфер64KiB, source не меняется; orphan tail
не копируется. UTC+UUID имена и CREATE_NEW не overwrites. Alias проверяется через
реальную StoreIdentity, не URI/display name/containerId. До VerifiedCopy:
полные length/SHA source/destination, оба каталога/ranges/record hashes, выбранные
AEAD/refs, force/identity/reopen/readback и повторный fingerprint S.

Выбор default=все existing зоны; empty/impossible запрещён. Decoy credentials
проверяются именно против expected zoneId/role/envelope/MAC/root до artifacts.
Selected closure строится полным reader VerifyAll, с trash/settings/индексами,
note/card/media chunks. Unselected переносится **весь** текущий catalogue record
set, включая obsolete; borrowed main handle при decoy-only не входит в key map
compactor и не вызывает main plaintext traversal. Unknown selected closure —
отказ до backup. Unknown unselected plaintext переносится opaque только при known
outer/envelope/S1/KDF/record framing. Fixture schema99 имеет собственный знающий
oracle, а не объявляется открываемой прежним reader.

Builder копирует ciphertext/nonce/recordId/hash/LogicalRefs неизменно и rebuild
только public catalogue pages/offsets. ContainerId/zoneId/sequence/root/logical MAC,
объекты/props/dаты/revisions прежние. Новый instanceId/generation=S+1 (overflow
отклоняется), оба header slots одинаковы; minor1.1/1.2 сохраняется, миграции нет.
Формат контейнера/AEAD domains не расширялись.

Verifier **повторно читает новый файл**: сравнивает logical descriptor+MAC,
recordId/hash/length/actual bytes с S; заново вычисляет selected closure, hashes/
AEAD/refs, каталоги обеих зон. Из перечитанных pages/records строит физическое
разбиение: нет overlaps, кроме alignment нет gaps, padding нулевой, нет неучтённых
ranges, EOF=committedLength, оба headers согласованы. Manifest/offsets builder
не используются как доказательство. Hash/copy/traversal проверяют revoke между
блоками; остановка не требует закрытия FD из другого потока. Независимый reader с каждым синтетическим
секретом сравнивает raw logical metadata, rich text/todo, card/mask/date/hidden
values, media digest, trash/settings до/после.

Уточнение нормы P4: существующий Boolean `useAsUserTemplate=true` теперь распознаётся
reader1.2 и сохраняется вместе со всеми заполненными полями; несвязанный card save
его не сбрасывает. P4 writer по умолчанию false, setter/UI шаблонов не добавлен.
Это реализация уже выделенного поля, не новая схема/версия. Прежняя P4 negative
ветвь true-as-Unsupported переведена на действительно неизвестную payload version99;
unknown body/object/field schemas по-прежнему отказывают. Добавлен отдельный
положительный P5a fixture с filled template flag и значениями.

Coverage per-zone: `AeadReachable` либо `RelativeToSnapshot`. SHA/равенство S не
доказывают здоровье закрытой зоны, полноту злонамеренно изменённого каталога,
anti-rollback или защиту от подмены с пересчитанными публичными хешами. Повреждённая
closed media с пересчитанным catalogue hash остаётся relative-only и не вытесняет
последнюю более сильную копию. Случайный hash mismatch — отказ.

## Журнал, switch и restart

`LocalStateStore` выделен из существующей FaultLocalStateStore; одна атомарная
binding+journal запись, не отдельные prefs. Prepared только после credentials,
confirm/freeze/preflight. Стадии Prepared/BackupVerified/Building/CandidateVerified/
SwitchIntent/Activated и Aborted/Unknown. Pending artifact хранится с URI/реальной
identity сразу после известного create; fingerprints появляются после проверки.
Metadata ограничены65536B, URI/identities bounded, checksum не anti-rollback.
Секреты/ключи/plaintext/engine objects не сериализуются.

App-local codec получил явную version2 для document identity и pending artifact;
старые metadata version1 без новых полей читаются и канонически round-trip прежним
образом. Это **не** изменение portable container1.1/1.2. Legacy journal без identity
не считается достаточным для безопасного автоматического разрешения обслуживания.

Binding выбирает документ (URI + реальная identity + container/instance). Его
fingerprint — последний наблюдавшийся checkpoint, **не** обязательная sidecar-запись
каждой T. Обычная подтверждённая T может увеличить generation того же документа;
новый explicit open аутентифицирует текущую зону и допускает такой прогресс. При
том же generation fingerprint обязан совпадать; иной document/instance или откат
ниже checkpoint отклоняется. Это не полноценный anti-rollback.
`journal.source` хранит точный immutable S текущего обслуживания;
`previousBinding` сохраняет прежний checkpoint и может иметь более раннюю generation.
Файловая T по-прежнему не зависит от частного metadata ACK. Это проверено обычным
save → закрытие → openBound → compact и lost-ACK recovery без refresh sidecar.

После verified backup и whole candidate: durable CandidateVerified, затем
SwitchIntent; повторные grants/namespace identities/source/candidate fingerprints.
AcceptSwitch атомарен с Revoke на коротком monitor ResourceOwner, без IO под ним.
Revoke раньше — old binding. Accept раньше — durable решение может завершиться
Locked; late revoke даёт Unreported, потерянный ACK/неясный state даёт SwitchUnknown.
Только подтверждённый Activated возвращает MaintenanceCommitted. Успех тоже
заканчивает прежнюю сессию; никакого UI auto-open. Verified source/backup сохраняются.

При live Unknown source/candidate owners остаются pinned; новое обслуживание/writer
не допускается. `MaintenanceRecovery.resolve` требует explicit Source/Candidate,
заново credentials **main authority и selected зон**, metadata integrity, identities/
grants/profile/fingerprints, повторную crypto/closure/whole candidate проверку.
Не запускает copier/build повторно и не выбирает по mtime/имени/одному containerId.
Потеря доступа — AccessLost, изменённый S/candidate — Conflict, нет ключей —
NeedsCredentials, неизвестная долговечность — SwitchUnknown. Подтверждённое explicit
resolution закрывает owners и оставляет Locked; prior Unreported отмечается отдельно.

Для нового входа в управляемый binding/journal файл предусмотрен
`TransactionCore.openBound`: pending/corrupt/Unreported local state или несовпадающий
файл блокируют admission и требуют resolve. Низкоуровневые P3 fixture constructors
сохранены для синтетических тестов; будущий app opener обязан использовать openBound,
не создавать обходную политику из direct constructor. P6c ещё не подключал storage UI.
После crash собственные unverified partial artifacts могут быть сохранены для явного
разбора; неизвестная identity не удаляется наугад. Результат отдельно перечисляет retained и unverified artifacts (например, partial
backup при потерянном grant); они не получают статус здоровой копии. Production
retention не добавлен.

## Budgets, retention и размеры

Read/write/copy/hash buffer64KiB; чтение AEAD record≤1MiB+92, pages16KiB. Note
validation использует прежний bounded16MiB body, card≤4MiB и aggregate32MiB/open.
Public catalogue/closure maps — прежние bounded reader maps32MiB и дополнительно
P5a conservative1024B/record, максимум32768 records **суммарно обеих зон**; clone/
manifest maps и ciphertext buffers ограничены этим пределом. Предельный media chunk
count524288/512GiB из wire не означает, что целый такой граф проходит RAM budget
P5a: ResourceLimit до backup, encrypted disk scratch в P5a нет. Не обещается
миллион объектов, fixed RSS или физическое стирание всех JVM копий.

CopyRetention.plan заново проверяет identity/длину/full hash/каталоги inventory и
fresh копии перед расчётом удаления. Байт-в-байт та же ранее проверенная копия
сохраняет известный coverage; relative не становится AEAD. Непроверенный bool
здоровья не является публичным входом.

Pure managed-store retention policy: максимум256 описаний, минимум3 currently-valid
verified copies; только после свежей проверенной, без duplicates/подмены контейнера,
с сохранением strongest-known per-zone coverage и последней исправной. Невалидная
последняя не заменяет здоровую. Реального удаления пользовательских SAF файлов нет;
source и backup текущего обслуживания политика не удаляет.

MaintenanceSizes различает `S−C`, `2S+C`, `S+C`, old backups/scratch/partial/reserve
и unknown allocated bytes. C=S/C>S разрешены. У source отдельно учитывается
незакоммиченный tail: backup его не копирует, source сохраняет. Поэтому базовые
committed `2S+C` и reduction `S−C` дополнены `retainedFileLengths=2S+C+tail` и
разностью наблюдаемых размеров `S+tail−C`; прирост к исходному размещению остаётся
`S+C`. Это logical lengths, не released physical blocks. Новая успешная операция
использует scratch=0/partial=0; размер прежних backups неизвестен без внешней inventory и не
подставляется как0. `totalWithExtras` тогда null, а базовые2S+C/S+C точны. Reserve —
плановый запас, не физически выделенные blocks. Free-space preflight консервативен:
одна backup S + верхняя оценка candidate +1MiB reserve в каждом destination
(безопасно и при одном томе), ошибка позднего ENOSPC всё равно возможна.

## Матрица нормативных gates

Результаты ниже — synthetic/model/host, не Android/SAF/power-loss. Имена методов
указаны без повторения package `storage`; shared методы — в P5aChecks/P5aCoreTest.

| ID | Конкретная проверка/fixture/fault | Результат и ограничения |
|---|---|---|
| COPY-01 P0 | committedLengthCopyAndCoverage, selectiveZonesAndIndependentReaders; independent secret readers обеих зон; schema99 knowing oracle | Model; committed length/tail и per-zone coverage, не здоровье opaque plaintext |
| COPY-02 P0 | faultCopyBuildAndJournal, allIoBoundariesAndDurableRestarts, aliasesReopenCorruptionSourceReplacementAndUnknownOuter, aliasesToEarlierHealthyBackupAreNeitherWrittenNorDeleted | IO before/after/partial, ENOSPC/EIO, bit flip, grant/force/reopen/identity failure; source/старые copies не удаляются |
| COPY-03 P1 | retentionSizesAndFailures, retentionCoverageClosedCorruptionGenerationAndMapLimits, retentionRechecksInventoryAndFreshCopyBytes; UTC+UUID/exclusive create | Managed policy минимум3, strongest coverage, invalid fresh/space refusal; не production deletion |
| COPY-04 P0 | maliciousClosedDamageDoesNotAcquireAeadCoverage | Closed media ciphertext + пересчитанные публичные hashes; relative-only, selected full verify отказывает |
| COMPACT-01 P0 | selectiveZonesAndIndependentReaders, rich fixture | Main selected, decoy full including obsolete без decoy key, позднее independent decoy open |
| COMPACT-02 P0 | authorizationCredentialsAndNoEffects | Decoy/direct fake capability/Locked/stale deny до grants/KDF/artifacts; UI dispatch в P6c |
| COMPACT-03 P0 | allIoBoundariesAndDurableRestarts, everyJournalWriteForceRenameSyncReadbackAck | Обе зоны/граница зон/verify/switch, immutable source, нет partial ACK; synthetic crash, не device kill |
| COMPACT-04 P0 | independent reader oracle, candidate corruption/replacement и fault matrix | Reread multiset/logical/AEAD/partition, whole-file verification до accept |
| COMPACT-05 P1 | retentionSizesAndFailures, MaintenanceSizes C=99/100/101 приS=100 | S−C/2S+C/S+C/extras, source tail отдельным retainedSourceTailIsCountedSeparatelyFromCommittedSnapshot, allocated unknown; не физический freed space |
| COMPACT-06 P0 | selectiveZonesAndIndependentReaders selection={decoy} | Main unselected, main key отсутствует в compactor key map; main UI не открывает decoy |
| COMPACT-07 P0 | default both на rich двухзонном graph | Один snapshot/backup/candidate/switch; stale/deleted garbage удаляется только у selected; zoneSeq/nonce/refs прежние |
| COMPACT-08 P0 | authorizationCredentialsAndNoEffects, largeMediaAndMainOnlyCompatibility | Main-only1.1/1.2, empty/impossible selection, default existing zones |
| COMPACT-09 P0 | authorizationCredentialsAndNoEffects, kdfFailuresSetupPendingSavesAndStaleConfirmation | Wrong/cancel/main-as-decoy, service KDF OOM/unsupported fault;0 artifacts, no fallback |
| COMPACT-10 P0 | lowLevelSymmetryWithoutUnselectedKey: оба role fixtures, copy/compact selected-only key map, independent readers | Symmetric record relocation не выдаёт decoy command capability |
| MAINT-01 P0 | authorizationCredentialsAndNoEffects | Real role/owner capability, zero effects on denial |
| MAINT-02 P0, часть | kdfFailuresSetupPendingSavesAndStaleConfirmation SetupRequired/late capability | changedRequestAndCredentialsInvalidateEarlierConfirmationAcrossCoordinators; Headless grants→Locked→новая команда; Android picker/rotation остаются P6 |
| MAINT-03 P0 | pendingUnknownAndAcceptBeforeRevoke, pending save refusal, admissionAlreadyClosedBeforeErrorLeavesCoreAndHoldSurvives, abortMetadataIoStillOwnsLeaseAndBlocksRecovery, revokeSwitchAndExclusiveLease | Drain/reconcile-or-stop, lease весь workflow и blocked syscall; actual process locks также P3 regression |
| MAINT-04 P0 | aliasesReopenCorruptionSourceReplacementAndUnknownOuter, rich oracle/fault matrix | Source/candidate substitution и final verify stop; несотрудничающий writer вне cooperative lock guarantees |
| MAINT-05 P0 | revokeSwitchAndExclusiveLease | Revoke до accept, old binding, late completion не принимает switch |
| MAINT-06 P0 | pendingUnknownAndAcceptBeforeRevoke, restartAndLostAcknowledgement, journal matrix | Accept прежде revoke, Unreported/ACK loss/SwitchUnknown; Locked и pinned live lease |
| MAINT-07 P0 | capturedDurableStagesRestartWithoutReplay; crashAtCopyBuildAndNamespaceTransitions; restartRejectsBadStaleAndMissingJournalFiles | Каждая durable публикация, checksum/усечение/missing files/stale fingerprints; без эвристик и replay create |
| MAINT-08 P0, часть P5a | restartAndLostAcknowledgement, fresh credentials и openBound | Saved keys нет; новый explicit main/selected вход; rekey old/new secret часть остаётся P5b |
| MAINT-09 P0, часть | P5aOwnershipTest (включая revokeDuringCredentialsClosesMainOwnerAfterRealKdfQuiescence), cleanup/close-count, failedCandidateRecoveryRetainsAddressableOwnerForRetry, KDF faults, blocked IO/revoke, whole candidate errors | Quiescence/one close/suppressed, RAM-only service keys; Android leak/OEM/physical-memory tests P6b |
| MAINT-10 P1 | retention/sizes, read-only destination profiles, aliases/free space | Separate destinations, один verified backup/candidate; не измерения реального тома |
| DUR-01/02/03 P0, часть | existing FaultStore/FaultLocalStateStore, journal matrix, host FileChannel | File bytes/namespace/local metadata разные barriers; Linux fsync/reopen не Android power loss |
| DUR-04 P0, часть | только явные Synthetic/JvmSynthetic profiles; отсутствие production allowlist | Неизвестному SAF profile гарантия не назначается; настоящий gate P6a |
| LIFE-06/09 P0, часть | revokeSwitchAndExclusiveLease, pendingUnknownAndAcceptBeforeRevoke | Blocking synthetic syscall, timeout не release/second writer; Android lifecycle P6b |
| LEAK-01/04 P0, часть | codec safe metadata, test/target APK audit, operation ownership/cleanup | Нет UI/SavedState/plaintext scratch/logging; реальное Android leakage/security acceptance P6b |

MIGRATE/RESTORE не объявлены реализованными операциями P5a. REKEY-01…08/MAINT-08
rekey — P5b. Исходные P1–P4 reports/пользовательские receipts сохранены как история;
P4 принят пользователем на Honor15 и emulator17, не агентом и не автоматически P5a.

## Проверки, артефакты и команды

Среда агента: Linux, Gradle9.6.0 (нормативный wrapper/checksum), Oracle JVM
25.0.4.1+1-LTS-5, Java target11, AGP9.4.1, Kotlin2.2.10, SDK37/min31. Dependencies,
wrapper, SDK, optimization enable/scope, Startup/lifecycle и keep rules вручную
не менялись. Пакетные ABI rules генерируются прежним механизмом из реальных callers.
Warnings sun.misc.Unsafe/protobuf и недоступная Android analytics settings не
заменяют результаты задач и не послужили поводом менять среду.

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults --offline --console=plain
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew :app:testDebugUnitTest --tests '*P5a*Test' :app:assembleDebug :app:lintDebug --offline --no-daemon --no-parallel --max-workers=2 --console=plain
```

Полный JVM run: **111 tests,0 failures/errors/skipped**, BUILD SUCCESSFUL6m04s
вместе с debug build/lint. После последнего P5a-only guard (alias к прежней healthy
backup) повторно **34 P5a tests,0 failures/errors/skipped**, debug build/lint PASS1m48s.
Это112 различных JVM методов по совокупности прогонов, не заявление об одном
112-test запуске. Shared/legacy P1–P4 suite сохранены; изменена только P4 negative
ветвь ранее зарезервированного template Boolean на неизвестную payload version.
Host gates: **159 checks PASS** (P1=33, P2=15, P3=13, P4=18, source=62, P5a=18).
Новые P5a host PASS печатаются после cleanup; primary exception сохраняется suppressed.
Windows исполнения агента нет; Windows-specific filesystem enforcement подтверждается
пользовательским последующим запуском, Linux DOS attribute/model checks не равны Windows.

P5a fault evidence (JVM, отдельные tests, не Android):

- seed20538/0x503a;437 фактических IO events,1798 before/after/partial EIO/ENOSPC
  cases, два варианта сохранения unforced bytes;
- 8 реальных journal publications,288 комбинаций write/force/rename/parent sync/
  readback/ACK × before/partial/after × old/new rename retention;
- 8 durable metadata crash snapshots;
- 50 checkpoint boundaries до/после copy/build/force/identity (включая границу зон),
  **100 немедленных crash images** lost/retained bytes **и namespace**, снятых до
  обработки исключения/abort/cleanup;
- independent source/candidate content oracles, knowing schema99 oracle, old-healthy
  alias, ordinary-T binding progress, stale confirmations across coordinators,
  blocked abort IO, real-KDF cancellation и close/suppressed cases.

Fault enumeration использует production coordinator/credentials/unwrap/MAC/root;
повторные Argon outputs для фиксированных synthetic входов кэшируются только в
JVM fault-test injector. Shared Android suite и остальные credential/KDF tests
исполняют реальный Argon. Это не параллельный движок, не production KDF cache и не
количество повторённых happy paths. P3/P4 fault/model регрессии полного JVM run
также выполнены (прежние seeds/oracles сохранены).

```sh
python3 tools/p1/fixtures.py --check
python3 tools/p2/fixtures.py --check
python3 tools/p4/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
git diff --check
```

Frozen fixtures PASS: P1=28, P2=31 independent fixed fields, P4=3 positive/12 negative,
JVM/Android bytes identical. Static docs checks:41 links/28 tables/116 test IDs/13
resolution rows; это не semantic proof. Debug lint:0 errors/13 warnings/1 hint.
Host FileChannel/source alias tests выполнены на Linux с non-null fileKey; fallback
path identity других FS не объявлен доказательством отсутствия hardlink aliases.
Production allowlist остаётся пустой.

Release/R8 и аудит артефактов выполнены на Linux (22 октября 2026):

```sh
bash ./gradlew :app:assembleRelease --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew -Pp5aReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew -Pp5aReleaseRuntime=true :app:lintP1Release --offline --no-daemon --no-parallel --max-workers=2 --console=plain
P1_AUDIT_ARGUMENTS=app/build/reports/p5a-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
python3 tools/icons-demo/audit-apks.py --aapt2 /workspace/android-sdk/build-tools/37.0.0/aapt2
```

Ordinary release, обработанные target/test APK P5a, source identity и lint —
**PASS**. Release-derived lint: 0 errors, 15 warnings, 1 hint. DEX linkage,
package access, compiler intrinsics, Startup/lifecycle и оба mapping markers —
PASS; дублирующихся target/test definitions нет. Maintenance/backend только
в target; synthetic helpers, fixtures и восемь runtime methods только в test APK.
3950 закреплённых ICU resources и независимые fixture hashes проверены.

Восемь негативных APK-аудитов PASS: debug вместо target, второй backend,
изменённые NFC data, недоступный superclass, отсутствующие runner class/method,
недоступный runner method, target mapping вместо test mapping. Signed originals
остались неизменны. Проверка второго backend охватывает и maintenance package.
Demo isolation audit PASS: demo Activity/classes и шесть images присутствуют
только в debug; в ordinary release и p1Release их нет, launcher — MainActivity.

P5a artifact bundle `dcfa83f570707f478674b2428ba4df5582dcf33cfdbb45954296481806150dfa`:

| Артефакт | APK SHA256 | R8 map ID |
|---|---|---|
| target | `648bc2b1d67a1f299dd5fa6bc96b8d49facc11f381bbcca0118b11d56fc61542` | `c8dfdeb83935da114dd435afdcd481c3c70a7ebb0bcb2b1448102a35684ab638` |
| instrumentation | `35a12bd5eb59442cca146d9f3dd8c633bef0ef73339088af61d2f616df16a896` | `c39cf592d5b5f2d9d7ffb9e0075c542844889e8fc457f9499f3e943bbba3e044` |

Обе точные mappings, их SHA256, R8 configurations, instrumentation ABI и APK
сохранены в `app/build/reports/p5a-runtime/p1-diagnostics.zip` и artifact manifest.
Это локальные build outputs, не файлы репозитория. Новая пользовательская сборка
получит собственные hashes/IDs. Source capture: revision базы `93557e1…`,
`sourceFingerprint=751a56bafd0cb79b1c2cb294a768fb0bee13fb5c97b2259bcddaa674ae1c466c`,
source/working tree dirty=true, wrapperModified=false: проверки сделаны на рабочем
дереве перед публикацией, а не выданы за сборку чистого итогового коммита.

Отдельные P1/P2/P3/P4 release profiles собраны и прошли тот же APK audit
с соответствующими flags (все BUILD SUCCESSFUL):

```sh
for phase in p1 p2 p3 p4; do
  bash ./gradlew "-P${phase}ReleaseRuntime=true" :app:verifyP1ReleaseApks --offline --no-daemon --no-parallel --max-workers=2 --console=plain
  bash ./gradlew "-P${phase}ReleaseRuntime=true" ":app:${phase}ReleaseRuntimeCheck" --dry-run --offline --console=plain
done
bash ./gradlew -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --dry-run --offline --console=plain
```

Dry-run всех пяти runtime tasks проверил graph/gates, включая обязательные host
и source identity gates. Он не считается выполнением instrumentation.


## Android и оставшиеся условия

Новая suite `P5aMaintenanceRuntimeTest`: **8 методов**, прямая identity проверки
`target.classLoader`, те же production backend классы из обработанного target APK.
Фиксированные fixture keys/helpers только в test APK. Сохранены R8, packageScope,
Startup/lifecycle, generated instrumentation ABI, compiler intrinsics и APK audits.
Storage/ICU/CBOR/crypto по-прежнему **вне** optimization scope; это runtime исполнения
backend из обработанного APK, не подтверждение оптимизации этих packages.

p1Release отличается от ordinary release suffix `.p1runtime`, local test signing,
сохранённым ABI для внешнего instrumentation consumer/компиляторных intrinsics;
target non-debuggable. Его PASS не byte/runtime identity обычного release.
Общие DEX linkage/hierarchy/duplicate/backend/resources audits обязательны,
обе mappings сохранены с обоими APK SHA256 в диагностическом artifact bundle.

Свежий gate проверяет8 точных завершённых PASS на **каждом** выбранном online
устройстве, XML wrappers/testcase без двойного подсчёта, protobuf, exit code,
runId/freshness, SHA/mapping и revision/fingerprint/dirty/wrapper/Gradle/JVM.
Старые/0/неполные/skipped/crash отчёты не PASS. Windows host source checks,
62 checks с CRLF/autocrlf/read-only Git/process cleanup/suppressed сохранены;
`tools/p5a/` добавлен в byte fingerprint, ignored sources тоже учитываются.

Android runner агента отсутствует: adb не может создать служебный каталог
в read-only HOME; готового emulator/system image/телефона нет. Android **NOT_RUN**,
включая новые регрессии P1–P4 этой сборки. Существующие пользовательские P1–P4 PASS
не переименованы и не перенесены на новую APK пару.

[Точная Windows-инструкция](../../../tools/p5a/android-runtime-windows.md), готовая
**P5a Release Runtime** в Android Studio; Terminal из корня:

```powershell
.\gradlew.bat -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
```

Ожидание: Honor ABR-LX1 Android15 и emulator Android17/API31+, по8 tests,
failures/errors/skipped=0, `runtime=PASS`, итоговый BUILD SUCCESSFUL.
Передать `app/build/reports/p5a-runtime/runtime-result.txt`; при ошибке лог и
`app/build/reports/p5a-runtime/p1-diagnostics.zip`, свежие XML/protobuf/exit code.
Нужны и отдельные пользовательские регрессии P1/P2/P3/P4:7/8/8/10 PASS на каждом
устройстве, команды/receipts приведены в инструкции. KDF benchmarks не автозапускаются.

До этих двухустройственных runtime результатов P5a не принят, PR Draft.
P5b новый ZDK/re-encrypt, P6a SAF/durability/power-loss, P6b lifecycle/leaks/OEM и
P6c рабочий UI остаются отдельными gates. Master/PR агент не сливает.


## Пользовательское подтверждение Android, 22 октября 2026

Пользователь выполнил проверки на Windows для commit
`0f1c43fea9047be197fb58e9cb712f07c906aeb9`, затем слил PR №11 в master.
Агент проверил предоставленный `results.zip`: все пять receipts содержат
`runtime=PASS`; P5a XML содержат восемь реальных ожидаемых testcase на каждом
устройстве, failures/errors/skipped=0; runner exit code=0. Это пользовательское
исполнение, не Android-прогон агента. Исторический agent NOT_RUN выше сохранён.

| Suite | Honor ABR-LX1 Android 15 | Virtual Pixel 6 Android 17 |
|---|---|---|
| P5a | 8/8 PASS | 8/8 PASS |
| P1 | 7/7 PASS | 7/7 PASS |
| P2 | 8/8 PASS | 8/8 PASS |
| P3 | 8/8 PASS | 8/8 PASS |
| P4 | 10/10 PASS | 10/10 PASS |

Во всех receipts failures/errors/skipped=0. Одна APK-пара:
`artifactBundleId=074c123437a80efb64e9e6c72d054a04c64f101aa4e312e81e04b9cb7b0e8251`.
Target SHA256 `c4b49292480de8cf9a352cdc35bea86e57b52e1cbd56514544ef27d181bc3069`,
test SHA256 `e68d19a55d7b0909817e3b25f84c5a7c655f7a06aa0f32afa940c8f5ceb4056a`.
P5a `runId=809acb64-86c6-4491-9f78-656245a45d3d`,
`sourceFingerprint=032b16e1ac753fea6bdcbdc504d13e8a6ff2a5e808c2f31b6c5b6ff93a391935`.
Фактическая среда: Gradle 9.6.0, JetBrains JVM
`25.0.3+-15898627-b508.16`; `sourceCodeDirty=false`,
`sourceDirty=true`, `workingTreeDirty=true`, `wrapperModified=true`.
Локальное отличие wrapper properties сохранено в provenance, не скрыто и не
перенесено в нормативный wrapper репозитория.

Двухустройственный runtime gate P5a и необходимые регрессии P1–P4 для этой версии
закрыты. Ограничения p1Release относительно ordinary release, optimization scope
и границы synthetic/provider/power-loss/lifecycle сохраняются. Эти результаты
не подтверждают новую реализацию P5b: для неё нужны отдельные свежие прогоны.
