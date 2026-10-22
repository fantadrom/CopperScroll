# P6a.1 — Android SAF, noBackup metadata и проверка хранилищ

22 октября 2026. Репозиторий fantadrom/CopperScroll, ветка
`codex/copper-scroll-p6a1-saf-adapters`. Проверенный актуальный master/base:
`ace1ee51d2c7d56a71e2aa1c007b0f928bd1c3f3`, merge PR12, содержащий P5b head
`2ec28bf8c458ace5b283a2fe6aad84872df7c3a1`. Перед изменениями fetch/API подтвердили
merge, ancestry и чистое дерево. AGENTS.md не найден. Применены design§4/6/8,
format1.1/1.2, review-resolution, prototype P6, test SAF/DUR/LIFE/LEAK/MAINT,
отчёты P1–P5b и существующий owner/core/copier/coordinator/recovery/local codec.

**Текущий статус: P6a.1 принят по пользовательскому подтверждению 22.10.2026.
Production allowlist пуста; квалификация P6a.2 и общий P6a открыты.**
Android агента NOT_RUN ниже относится к собственным историческим сборкам. Пользовательские
P5b/regression результаты прежней пары отдельно добавлены в
[p5b-report](p5b-report.md); они не подтверждают новые adapters/APK P6a.

## Пользовательское подтверждение P6a.1 от 22 октября 2026

Пользователь сообщил успешное выполнение проверок после `91ff18bcbadbdf3e1ec9a2844c1f09f73741e1d1`.
Результаты Results(1).zip проверены в предшествующем чате; в среде этого задания
архив недоступен. Агент не получал/не проверял его самостоятельно и не утверждает,
что исследовал отсутствующие raw runtime XML. Ниже зафиксировано переданное
пользовательское подтверждение и его идентификаторы, не собственный Android запуск.

| Проверка | Honor ABR-LX1 Android15/API35 | Virtual_Pixel_6 Android17/API37 |
| --- | --- | --- |
| P6a SAF runtime |13 PASS|13 PASS|
| P1 / P2 / P3 |7 /8 /8 PASS|7 /8 /8 PASS|
| P4 / P5a / P5b |10 /8 /8 PASS|10 /8 /8 PASS|
| Real-provider probe |1 завершённый тест, receipt|1 завершённый тест, receipt|

Runtime receipts: runtime=PASS, failures/errors/skipped=0; **124 завершённых runtime
теста плюс2 отдельных probe**. Gradle9.6.0, JetBrains JVM `25.0.3+-15898627-b508.16`.
sourceRevision=`91ff18bcbadbdf3e1ec9a2844c1f09f73741e1d1`, sourceFingerprint расширенных
receipts=`ffb380e2e34d121176dd60bb22cb9436abce9a4ca34373af5d1b75a1a064bdff`.
sourceCodeDirty=false, sourceDirty/workingTreeDirty=true, wrapperModified=true;
это не полностью чистая сборка master.

- P6a runtime runId: `18e9b55b-05bc-47e6-9f03-aff27e8569bc`.
- P6a/probe artifactBundleId: `59b9ecc23c9967f279553b2dd7fe73be8cc54c5fba2e28fd462e3e91edde29c1`.
- P1–P5b regression artifactBundleId: `b482430b0b2f27b153cd9af71e0c6a61dfc03dbac8ff4ad753be385883f75958`.
  Различие ожидаемо: opt-in P6a включает harness, regression policy его исключает;
  одинаковые APK между вариантами не требуются.
- Probe host runId `4b671017-857a-4e29-a9d3-e43f9c5a9aae`;
  Honor `dc3925c6-acce-4f64-b8a6-d5e27cb2d776`, emulator `201e9d27-eab0-4740-ae24-3a5a57ce6d9f`.

Оба probe: authority=com.android.externalstorage.documents,
package=com.android.externalstorage; mode=rw;no-truncate. Create, positionalWrite,
fileFsync, interprocessLock, noTruncateReopenLengthHash=OBSERVED;
identityStrategy=observed-fstat-dev-inode;NOT-qualified. Volume/backend=unknown,
три durability-поля UNKNOWN, productionApproved=false,
powerInterruption/processRestart=NOT_TESTED,
cleanup=RETAINED_NO_QUALIFIED_DELETE_CONTRACT. Это ожидаемый результат P6a.1.

Root hashes: Honor `4af0286f7f6db1fff1e398f26d57bffdcaf9958c8f035c02b5049e8eaa94efc1`,
emulator `b53b0a25d2b032f08b7bd682b5d366a273d83a85c210012450118b78572d3b87`.
Это observation labels; они не устанавливают backend, подлинность root или guarantees
и не включаются в allowlist. Условия приёмки P6a.1 закрыты пользователем; production
qualification, power-loss, KDF benchmarks, P6b/P6c/security gates не закрыты.
[Следующий отчёт P6a.2](p6a2-report.md) сохраняет эту границу. Ошибки и собственный
Android NOT_RUN в следующих разделах — история соответствующих прежних сборок,
а не отмена этого подтверждения и не PASS новых APK P6a.2.

## Повторное исправление bootstrap и сценариев перезапуска

Актуальный head PR13 перед изменениями подтверждён fetch/API:
`c4f8ebe15e63bd03cf4c6d3c06cfd98618117644`, ветка
`codex/copper-scroll-p6a1-saf-adapters`, PR открыт/Draft, без merge.
В новом пользовательском p1-diagnostics.zip оба XML (Honor ABR-LX1 Android15,
Virtual_Pixel_6 Android17) содержат13 тестов,2 failures,0 errors/skip:11 PASS,
отказы explicitModesAndNegativeAdmission и grantLossReplacementAndProfileMismatch.
Windows Terminal подтверждает BUILD FAILED connected и запуск финализатора.
Это пользовательский прогон предыдущего исправления, не PASS текущего кода.

