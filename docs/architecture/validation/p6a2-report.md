# P6a.2: контракты и средства квалификации storage

22.10.2026. База/master `d9891659f8bd6169185c73b52dd9ce99b82da6ea`, merge PR13;
ancestry `91ff18bcbadbdf3e1ec9a2844c1f09f73741e1d1` подтверждена fetch/API.
Ветка `codex/copper-scroll-p6a2-provider-qualification`, отдельный Draft PR, без merge.
AGENTS.md в репозитории/родителях не найден. Изучены нормы design/format1.1+1.2/
review-resolution/prototype/test-plan/P4 extension, отчёты P1–P6a.1 и Android gate/
factory/FD owner/local publisher/P3/P5 copy/coordinator/recovery.

P6a.1 **принят пользователем**, [отдельная запись с provenance](p6a-report.md).
Исторические failures и agent Android NOT_RUN не переписаны. Этот PR готовит
исследование/инструменты; новый Android runtime не наследует прежний PASS.
**Production admission REFUSED, allowlist пуста; общий P6a открыт.**

## Реализовано и исследовано

- [Primary-source qualification](../storage-provider-qualification.md): AOSP15
  frameworks/base/MediaProvider pins, conditional Linux6.6/man-pages contracts,
  actual open/create/identity/namespace/fsync/locks/noBackup цепочки. Honor OEM и
  Android17/backend/mount/flush identities не установлены. Нет догадки paths,
  hidden APIs, root или broad storage permission.
- Независимый bounded [evidence ledger](../../../tools/p6a2/evidence-ledger.properties):
  четыре конкретных исследуемых пути, отдельные evidence IDs трёх гарантий,
  Unknown измерения/prerequisites/process/power статусы; host schema/negative gate.
  Никаких imported receipts → production policy или wildcard admission.
- SAF deleteOwned раньше делал checkIdentity → deleteDocument, оставляя replacement
  TOCTOU. Теперь известный own artifact сохраняется с UnsupportedDurability до delete;
  ни write/create admission, ни наблюдённый fstat не разрешают compare-delete.
  Существующий coordinator сохраняет retained unverified artifacts/ошибки cleanup;
  source/backup не удаляются. В существующий explicitModesAndNegativeAdmission
  добавлена проверка отказа и сохранности bytes; все13 методов сохранены.
- Test-only QualificationSession в прежнем nonexported helper. Explicit process
  experiment использует настоящие picker/persistable grants, отдельный synthetic
  raw scratch, production read-only AndroidSafStore и real AppPrivateLocalState
  binding+journal publisher. PREPARED checkpoint forced перед смертью worker.
  Binder death, новый PID/session и same boot count проверяются до recovery PASS.
- Отдельный resumable normal-reboot prepare/resume, без автоматической перезагрузки.
  Новый BOOT_COUNT+session, точное окружение/root/artifacts/source/grants/whole bytes/
  observed fstat/local state обязательны. Подготовка не PASS завершённого эксперимента;
  old/consumed/failed attempt нельзя переиспользовать. Raw URI/local binding остаются
  приватными, публично hashes/метки. Лимит32 sessions и bounded metadata/logs/ZIP.
- Host controller проверяет также SHA256 обоих УСТАНОВЛЕННЫХ base APK. Reboot resume
  не устанавливает APK. Новые4 метода — explicit controller API, не замена connected
  suite и не автоматическое принятие count13. Каждая process/reboot пара требует
  ровно2 содержательных completed methods/device,0 failure/error/skip/crash.
- Fresh qualification-result.txt/ledger/phase logs и ZIP отдельно от correctness
  runtime. Build finalizer сохраняет первый failed task даже до старта controller;
  старый audit ZIP не прикладывается, если текущий audit не прошёл. Ошибка script
  до создания задач требует Terminal log. Сохраняются оба mappings/configs/hash IDs.
- Source fingerprint теперь охватывает tools/p6a2 и ignored files; Windows
  read-only/EOL/process cleanup fixtures/gates сохранены. APK audit требует новый
  target-only helper и четыре публичных test-only controller methods; ordinary
  release isolation/cold-loader/AXML/linkage audits остаются строгими.