Retrace выполнен по test-mapping.txt именно из этого ZIP: map ID
de9e28fde918e0313d39822c14a5579c1111cb4dc21eae3140b9889e7fc76987,
SHA256 e4c5161d1d6581700862565e114fbbbe638bcfd2cf243244351cb337c5f705bd.
ID совпадает с тестовыми стеками; target mapping не применялся к test APK.
Восстановленные места: P6aSafRuntimeTest.kt:167 (прежний death/reject) и
:97/:101 (externalGrantAccess → ContentResolver.call).
Artifact bundle пользователя:97d73bd476fca25e3b10c16667f185991a6f9af46c1bd6bc66d4d1df575e27f6;
target/test SHA256:128fa7b59bbe911c5147d98eee01431ab29d0cb0bd40f827d2b01c255a28a9e3 /
a3e12778151fe275aa1a582067d12b5c1dd163d1938617dc3f475d200805e704.

Причина bootstrap: отсутствие permission check внутри ContentProvider.call не
отменяет предварительный барьер acquireProvider. До получения URI-grant процесс
target не может приобрести внешний DocumentsProvider с MANAGE_DOCUMENTS.
Прежний control через этот provider удалён. Новый ExternalGrantOwnerService в
test APK работает отдельным процессом владельца URI. Bind защищён signature
permission, объявленной/запрошенной только opt-in target; Binder transaction
дополнительно проверяет точный UID/подпись target, interface token и полный payload.
Разрешены лишь revoke/read для фиксированного synthetic URI и фиксированного target;
нет произвольного адресата/URI/write-grant. Перед grant/revoke очищается calling
identity, права выдаёт владелец. Клиент закрывает канал через use: revoke и unbind,
ошибки cleanup сохраняют первоначальную ошибку. UI/нового пользовательского входа нет.
Документный provider сохраняет MANAGE_DOCUMENTS и реальные проверки URI-grants.

One-shot death ранее терялась вместе с HashMap; ContentResolver мог повторно
открыть provider и успешно получить FD. Новый test-only ProviderRestartFaults
пишет состояние в отдельный приватный файл и завершает write/fsync/close до смерти.
deathOnce переходит once-armed → once-consumed → Allow; deathUnavailable переходит
persistent-armed → unavailable и запрещает query/open после нового PID, пока не
выполнен явный normal/clear. Один и тот же journal используется в provider и5 JVM
тестах; повреждённая запись не сбрасывается, имена файлов пригодны для Windows.
Это process-restart injection, не доказательство power-loss durability.

В прежнем grantLossReplacementAndProfileMismatch сохранены отказы при denied,
смене профиля, подмене identity и missing. На отдельном валидном artifact проверяются
оба сценария смерти. Для one-shot разрешён framework retry либо один явный fresh
open только после consumed; проверяются новый PID, store identity, полный production
fingerprint, writable/admission и обе применимые гарантии store. Все проверки
открытого store выполняются под use. Persistent сценарий требует отказа первого
open и повторных read/write попыток, новый PID и сохранённое unavailable; только
явное снятие fault позволяет открыть и заново проверить artifact. rejectOpen закрывает
неожиданно открытый store и всё равно проваливает отрицательную проверку; ошибка
close не превращается в ожидаемый отказ. Простой замены reject на успешный open нет.

В explicitModesAndNegativeAdmission сохранены реальные no-grant/read-only/revoked
отрицательные проверки другого UID и отдельное отсутствие durability approval.
Все13 имён и exact-set/XML/protobuf/exit/freshness/source/artifact gate сохранены.
Host APK audit теперь читает фактические AXML attributes: signature protectionLevel,
permission service, exported/process, document provider ACL/authority. Транзитивные
DEX references service/provider проверяются против class loader отдельного test UID.
Оба Java-компонента и Binder-класс не требуют общей Kotlin runtime или backend target.
Они исключены из ordinary release; production policy/три gates/пустая allowlist,
R8 scope целевого/обычного release, версии и штатная подпись не меняются.

Первый post-R8 audit отказал на зависимости отдельного test UID:
ExternalGrantOwnerService → Binder$1 → xf1.c(String) → ActivityScenario →
androidx.lifecycle.Lifecycle$State (последний существует только в target).
Точный mapping этой сборки показывает xf1 как R8 synthetic ActivityScenario helper.
Это обнаруженная статическая зависимость, не воспроизведённый агентом ART crash.
Аудит оставлен строгим. DEX disassembly показывает xf1.c(String) как общий
throw IllegalArgumentException(String); другие методы этого же synthetic класса
ссылаются на ActivityScenario. Узкие test-only keep rules не устранили такой перенос
и удалены. Исправлен исходный шаблон отказа недопустимого Binder payload:
SecurityException без зависимости от общего exception helper. Авторизация/запрет
лишних mode/payload остаются обязательными. Итоговые keep rules, target/ordinary
release scope и остальная instrumentation не изменяются; R8 остаётся включён.
Неудачная APK-пара/точный mapping сохранены в ignored work для отрицательного audit.

Проверки текущего исправления: полный JVM suite162/0 failures/errors/skipped,
27 XML suites, включая5 новых ProviderRestartFaultsTest. Все239 host checks
(33/15/13/18/86/18/18/18/20) — PASS. Debug/debugAndroidTest собраны,
lintDebug:0 errors/13 warnings/1 hint. Полный host/debug/lint запуск —7m08s.
Windows-specific ветви здесь повторно не исполнялись; unit-файлы fault journal
не содержат Windows-недопустимого двоеточия. Android runtime новых сценариев
пока NOT_RUN; пользовательские11 PASS относятся к прежнему APK.