- [ADR-001](../adr/001-saf-durability-boundary.md) сравнивает documented backend,
  иной явный API, app-private working file + separate import/export/backup и read-only.
  Последний действует сейчас; изменение источника истины не введено скрыто.

Криптография/формат1.1/1.2/S1/карточки/шаблоны/корзина/ChangeSecret/main-only/UI,
versions/toolchain/release signing и R8/keep scope не менялись. Три independent gates
сохранены. Controlled policy остаётся только opt-in; owned provider UID и внешние
actual grants не стали durability. Existing provider retry/persistent unavailability
и защищённый owner grant channel сохранены. Не запускались KDF benchmarks.

## Решения по гарантиям

| Путь | ExistingWriteDurable | DurableCreateIdentity | DurableLocalState | Admission |
| --- | --- | --- | --- | --- |
| Honor15 primary shared root | UNKNOWN | UNKNOWN | Отдельно ниже | REFUSED, read-only/отказ |
| Emulator17 primary shared root | UNKNOWN | UNKNOWN | Отдельно ниже | REFUSED, read-only/отказ |
| Honor15 app-private noBackup | N/A | N/A | UNKNOWN | Maintenance/switch запрещены |
| Emulator17 app-private noBackup | N/A | N/A | UNKNOWN | Maintenance/switch запрещены |

Reasons: pre-open trusted identity/alias и namespace durable publication API не
установлены; OEM/backend/FS/mount/device flush contract отсутствует. Local sequence
подходит условной POSIX model, но noBackup/API ownership не доказывают power-loss.
Случайный root hash не compiled selector. Без reviewed resolver/contract нельзя
выдать запись на source и тем более на произвольные backup/candidate destinations.
Observed capability/contract/model/experiment/admission разделены.

## Нормативная матрица: ветвь, evidence, статус, остаток

| ID | Конкретная ветвь / evidence | Статус этого этапа | Остаётся |
| --- | --- | --- | --- |
| SAF-01 | Create/OpenOriginal/ImportCopy/backup roots; createTransactionCopyCompactRekey и explicitModesAndNegativeAdmission | Controlled P6a.1 user accepted; новый runtime NOT_RUN агента | Production write/create contract каждого endpoint; перенос/reinstall |
| SAF-02 | readOnlyPipeVirtualAndStaleMetadata, positionalShortInterruptedAndErrorIo, unknown real-root gate | Model/controlled refusal; новый primary experiment NOT_RUN агента | Backend/fsync/device contract, реальные unsupported FD варианты |
| SAF-03 | grantLossReplacementAndProfileMismatch, version/root/backend StorageAdmissionTest, new exact recovery environment | Fail-closed; fresh PID/grant/identity scenario подготовлен | OEM reinstall/provider update/remount evidence, trusted resolver |
| DUR-01 | append/data force/header force/readback/ACK; P3FaultMatrixTest/P5aFaultTest/P5bFaultTest, provider dataForce vs namespace | Model regression; реальный endpoint остаётся UNKNOWN | Проверенный fsync/storage fault contract существующего файла, не только current bytes |
| DUR-02 | create/lost reply/file vs namespace force; P6a dataForceDoesNotPublishNamespaceAndUnknownCreate; new checkpoint URI_RETURNED/FILE_FORCED | Fail-closed production create; raw scratch observation отдельно | Namespace commit через разрешённый интерфейс и interruption evidence; Unverified не VerifiedCopy |
| DUR-03 | first-dir/marker/temp partial/full/force/rename/parent/readback/ACK; P3 all9 stages и P5 journal variants; P6a noBackupPublicationFaultsAndWholeRecords/corruptLostAndVersionedLocalStateNeverReset | Model+принятый P6a.1 process subcase; новый recovery controller готов к device | Local FS/device contract/power-loss, full stable lock/first-create на целевом стенде |
| DUR-04 | exact environment mismatch/null backend/root/contract; StorageAdmissionTest; QualificationLedger negatives | Fail-closed,3 independent gates; old receipts cannot authorize policy | Trusted endpoint resolver/OS+provider+mount invalidation для любого будущего admitted profile |
| TX-01…07, RETRY-01…03, RECOVERY-01…04 | Existing P3 transaction/retry/model/old-new slots, committed ranges, lost ACK; fresh P6a workers | Наследуемые model branches проверяются вновь; не полный device gate | Реальная interruption model/whole-file recovery; новый scratch не транзакционный oracle |
| COPY-01…04, COMPACT-01…10, REKEY-01…08 | P5a/P5b full snapshot/one backup/candidate/rekey, old/new credentials/другая зона; P6a createTransactionCopyCompactRekey | Controlled/model subcases, прежняя пользовательская приёмка отдельно | Production source/backup/candidate/local qualified независимо; real-root workflow запрещён |
| MAINT-01 | authorizationCredentialsAndNoEffects, mainOnlyAndCompatibility; direct decoy/Locked/stale deny | Core model/controlled branches | Full UI dispatch/deep-link P6c |
| MAINT-02 | pickerCancellationStaleAndRequiredFlags, kdfFailuresSetupPendingSavesAndStaleConfirmation | Locked token/grant subcase | P6b actual Home/rotation/late picker/OEM |
| MAINT-03 | pendingSaveUnknownAndOrdinarySaveBeforeRekey, aliasInterprocessLocksAndContinuousReopen | Drain/freeze/continuous lease model+controlled | Qualified real source lifetime, concurrent external writer scope |
| MAINT-04 | aliasesReopenCorruptionSourceReplacementAndUnknownOuter, commitUnknownCannotEnterMaintenanceAndVerifiedCandidateReplacementCannotSwitch | Whole verify/freshness negative branches | Qualified real candidate/source recovery, не только scratch bytes |
| MAINT-05 | revokeSwitchAndExclusiveLease, revokeSwitchAndLease | AcceptSwitch/Revoke serialized core | Real foreground/lifecycle race P6b |
| MAINT-06 | acceptedSwitchThenRevokeIsUnreportedAndRequiresExplicitResolution; P6a switchRevocationLostAckAndDistinctRecoveryCredentials | Lost ACK/Unknown/unreported controlled process | Real durable local publication/device interruption |
| MAINT-07 | everyJournalWriteForceRenameSyncReadbackAck, capturedDurableStagesRestartWithoutReplay; new raw scratch/local record checkpoint | Model stages + explicit process/reboot tooling | Physical namespace/local durability; raw scratch не complete workflow |
| MAINT-08 | restartWithDistinctCredentialContexts, recoveryRejectsMissingChangedFilesAndWrongContextsWithoutReplay, P6a switchRevocationLostAckAndDistinctRecoveryCredentials | Old/new credentials и no replay controlled | Production profile-specific recovery после interruption |
| MAINT-09 | cleanupKeepsFirstFailureAndClosesEveryResourceOnce, blockedAbortStillHoldsLeaseAndPreservesPrimaryWithSuppressedCleanup; P6a blocking IO | Owner/borrowed keys/primary-suppressed model; safer retained delete | P6b lifecycle/OEM/leaks, no 2s RAM promise |
| MAINT-10 | retentionSizesAndFailures, separate destination checks, aliasesToEarlierHealthyBackupAreNeitherWrittenNorDeleted | Model inventory/size/unique names; raw scratch retained | Each production destination qualified; compare-delete API либо retained |
| LIFE-06/09, MAINT-09 | ResourceOwner blocking IO/revoke, continuous lease/reopen, helper death; P5 ownership tests/P6a blockingIoRevokeRetainsOwnerUntilQuiescence | Existing bounded model/process subcases | P6b actual foreground/top-resumed/late callbacks/OEM/IME/leaks; timeout не quiescence |
| DUR cleanup subcase | checkIdentity→delete race; deleteOwned unconditional safe refusal before syscall; root probe retained | Исправлено без догадки identity | Отдельный atomic compare-delete backend contract либо всегда retained |

Полный нормативный ID не закрывается одной строкой/subcase. Сценарии матрицы ТЗ
переиспользуют существующие substantive P3–P6a tests; новый raw scratch не дублирует
шифрование/полный workflow ради count. При отсутствии нужного контракта ожидаемый
production refusal может быть PASS отрицательной проверки, но admission REFUSED.

## Выполненные проверки

Linux, Gradle9.6.0/checksum, Oracle25.0.4.1+1-LTS-5; AGP9.4.1/Kotlin2.2.10/SDK37/min31 прежние.