Окончательные release/test сборки, R8, lintP1Release, APK/linkage/cold-loader/AXML
ACL/isolation/source gates — PASS; финальная сборка2m29s, lint0 errors/25 warnings/
1 hint. Test R8 configuration SHA25631d11d438266a0f152aee4b2bbbcb67b762a6465cebdde0bf1822bbcd81e01b6:
добавленных owner keep rules нет. Отдельно отвергнуты4 isolation нарушения,
неверная external authority,2 неверных owner ACL (normal вместо signature и
чужая permission service),8 прежних generic нарушений. Сохранённый реальный
промежуточный APK с xf1/Lifecycle зависимостью также отвергнут тем же строгим audit
с его точным test mapping. Подписанные итоговые APK негативными тестами не изменены.

Команды проверки (дополнительно --offline --no-daemon --no-parallel --max-workers=2):

```sh
bash gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:testP6aRuntimeDiagnostics :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
bash gradlew -Pp6aReleaseRuntime=true :app:verifyP6aIsolation :app:verifyP4BuildIdentity :app:lintP1Release --console=plain
python3 tools/p6a/check-isolation.py
P1_AUDIT_ARGUMENTS=app/build/reports/p6a-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
```

Свежий adb devices -l завершился134 (Cannot mkdir /home/agent/.android, read-only);
список устройств не получен, emulator/system images/dev-kvm отсутствуют.
Фактический Android runtime новых сценариев **NOT_RUN**, не заменён сборкой.
Все логи, exact retrace, промежуточный отрицательный APK и mappings сохранены
в ignored work/p6a1-bootstrap-fix. Итоговые оба mappings/configs/SHA находятся
в app/build/reports/p6a-runtime/p1-diagnostics.zip; sourceRevision=c4f8ebe15e63bd03cf4c6d3c06cfd98618117644,
sourceCodeDirty/sourceDirty=true, wrapperModified=false, Gradle9.6.0/JVM25.0.4.1.
Это подготовка до commit, не clean build будущего commit.

```properties
artifactBundleId=8c3f6df83188385005346495a4a23f70f8b9fbae224c5768c33fea7f85aae219
targetSha256=03ef6ddd8e5aca584ff79fc30c0f6548a78a859c45389efceb8e7637c15fc432
testSha256=dc4a6e204a0ff1af424985c81fc2da6ecab28f7d986b46437f87270d8c09d945
targetMapId=d1caaecdae67ab7a10b5d7bdfe7071e8ce45ef7eb8be18495617bc953a26f7bc
testMapId=bd17836b014269b717558216fbe1a0767e90ea7d75b66023b65c320cc7c13248
sourceFingerprint=476f35325664a929e0b53474d2cbf45581a4de2a259e12b752ca1f9e53e3a94d
runtime=NOT_RUN
```

Пользователю остаётся прежняя единая p6aReleaseRuntimeCheck из Windows инструкции:
новые13 PASS/0 failures/errors/skipped на каждом Honor15/emulator17 и финальный
BUILD SUCCESSFUL. Draft PR13 сохраняется без merge. P6a.2 qualification, P6b,
P6c и отдельная регрессионная матрица этим исправлением не закрываются.

Ниже сохранены исторические результаты первого исправления UID. Описание его
старого bootstrap через provider заменяется текущим owner service, описанным выше.

## Исправление доступа после пользовательского прогона

Перед исправлением PR13 открыт, Draft, head
`3e7cd505bf0b9bcd7381846876defaf7cb7752da`; удалённая ветка и локальный checkout
совпали. В присланном `p1Release.zip` на Honor ABR-LX1 Android15 и
Virtual_Pixel_6 Android17 каждый XML содержит13 тестов,12 failures,0 errors,
0 skipped. Единственный PASS — pickerCancellationStaleAndRequiredFlags.
Все12 отказов каждого устройства — StoreException.AccessLost на прежней
строке34 SafStoreFactory.inspect, проверке checkUriPermission. Терминальный
лог (UTF-16) подтверждает BUILD FAILED connectedP1ReleaseAndroidTest.
Это пользовательское исполнение, не прогон агента и не P6a PASS. Warnings
Unsafe/protobuf не причина отказа. Log сообщает Gradle9.6.0/JVM25.0.3,
sourceDirty=true/wrapperModified=true; различия не объявляются причиной ошибки.

Установленная причина: checkUriPermission проверяет явно выданные URI-grants,
но не общий доступ UID владельца provider. Контролируемый provider работает
отдельным процессом того же UID; ему не выдавался внешний grant.
Теперь inspect сначала разрешает ProviderInfo, сравнивает именно
ProviderInfo.applicationInfo.uid с Process.myUid и для чужого UID требует
checkUriPermission с полным набором READ либо READ|WRITE. Совпадение authority
или имени package не подтверждает владение. Используются API, доступные на31;
checkContentUriPermissionFull (только35+) не вызывается.

Разрешение доступа не выдаёт ExistingWriteDurable, DurableCreateIdentity или
DurableLocalState. AdmissionMatcher/квалификация, последующие query/FD/identity
проверки и пустая production allowlist сохранены; даже свой provider с неизвестным
backend остаётся readonly/отказом для изменений. R8 scope, keep rules,
Startup/lifecycle, версии зависимостей и штатная подпись не изменены.

Добавлены три JVM проверки UID/полного grant mask/независимости admission.
В существующий explicitModesAndNegativeAdmission встроены Android проверки
реального своего provider без explicit grant и внешнего provider, расположенного
в test APK с другим UID: no grant → отказ чтения/записи; настоящий read-only
grant → inspect(read), пустые durability gates, отказ write; отзыв → отказ.
Read-only grant выдаёт сам отдельный процесс этого синтетического provider,
с проверкой UID/signature target; instrumentation не выдаёт себе чужие права.
Документ объявляет SUPPORTS_WRITE, чтобы read-only отказ проверял именно grant.
Provider не содержит backend/файлов, скрыт из picker и отсутствует в target и
обычном release. Audit проверяет его DEX/manifest/authority в test APK.
Его код написан на Java с одними platform/Java зависимостями: собственный процесс
test UID не имеет class loader целевого APK с общей Kotlin runtime. Audit проверяет
реальные post-R8 references этого класса на отсутствие таких зависимостей.
Все13 имён runtime и строгий итоговый gate сохранены.

Диагностический финализатор prepare/connected/final gate теперь обновляет
текущий p1-diagnostics.zip даже после отказа connected. Он сохраняет оба точных
mappings/configs/APK SHA256/map IDs, попытку/run manifest, source identity,
свежие XML/protobuf/exit/device logs и runtime-diagnostics.txt с причиной отказа.
Ошибка утверждения и стек остаются в исходном XML. Отсутствующие/старые результаты
помечаются, старые не включаются как свидетельство нового запуска. Если final gate
выполнился и отказал, runtime-result.txt содержит FAIL и исходное исключение;
ошибка записи диагностики добавляется suppressed к первоначальной ошибке.
Финализатор не выдаёт runtime PASS и не подавляет ошибки connected/gate.

Проверки исправления в Linux: полный testDebugUnitTest —157 тестов,
0 failures/errors/skipped (26 XML suites), включая3 новых SafUriAccessTest;
debug и debugAndroidTest APK собраны, lintDebug —0 errors/13 warnings/1 hint.
Все host gates —239 проверок: P1=33,P2=15,P3=13,P4=18,source identity=86,
P5a=18,P5b=18,P6a=18,диагностический архив=20. В архивных host fixtures проверены
success, failed connected без запуска verifier, отсутствие/устаревание результатов,
другая attempt identity, оба mappings, очистка старого PASS, первоначальное
исключение и suppressed ошибка записи. Это не Android receipts.
Wrapper9.6.0/JVM25.0.4.1; исходники/сборка не требуют новой версии зависимостей.
Windows ветви read-only cleanup и новый диагностический ZIP здесь отдельно
не исполнялись на Windows; прежние source gates остаются обязательными.

Ordinary release, p1Release target и instrumentation APK после R8 собраны;
lintP1Release —0 errors/25 warnings/1 hint. Positive APK/linkage/standalone external
provider/обычный release isolation/source identity audits PASS. Негативные audits:
4 isolation,1 отсутствующая external authority и8 прежних generic APK нарушений
отвергнуты по ожидаемым причинам; подписанные APK не изменены.
В отдельной host Gradle-фикстуре тот же порядок finalizedBy/mustRunAfter проверен
для отказа prepare, отказа connected и полного успеха: финализатор выполняется
во всех3 случаях, ошибки сохраняют nonzero exit/BUILD FAILED, gate не исполняется
после отказа prerequisites. Это проверка планировщика, не Android execution.

Фактические команды (общие параметры: --offline --no-daemon --no-parallel
--max-workers=2 --console=plain):

```sh
bash gradlew :app:testP6aRuntimeDiagnostics :app:testP1RuntimeResults :app:testP6aRuntimeResults :app:testP4SourceIdentity :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
bash gradlew -Pp6aReleaseRuntime=true :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:testP6aRuntimeDiagnostics :app:verifyP6aIsolation :app:verifyP4BuildIdentity :app:lintP1Release
bash gradlew -Pp6aReleaseRuntime=true :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP6aRuntimeDiagnostics :app:verifyP6aIsolation :app:verifyP4BuildIdentity
python3 tools/p6a/check-isolation.py
P1_AUDIT_ARGUMENTS=app/build/reports/p6a-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
```

Первый полный debug/JVM/lint запуск —5m52s; release/R8/lint —6m43s.
После дополнения host диагностики отказом prepare и усиления audit (без изменения
Android кода) повторная host/audit/source проверка —20s, PASS.
Логи и host-фикстура планировщика сохранены в ignored work/p6a1-access-fix.
Дополнительно выполнена сама команда `bash gradlew -Pp6aReleaseRuntime=true
:app:p6aReleaseRuntimeCheck --console=plain` с теми же общими параметрами.
Ожидаемый отказ подготовки: adb exit134, Cannot mkdir /home/agent/.android
(read-only). BUILD FAILED за18s, connected не запускался. Реальный Gradle-финализатор
после отказа prepare сохранил ZIP с обоими mappings, attempt/source identity,
preparation-failure.txt (исходное исключение ADB) и runtime-diagnostics.txt:
acceptance=NOT_CONFIRMED, evidence=FAIL, runtimeResults=ABSENT.
runtime-result.txt отсутствует; старые результаты удалены. Это проверка отказа
подготовки/архива, Android runtime исправления по-прежнему NOT_RUN.
Подготовка APK выполнена до commit, sourceRevision — предыдущий head3e7cd505,
sourceCodeDirty/sourceDirty=true, wrapperModified=false; это не clean build
будущего commit. Точные пары mappings и APK связаны SHA256:

```properties
artifactBundleId=cebf293b6368dc5f207a48a1bf4847743fbe9dfa069d3f103c0384641cab4f21
targetSha256=8f4904486c814b2e9ba63b8ba33f696dd4b02e5640b801c832628b1082c8a8cf
testSha256=d6358e3ae1c07266691a3194625d23bb1d668e6856fa048df917b1de4a6a286c
targetMapId=db8b893400b50b74ffec1c735722c6ab572839f4ab5a4694312cb672d83fa4f7
testMapId=de9e28fde918e0313d39822c14a5579c1111cb4dc21eae3140b9889e7fc76987
sourceFingerprint=1cdfb09277b28cb34ee5776ee9db27aebc6e8b878e264cee730faa173ffc6629
runtime=NOT_RUN
```

Для подтверждения исправления нужны новые13 PASS на каждом Honor15/emulator17
с прежней единой командой из Windows инструкции. Присланные12 failures не закрывают
приёмку. Production admission/P6a.2/P6b/P6c и отдельная регрессионная матрица
остаются открытыми; Draft PR13 не сливается.