| Проверка агента | Фактический результат |
| --- | --- |
| Полный JVM/fault suite |162 tests,27 XML suites,0 failures/errors/skipped;6m17s вместе с host/debug/lint |
| P3 model |6820 fault cases; прежние committed ranges/old-new whole snapshots/другая зона |
| P5a / P5b model |1798 /2938 IO cases;288 local journal cases каждого;100 /156 pre-cleanup crash images,8 durable-stage restarts каждого |
| Все host result/source/diagnostics gates |371 checks:33/15/13/18/92/18/18/18/20/126; последний126 — новые evidence/controller negatives |
| Windows-specific cleanup/EOL |P4 fixture autocrlf=false/true/input и readonly/process/primary-suppressed на Linux; Windows OS ветви агентом NOT_RUN |
| Debug + debugAndroidTest |Собраны; lintDebug0 errors,13 warnings,1 hint |
| Ordinary release, p1Release, p1ReleaseAndroidTest/R8 |Собраны; полный release/lint/audit запуск8m20s; R8/keep scope сохранены |
| Lint release / p1Release |0 errors;15 /26 warnings, по1 hint. Новые ошибки API31 не обнаружены; warnings не основание менять версии |
| APK/linkage/manifest/cold-loader/isolation |PASS;6911 method refs,1949 field refs,32715 code edges;0 duplicate target/test definitions, backend только target,3950 pinned ICU files/independent fixtures |
| Negative APK/isolation |8 generic negatives;4 isolation + неверная external authority +2 owner ACL negatives отклонены; подписанные APK не изменены |
| Fixtures/docs |6 wire +21 static S1 expectations;51 local links/anchors,28 tables,116 IDs,13 resolution rows; новые5 Markdown/15 local links/3 Run XML; diff whitespace PASS |
| Build-failure finalizer |Намеренный target compile failure и более ранний host compiler failure: свежая первая ошибка, старые artifacts исключены; оба ожидаемо BUILD FAILED |
| Неполученный Android runner |adb devices134: Cannot mkdir /home/agent/.android, read-only; список устройств не получен, system images/dev-kvm отсутствуют |
| Реальная итоговая qualification команда без runner |Ожидаемый nonzero/BUILD FAILED на snapshot устройств, compact experimentOutcome=NOT_RUN + productionAdmission=REFUSED; свежие controller/phase/build diagnostics сохранены, ложного PASS нет |

Команды (в среде агента source /workspace/tooling/env.sh; дополнительно --offline
--no-daemon --no-parallel --max-workers=2):

```sh
bash gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:testP6aRuntimeDiagnostics :app:testP6a2Qualification :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
bash gradlew -Pp6aReleaseRuntime=true :app:verifyP6aIsolation :app:verifyP4BuildIdentity :app:testP6a2Qualification :app:lintRelease :app:lintP1Release --console=plain
python3 tools/p6a/check-isolation.py
P1_AUDIT_ARGUMENTS=app/build/reports/p6a-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
bash gradlew -Pp6aReleaseRuntime=true :app:p6a2ProcessQualification --no-configuration-cache --console=plain
```

Последняя команда фактически проверена до отказа adb, вместе с повтором всех host
result/source/diagnostic tasks, не до Android исполнения. Негативные build fixtures
использовали ignored init scripts с doFirst throw P6A2_SYNTHETIC_COMPILE_FAILURE /
P6A2_SYNTHETIC_HOST_COMPILE_FAILURE; они не входят в пользовательскую конфигурацию,
не отключают gates/R8 и не используют -x. Финальный обычный audit повторно PASS.

Логи в ignored work/p6a2: host-debug.log, release-audit.log, final-host-no-runner.log,
build-failure-fixture.log, early-build-failure-fixture.log, apk-negatives.log,
isolation-negatives.log. Первый запуск isolation script без source env отказал на
отсутствующем JAVA_HOME; исправлено только окружение и тот же script прошёл.
Новый no-runner hostRunId `91bec6e2-dd08-4671-b7dc-4d8355a80bb9`, build attempt
`c99db163-1fa1-477b-a3cb-37d768f9fc31`. Его qualification-diagnostics.zip содержит
NOT_RUN/причину, source и только текущую audited APK-пару с двумя mappings; build
archive содержит ошибку controller и ту же пару. Fixture early failure archive
`0f13fa3a-2374-4f9b-aa8a-9e86e55df183` не содержит старой пары.