## API, FD и исключение второго writer

[SafStoreFactory](../../../app/src/main/java/com/fantadrom/copperscroll/storage/android/SafStoreFactory.kt)
различает OpenOriginal/Create/ImportCopy/RestoreCopy. Source URI берётся без path
угадывания и без staging. Create использует отдельный tree, unique UUID/name и
фактически возвращённый URI; не предполагает O_EXCL для неизвестного provider.
Отсутствующий документ не создаётся автоматически. Flags query служат наблюдением,
не долговечностью. Intent flags сохраняются ровно выданные, только активным
одноразовым Locked token; readonly не получает write. Нет широких permissions.
Полный picker/foreground lifecycle, reinstall и OEM остаются P6b.

[AndroidSafStore](../../../app/src/main/java/com/fantadrom/copperscroll/storage/android/AndroidSafStore.kt)
использует ContentResolver.openFileDescriptor только `r`/`rw`, Os.fstat/lseek
пригодности и Os.pread/pwrite/fsync/ftruncate. IO positional, bounded full loops,
short/EINTR/EOF/zero progress/overflow checked; чтение ограничено8MiB за вызов,
P5 copy/hash64KiB, текущие records≤CHUNK+92. Metadata size provider не источник
истины. Нет полного чтения container/media в RAM. Errors имеют content-free codes.
InterruptedIOException с достоверным bytesTransferred учитывается; повторный
нулевой EINTR ограничен64, отказ не трактуется как завершённая запись.

Цепочка владения: PFD → один framework AutoCloseInput/OutputStream → его channel;
Os заимствует FD, не закрывает его. Store.close вызывает только stream.close,
который по API владеет PFD; нет собственного dup/borrowed wrapper/второго close.
ResourceOwner владеет store, locks и in-flight IO. Revoke/timeout не закрывает
дескриптор во время syscall; lease держится до quiescence/Unknown reconciliation.
Cleanup сохраняет primary и suppressed failures.

FileChannel.tryLock проверяет документ. Дополнительно стабильный app-private
lock inode, имя от hash(dev,inode,device build), держится другим FileChannel на
весь writable lifetime, включая FD reopen. Общий ResourceOwner сохраняет
ReopenLease через close/open gap; document lock переполучается под этим guard.
Так закрытие SAF FD не создаёт окно второго сотрудничающего writer. Process
registry запрещает дублирующий FD до открытия при известной pre-open identity;
неизвестный provider допускается readonly, консервативно без параллельных handles.
Guard не размещается в чужом каталоге и не угадывает SAF filesystem path.

Tree/document формы одного ID и объявленные test aliases узнаются через
contract identity плюс проверенный stat. Portable containerId не file identity.
Qualified writer требует безопасной pre-open alias identity; actual FD/stat
сверяется при открытии, использовании и reopen. Dev/inode сами по себе не
универсальный provider contract; inode reuse/replacement дополнительно
проверяются environment, container instance и engine fingerprints. Advisory locks
не защищают от несотрудничающего внешнего writer; T/P5 freshness сохранена.

## Три отдельных gate/evidence

[StorageAdmission](../../../app/src/main/java/com/fantadrom/copperscroll/storage/android/StorageAdmission.kt)
сопоставляет authority/package/version/build, Android/API/build, root/volume/backend
и failure model. Unknown dimension не совпадает с approved profile. Изменение
любой dimension, потеря grant/документа или фактической identity не наследует
capability другого URI/root. Источник, backup root и candidate root проверяются
независимо, до create/credentials и повторно после reopen/перед switch.

| Гарантия | Применение | P6a.1 production |
| --- | --- | --- |
| ExistingWriteDurable | Existing T; нет допуска → readonly OpenOriginal/отказ | Не квалифицирована, false |
| DurableCreateIdentity | Create/VerifiedCopy/candidate/import/restore; требует exclusive/non-replacing contract | Не квалифицирована; отказ до create |
| DurableLocalState | Binding+journal и maintenance/switch | Не квалифицирована; production maintenance запрещён |

Обычный release/debug и p1Release регрессий компилируют пустой productionPolicy.
Публичного forceAllow/trustedProvider/set-policy API нет. Нельзя получить допуск
из flags/seek/fsync/reopen/probe. `forceIdentity` не повторный file fsync: production
возвращает UnsupportedDurability; test contract выполняет namespace publication
в самом provider, который знает собственный каталог. Future production contract
должен определить собственный подтверждающий шаг в P6a.2.

Только opt-in `p6aReleaseRuntime` компилирует test registry: точная собственная
authority/UID/package, roots a/b/c, backend p6a-controlled-v1 и явно ограниченная
process/fault model. Это `P6A-HARNESS-ONLY`, не Synthetic/JvmSynthetic и не
production qualification. `MaintenanceProfile.AndroidGated` не даёт гарантий сам
по себе. Отдельный local decision привязан к app package/build/OS/директории/
stat volume; local test model не выдаётся за физическую долговечность Android.
Адаптеры/core/crypto одинаковые в test target и ordinary release.

## App-private local state и подключение P3/P5

[AppPrivateLocalState](../../../app/src/main/java/com/fantadrom/copperscroll/storage/android/AppPrivateLocalState.kt)
публикует один bounded LocalStateCodec v1/v2 record≤65536B в Context.noBackupFilesDir:
create own same-directory temp → полный write → fsync temp → Os.rename replace →
fsync parent directory → bounded decode/byte equality → ACK. Directory sync через
публичные O_RDONLY/O_CLOEXEC и fstat S_ISDIR; O_DIRECTORY не часть SDK API,
числовые/hidden API обходы не использованы. Проверяются initial directory и его
parent namespace. Stable state.lock inode + process registry сериализуют публикации
и переживают замену state.bin. AtomicFile не подменяет lock/parent sync.

Первый binding создаётся только explicit initialize. Durable init-intent marker
публикуется до metadata. Fresh существует только для вновь созданного собственного
каталога; существующий каталог без state/marker, потерянный state, corrupt/truncated/
unsupported record не становится пустым binding. Маркер проверяется. До rename
обычный отказ — IoFailure; после начала rename/force/readback/ACK либо прерванного
initialize — StateUnknown. Последующий writer блокирован; reconcile читает только
целую валидную запись и повторяет sync, а выбор документа требует полной P5
проверки с нужными credentials/grants/profile. Нет mtime выбора или replay по opId.

SafMaintenanceDocuments используется общими SnapshotCopier/MaintenanceCoordinator/
MaintenanceRecovery. `requireDurableArtifact` сохранён; копия committedLength,
полная проверка обеих зон, одна backup/candidate и explicit binding switch прежние.
SafCopyOperations import/restore используют P5 copier, сохраняют portable IDs,
не публикуют binding и не чинят источник. Ошибка после создания выдаёт
SafCopyFailure(CopyUnknown) с известным unverified Artifact либо без identity при
потерянном create outcome; primary/cleanup exceptions сохраняются. Это не healthy
VerifiedCopy и не право удалить файл по имени. Непригодный к надёжному snapshot источник
не получает VerifiedCopy. Unknown create outcome не даёт права удалить по имени;
cleanup только известного собственного неподтверждённого artifact с rechecked identity.
OpenBound сохраняет generation-progress contract: обычная T не требует sidecar ACK
для каждой записи. Recovery P5b различает old-source/new-candidate secrets.

## Android test provider, IPC и нормативная матрица

P6aSafRuntimeTest содержит13 методов; [полные имена и команды](../../../tools/p6a/android-runtime-windows.md).
Production adapters исполняются через настоящий ContentResolver/PFD/Os/Binder.
Provider и helper в отдельных Linux процессах, но том же target UID; instrumentation
также исполняется с target UID. Это own-provider доступ, не поддельный внешний grant.
DocumentsProvider.attachInfo требует exported=true + MANAGE_DOCUMENTS; операции
дополнительно проверяют calling UID, roots скрыты из пользовательского picker.
Helper/service/Activity не exported. Всё отсутствует в ordinary release.

Test provider моделирует readonly/pipe/virtual/unknown length/stale flags/size,
missing/denial/death/replacement/aliases/backend change, known/unknown create
failure и namespace loss. Forced bytes сохраняются отдельно потоковой копией;
model data loss и namespace loss независимы. Test-only syscall shim на границе
настоящего adapter даёт short/EINTR/zero/EIO/ENOSPC/blocking sync. Это fault
injection, не физическая неисправность носителя. Independent helper проверяет
locks, reopen gap, local lock и реальную смерть/новый PID. Дополнительно production
coordinator в helper доходит до durable SwitchIntent и убивает только собственный
процесс, без exception/finally cleanup. Main controller после Binder death открывает
metadata заново, отвергает старый target secret в candidate context и явно
подтверждает Candidate с новым secret. Только фиксированные synthetic credentials
и собственный test-provider URI допускаются этим test-only IPC entry. Это process
death, не power loss. Late IO не отменяется фиктивным close; сохранённых engine
keys на restart нет.

| Нормативный ID | P6a.1 test/evidence | Результат агента | Открытая часть |
| --- | --- | --- | --- |
| SAF-01 | explicitModesAndNegativeAdmission; createTransactionCopyCompactRekey | Build/host, Android NOT_RUN | Двухустройственный adapter runtime; real provider qualification |
| SAF-02 | readOnlyPipeVirtualAndStaleMetadata; positionalShortInterruptedAndErrorIo; negative policy | JVM gate model PASS; Android NOT_RUN | Реальные grants/cloud/provider и аппаратная IO модель |
| SAF-03, DUR-04 | grantLossReplacementAndProfileMismatch; explicitModesAndNegativeAdmission (different UID/no grant/read grant/revoke); exact environment matcher | JVM dimension/access tests PASS; исправленные Android NOT_RUN | Реальные revoke/reinstall/move, provider/OS обновления |
| DUR-01/02 | dataForceDoesNotPublishNamespaceAndUnknownCreate; P3/P5 fault regressions | Host model; Android NOT_RUN | Controlled interruption и physical namespace/media durability |
| DUR-03 | noBackupPublicationFaultsAndWholeRecords; corruptLostAndVersionedLocalStateNeverReset | Codec/model regression; Android NOT_RUN | Реальные metadata syscall faults/restart/power interruption |
| TX-02/05, LIFE-06 | aliasInterprocessLocksAndContinuousReopen; blockingIoRevokeRetainsOwnerUntilQuiescence | JVM continuous lease PASS; Android NOT_RUN | Два процесса на обоих устройствах; реальные зависшие syscalls/OEM |
| MAINT-03/04/10 | actual SAF create/T/copy/compact/rekey, retained source/backup | Android NOT_RUN; прежние model regressions | Двухустройственный workflow, реальные storage contracts |
| MAINT-05/06/07/08 | switchRevocationLostAckAndDistinctRecoveryCredentials; processRestartLocalLockAndBoundGenerationProgress | Android NOT_RUN; model regression | Полный device crash-stage/revoke table и power loss |
| MAINT-02, LIFE-03/04 | pickerCancellationStaleAndRequiredFlags; отдельный system-picker probe | Android NOT_RUN | Полный Home/rotation/configuration lifecycle P6b |
| LEAK-01/03, MAINT-09 | bounded metadata/temp, no secret fields, APK isolation | Build/static checks | OEM backup/device transfer, RAM/IME/lifecycle/leaks P6b |

Частичный subcase не закрывает весь SAF/DUR/LIFE/MAINT ID. Process death/restart,
normal reboot, fault injection и physical power interruption — разные evidence.
Полного reboot/power interruption в P6a.1 агент не выполнял.