```properties
sourceRevision=d9891659f8bd6169185c73b52dd9ce99b82da6ea
sourceFingerprint=b2cb96856d03fc2c9b40e5f0f373c2f5dea95ad15d127a298f76561d37b54cd6
sourceDirty=true
sourceCodeDirty=true
workingTreeDirty=true
wrapperModified=false
artifactBundleId=0040d88c2e28b0bafd83d3cd1de50a7b7038f50d77164269914a3247376b8f6e
targetSha256=cad033c0aabcc4154aa0156c0d2f7fe9878bf3046a4db8781e990439d670c5f5
testSha256=60c6ed3ec3669677ab560946b47c40c22ad459fa273a972f51f5b5f507749e19
targetMapId=d1caaecdae67ab7a10b5d7bdfe7071e8ce45ef7eb8be18495617bc953a26f7bc
testMapId=a202c90dc005a1950dfca932cb14aeecb6b106b8af9391eb839bec1b9d004a36
targetMappingSha256=8e5f4892334b072cd3f3329a7904da069c38f39b05273a670b49f33e87432887
testMappingSha256=9c2f181f981a047454db53b9ae81fb22ebb0762eaf196f94e88806ddaa58d3e0
ordinaryReleaseSha256=694938ba5c3347039e0ca953dc512b0c488923d9b41b0084033831a7dc330c2c
runtime=NOT_RUN
```

Это собственная подготовка до commit, не clean build будущего head. Target map ID
может совпасть с прежним при одинаковом mapping оптимизируемой части; привязку к
бинарнику устанавливает APK SHA256 + mapping SHA256 + bundle, не один map ID.
Конфигурации/оба maps/rules находятся в immutable artifacts/<bundle> и p1-diagnostics.zip.
Windows новых controllers, новые process/reboot Android эксперименты, controlled
VM/storage и физическое питание **NOT_RUN агента**. Host/build/старая приёмка не
runtime PASS. Безопасного interruption стенда нет; недостающее evidence в qualification/
ADR, обычный Stop VM не доказательство очистки host/kernel caches.

## Пользовательский путь и предел release

[Точная Windows инструкция](../../../tools/p6a2/android-qualification-windows.md).
Новая двухустройственная regression обязательна, поскольку изменены общий adapter
cleanup и target/test ABI: P6a13 и P1/P2/P3/P4/P5a/P5b7/8/8/10/8/8 на каждом,
0 failures/errors/skipped,124 completed total. Предыдущие пользовательские P6a.1
PASS не подтверждают новый APK. Новый process experiment —2 completed/device;
reboot prepare —1/device и NOT_RUN до отдельного resume —1/device.

p1Release nondebuggable, test signed, другой applicationId, harness/policy/helper и
instrumentation API keep; production backend только в target. R8 включён с прежней
частичной областью androidx/kotlin/kotlinx; storage/ICU/CBOR/crypto вне optimization.
Поэтому runtime после R8 этой APK-пары не означает runtime/binary identity обычного
release, которого UI/harness не расширяет. Maps обоих APK связаны с SHA256/bundle.

## Приёмка и следующий шаг

Инструменты/исследование P6a.2 могут быть готовы к проверке при незакрытом production
профиле. Production admission здесь не выдан: нет оснований для нового registry/
resolver/forceIdentity. Необходимый приложению рабочий write путь нормативно не
квалифицирован; общий P6a не завершён. Нужны новый user runtime/explicit observations,
а затем выбор documented backend или отдельное решение по ADR. Эксперименты сами
по себе отсутствующий интерфейс namespace/pre-open identity не создадут.

P6b можно параллельно проверять на synthetic data: foreground/multi-resume/screen-off/
rotation/picker invalidation, secure recents/IME/autofill/content capture/clipboard/
SavedState/logs/reveal и backup/OEM transfer, owner quiescence. Это не переносит
production durability в закрытые gates. P6c UI integration, KDF benchmarks,
performance limits и независимая security/format review остаются отдельными.