## Отдельный реальный root probe и P6a.2

[P6a Provider Probe](../../../tools/p6a/android-runtime-windows.md) — отдельная
Run-конфигурация/controller, запускаемая явно. System ACTION_OPEN_DOCUMENT_TREE
и фактический persistable rw grant; выделенная чистая тестовая папка. Ни тестовый
context grantUriPermission, ни ранее выбранный личный контейнер не используются.
Automatic runtime picker не ждёт. Controller агрегирует свежие receipts отдельно
для каждого устройства, сохраняет связь с audited artifactBundleId и source identity.

Probe создаёт один собственный synthetic scratch, наблюдает mode/FD identity/
positional IO/hash/length/file fsync/close+rw reopen/no-truncate/межпроцессный lock.
URI/root/scratch identity private; public receipt содержит root hash, provider
package/version/build/authority и OS/device, unknown volume/backend явно unknown.
При неизвестном compare-and-delete contract scratch сохраняется:
`RETAINED_NO_QUALIFIED_DELETE_CONTRACT`, исключая гонку stat→чужой replacement→delete.
Удаление только явно своего тестового файла пользователем после сбора отчёта.
Зависший IO не блокирует UI; timeout ошибочен, не PASS/доказательство отмены.

`existingWriteDurable=UNKNOWN`, `durableCreateIdentity=UNKNOWN`,
`durableLocalState=UNKNOWN`, `productionApproved=false` остаются раздельными,
даже при всех observed operations. Это observation receipt, не durability PASS.
Process restart и physical interruption real-root probe не заявляет. Automatic
helper restart проверяется отдельно. Пути receipts/diagnostics ниже.

P6a.2 должен для точных выбранных root/backend/build:

1. Изучить реализацию выбранного DocumentsProvider (ExternalStorageProvider и
   нижележащие MediaProvider/FUSE/volume layers применимой AOSP/OEM версии),
   rw/no-truncate/identity/alias/exclusive create/delete/grant contracts, поведение
   при remote/deferred backend. Отсутствующий проверяемый contract → нет допуска.
2. Установить отдельно data fsync ordering/сохранность ACKed ranges и namespace
   commit/documentId/URI доступность, устойчивость replacement и root remapping.
   Выявить provider-specific шаг forceIdentity; file fsync+reopen недостаточны.
3. Проверить noBackup rename/parent sync/first-create/lock protocol на точном
   app filesystem, corruption/restart и loss of ACK; оценить filesystem/storage
   stack failure bounds, не только результат AtomicFile или checksum.
4. Выполнить на выделенных synthetic данных controlled process death, normal
   reboot и отдельные interruption/namespace эксперименты с точной классификацией.
   Emulator hard stop не доказывает power loss цепочки Honor. Не требуются factory
   reset, разряд телефона либо риск для личных данных. Если безопасного стенда
   нет, physical gate остаётся открытым, allowlist пустой.
5. Связать evidence с authority/package/version/build/Android/API/root/volume/
   backend/failure model, перечислить пределы и инвалидацию обновлением. Включать
   каждый source/backup/candidate/local profile только после review evidence.

## Проверки и ограничения передачи

Linux, Oracle JVM25.0.4.1+1-LTS-5, wrapper9.6.0 с checksum, AGP9.4.1,
Kotlin2.2.10, compileSdk37/min31/Java11 и зависимости сохранены. SDK public API
проверены javap android-37.0/android.jar; ownership/режимы/ограничения по официальным
[ContentResolver](https://developer.android.com/reference/android/content/ContentResolver),
[PFD](https://developer.android.com/reference/android/os/ParcelFileDescriptor),
[DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider),
[SAF](https://developer.android.com/training/data-storage/shared/documents-files),
[Os](https://developer.android.com/reference/android/system/Os),
[AtomicFile](https://developer.android.com/reference/android/util/AtomicFile) и AOSP
[Android15 источникам](https://github.com/aosp-mirror/platform_frameworks_base/tree/android-15.0.0_r1/core/java/android).
AOSP/API не сертификат конкретного provider/OEM. Новых native/dependencies нет.

Полный JVM/fault regression: **154 tests,0 failures/errors/skipped**,
`final-host-debug.log`, BUILD SUCCESSFUL7m24s с debug/debugAndroidTest/lintDebug.
После финальных уточнений FD/initial namespace/picker повторены8 новых JVM
adapter/model tests,0 failures/errors/skipped, debug/debugAndroidTest/lintDebug —
BUILD SUCCESSFUL1m44s. Полный XML сохранён отдельно до subset run; это не один
162-test запуск. Debug lint0 errors/13 warnings/1 hint.

Host gates: **219 checks PASS**: P1=33, P2=15, P3=13, P4=18, source=86,
P5a=18, P5b=18, P6a=18. Source86 включает четыре новых variant/tool/assets inputs
в каждой autocrlf false/true/input fixture; исходники проекта не нормализуются.
Windows read-only/Git-process cleanup fixes сохранены; здесь выполнялся Linux,
нового фактического Windows-прогона агента нет.

Fault regressions: P3=6820 cases; P4=25767; P5a=437 IO events/1798 cases,
288 metadata faults,8 durable snapshots,100 immediate pre-cleanup crash images;
P5b=715 events/2938 cases,288 metadata faults,8 snapshots,156 crash images.
Seeds и lost/retained volatile bytes/namespace модели прежние, не device power loss.
Fixtures P1=28/P2=31/P4=3positive+12negative/P5b local v1/v2 — PASS;
reconciliation6 wire/21static S1, docs44links/28tables/116IDs/13resolution rows,
10 новых локальных ссылок и abstract protocol model — PASS. JVM включает полный
прикреплённый Unicode corpus; независимый conformance P1 не переименован в новый
Android результат. Точные13 runtime методов согласованы между suite/audit/gate/docs.

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew :app:testDebugUnitTest --tests '*StorageAdmissionTest' --tests '*ContinuousLeaseTest' :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew -Pp6aReleaseRuntime=true :app:verifyP6aIsolation :app:verifyP4BuildIdentity :app:lintP1Release --offline --no-daemon --no-parallel --max-workers=2 --console=plain
```

Ordinary release и окончательная P6a target/test R8 пара собраны; полный audit,
source identity и isolation — PASS (`final-verified-apks.log`,4m34s;
после отдельных регрессионных профилей восстановлена P6a пара,3m18s).
P1Release lint:0 errors/25 warnings/1 hint; дополнительные warnings — test-only
KTX/I18n предложения. Внутренний сбой lint `Unexpected owner function: null` на
промежуточном helper analysis исчез при отдельном неизменённом повторе1m49s;
детекторы/проверки/версии не отключались и не обновлялись. Финальный lint PASS.

Отдельные P1/P2/P3/P4/P5a/P5b processed-APK audits — PASS (первый3m15s,
остальные14…16s), включая отсутствие P6a harness без opt-in. Шесть correctness
task graphs и P6a runtime/probe task graph — dry-run PASS, не Android execution.
P6a isolation: positive +4 negatives PASS; прежние8 негативных APK checks PASS:
debug substitution, второй backend, изменённый NFC, package access, missing
runner class/method, inaccessible method, неверный test mapping. Signed APKs
не изменены. Негативный скрипт сохраняет имя выбранного profile в своей отдельной
временной diagnostics directory; намеренно проверяются конкретные причины отказа.
Frozen local v1/v2 assets в test APK byte-identical исходным fixtures; synthetic
restart secret и все helper/provider/probe классы отсутствуют в ordinary release.

Промежуточные source gates правильно отказали при изменении исходников во время
review/build; они не обойдены через -x/отключение. Окончательная пара проверена
после замораживания source fingerprint. Подготовка выполнялась из working tree
до публикации: sourceRevision — master базы, sourceCodeDirty/sourceDirty/
workingTreeDirty=true, wrapperModified=false. Это не clean-build итогового commit.

```properties
artifactBundleId=9c98aec6a1a1736e29ce03cbda99c7c955c3f662a43d8f13f6fffc6e7657a169
targetSha256=f862e0a3133319b079a85c42faa387825ea8501ddd33ec1974162d29bae30a71
testSha256=5d756ebaa42ceb0942e62f841c6b7b8ce381a8461d3e8d2b0e80a1bb8f4fa5e0
targetMapId=db8b893400b50b74ffec1c735722c6ab572839f4ab5a4694312cb672d83fa4f7
testMapId=2ea9c2036651a012aa6a3319296f4753369f6eedd70e64b28b8b92c176b71b4a
targetMappingSha256=e611171de05cd0af8e4f22e5dcea897a4544d6b596b00e1ed22c061aae25b49d
testMappingSha256=fdcef01e61929ea74050d87e4570a6d5e725284af220858d627079368f0c7079
sourceRevision=ace1ee51d2c7d56a71e2aa1c007b0f928bd1c3f3
sourceFingerprint=2e8e27f12f40da7dff74bcc9db3f9c435dd8a177374c998fa664062181e83a40
runtime=NOT_RUN
```

Оба mappings, configs, generated ABI и SHA256 соответствующей APK-пары сохранены
в `app/build/reports/p6a-runtime/p1-diagnostics.zip`/artifact-manifest.properties;
копия доказательных артефактов в ignored work/p6a1/final-p6a-artifacts. Старый
mapping другого APK не используется как достоверный retrace. User build после
checkout commit получит собственные revision/fingerprint/dirty/Gradle/JVM и hashes.

```sh
python3 tools/p6a/check-isolation.py
P1_AUDIT_ARGUMENTS=app/build/reports/p6a-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
```


Android runner здесь не получен: нет emulator/system images/dev-kvm, свежий
`adb devices -l` завершился134 (`Cannot mkdir /home/agent/.android: Read-only file system`),
список устройств не получен. Android runtime/probe **NOT_RUN**.
Build/host/dry-run не подменяют runtime PASS. Старые P5b пользовательские98 PASS
не относятся к новым P6a APK. Full Unicode conformance и KDF benchmarks на Android
не запускаются заново без необходимости; device KDF measurements остаются открытыми.

R8 включён с прежней scope androidx/kotlin/kotlinx; storage/ICU/CBOR/crypto вне
optimization. Startup/lifecycle и штатная release подпись сохранены. p1Release
non-debuggable, но отличается suffix/local test signature/instrumentation ABI;
P6a opt-in также содержит test policy/provider/helper/probe. Ordinary release
собирается с настоящими adapters и пустой policy, проходит negative isolation
checks. Runtime не объявляется выполнением идентичного ordinary release.

Пользователь: [точная Windows инструкция](../../../tools/p6a/android-runtime-windows.md).
P6a runtime13 на каждом Honor15/emulator17, затем отдельно P1/P2/P3/P4/P5a/P5b
7/8/8/10/8/8. Нужны новые компактные receipts; при ошибке весь Terminal output
и текущий diagnostics ZIP. Runtime receipt:
`app/build/reports/p6a-runtime/runtime-result.txt`, diagnostics `p1-diagnostics.zip`
там же с обоими mappings/SHA256/source provenance. Provider отдельно:
`app/build/reports/p6a-provider/<runId>/<device>/provider-result.txt`.

Историческое требование передачи PR13 до подтверждения выше: PR Draft до двухустройственной Android матрицы; production admission/P6a.2,
полный P6b lifecycle/leaks/OEM, P6c UI, иконки папок/порядок каталога и независимая
security review остаются будущими задачами. Размеры60dp/строка≥72dp и demo сохранены.
Криптография/container1.1/1.2/cards/icons не менялись. Настоящие секретные данные
не разрешены этим этапом; merge самостоятельно не выполняется.
