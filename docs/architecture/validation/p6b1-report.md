# P6b.1 — SessionCoordinator и Android lifecycle boundary

23 октября 2026. Ветка `codex/copper-scroll-p6b1-session-lifecycle` от актуального
master `4f55be9ebff9603c727df45bf547ea22bd490f2a`, merge PR14. Fetch/API/ancestry
подтвердили принятого предка `1ef434087e5ddfc67631f0d86f8ac7b094c65047`;
исходное дерево чистое. Новый отдельный Draft PR, без merge. AGENTS.md не найден.
Применены design§6/6.1/7/8, format1.1/1.2, review-resolution, prototype/test-plan,
P4 extension, ADR-001, отчёты P1–P6a.2 и существующие core/owner/maintenance/card/
legacy UI/Android/source/APK gates. Применён cloud-environment-runtime skill для
среды/сетевых ограничений; приложение не получило сетевых возможностей.

**P6b.1 пока не принят. Android агента NOT_RUN. Production storage admission
REFUSED; allowlist пуста, ExistingWriteDurable/DurableCreateIdentity/
DurableLocalState UNKNOWN.** Storage-путь не изменён; app-private canonical
working container не вводился. [Пользовательская инструкция](../../../tools/p6b1/android-runtime-windows.md).
[Подтверждение P6a.2](p6a2-report.md#пользовательское-подтверждение-p6a2--23-октября-2026)
перенесено из поручения с точными source/bundle/run/boot/dirty identifiers;
не предоставленные в этом поручении архивы агентом не читались.

Пользовательский прогон исходного `21f0b57` из `reports2.zip`: Honor Android15 —
8/8 PASS, emulator Android17 — 6 PASS + 2 FAIL. Это не полная двухустройственная
приёмка; подробный разбор и исправление приведены в конце отчёта.

## State/event → required outcome

| Состояние/событие | Решение сериализатора | Presentation | IO/worker |
| --- | --- | --- | --- |
| Fresh process/attach | Locked, неизвестные facts не дают capability | secure, empty/disabled | owner отсутствует |
| Все visible+interactive+unlocked+top+focus facts | Только допускают явный unlock | Locked до authenticated result | прежнего owner/Unknown нет |
| Explicit unlock | Immutable issuer/epoch/document token; новая попытка инвалидирует прежнюю | hidden | real S1→CredentialManager.login→openBound/graph; owner claimed до KDF |
| Совпавший result | OpenMain/OpenDecoy только из key/zone/role/instance/binding | разрешён актуальный epoch | handle передан owner |
| Home/screen-off/lock/invisible/not-top/unknown focus | Epoch revoke, нет auto return | text/selection/composition cleared, input disabled/GONE; dialogs dismissed/reveal inaccessible | stop, текущая checkpoint T + максимум одна заранее готовая N+1 |
| Own modal/menu/IME scope | Только bounded текущий window/epoch; не отменяет top/screen/visibility | child secure; неизвестная focus loss отдельно | нового owner нет |
| Configuration handoff | Memory-only old/new window token; epoch/owner/worker те же | hidden до свежих положительных facts | нет повторного unlock/closing save |
| Home/screen-off во время handoff | Revoke имеет приоритет | hidden | late old window ignored |
| Own picker | До launch checkpoint/revoke, затем отдельный Locked epoch/opId/purpose/document token | hidden | grant только после single consume; никакого auto import/binding/maintenance |
| Late/cancel/duplicate/foreign/old-window picker result | Denied/consumed cancel до resolver IO | Locked | invalid result не берёт grant |
| Main maintenance command | Role проверяется до worker; single-use ticket, пользовательская presentation скрыта | скрыта до core freeze/switch | existing P5 capability/owner AcceptSwitch, no service-decoy UI |
| Revoke раньше AcceptSwitch | Owner monitor отказывает switch | hidden | старый binding |
| AcceptSwitch раньше revoke | Принятое durable действие может завершиться | hidden, не open/retry | outcome/Unreported/Unknown; explicit reconciliation |
| ACK после revoke | Никакого нового capability/publish/Saved | Locked | только outcome и cleanup |
| Timeout/cancel при syscall/KDF | Не равен quiescence | hidden сразу | owner/FD/lease живы; второй writer Busy |
| CommitUnknown | Не Saved; новый unlock/writer запрещён | Locked | owner pinned, explicit resolve; нет retry rekey/switch/URI choice |
| Released | Нет активной работы и unresolved | Locked, явный новый вход | key/resources/lease закрыты ровно раз |

`SessionCoordinator` не зависит от Android. Его monitor сериализует admission,
revoke, unlock/picker/window tickets; owner AcceptSwitch использует тот же короткий
owner monitor, куда coordinator входит для revoke. Между двумя boundaries нет
ожидания core mutex: admit ticket не является AcceptSwitch, а worker не может
обойти owner revoke. Никаких KDF/codec/pwrite/fsync/traversal/wait/UI callback под
session guard. Презентация вызывается снаружи и перепроверяет epoch перед показом.
OwnerPhase.Active означает Running, pendingWork — admission slot, не доказательство
физического завершения IO. Одна command, одна unlock, один prepared checkpoint, один picker, максимум 4 own
scopes; issuer identity/single-use command claims/monotonic window sequence и
fail-closed epoch exhaustion. Новая попытка при pending worker отзывает старую,
но получает Busy до действительного завершения/release. Отдельный unlock-work fence
ставится до вызова factory: блокировка owner() до claim не оставляет возможности
запустить второй KDF/factory или обойти Busy сменой документа. Fence снимает только
finally фактически завершившегося worker. Готовый checkpoint делает admission
maintenance Busy, пока прежний ready payload не завершён/сессия не закрыта; он не
теряется при входе в служебный режим.

Право user Open создаёт только `SessionAuthentication.authenticate`: parsed document
identity/container/instance, настоящий credential match, binding/journal и root graph
`TransactionCore.openBound`. Нет production open(role) и API от CurrentCredential.
Maintenance работает с существующим P5 контекстом; его decoy/new-old keys не
становятся пользовательской decoy-сессией.

## Final checkpoint и IO lifetime

Worker заранее кодирует immutable `FinalCheckpoint` из последнего wholly-valid
`CardDraftSession`. На lifecycle boundary читается готовый slot; нет вызова
prepareSave/core stamp/codec на UI. Минимальный bridge `reserveFinalCheckpoint` /
`revokeWithFinalCheckpoint` / `submitClosingSave` использует **тот же** P3 commit,
publication/force/readback/ACK/Unknown pipeline. После фактического T готовится
только stamp/revision для уже захваченного payload; новый пользовательский payload
не принимается. Другая parent/type или неподдерживаемый graph отклоняются.

Owner сохраняет active checkpoint T и одну preaccepted closing permit N+1.
Повторный revoke не выпускает N+2; late timer/edit/prepare/reveal и Saved после
отзыва запрещены. Неподходящий/отсутствующий prepared payload не придумывается.
Смена ready slot требует retire прежнего на worker; lifecycle не закрывает
используемый buffer/FD. SessionWorker — один bounded worker, не shutdownNow;
cleanup после реального возврата работы. Owner registry держит сам owner/FD через
Unknown даже при потере UI references: GC не release/reconciliation. Закрытие
resources/store сохраняет первичную ошибку и suppressed; worker хранит только
безопасный тип/frames/count, не message/content. Explicit resolve не открывает UI
и не повторяет switch/rekey. Перед P6c application scope обязан удерживать
reconciliation controller; создание нового ViewModel не способ обойти Busy.

CardDraftSession остаётся headless. Семисекундный autosave, рабочий NoteSaveHook ACK,
подключение WorkspaceScreen и полный editor bridge не добавлены. Memory-only
legacy NoteSaveHook не объявлен долговечной записью.

## Android adapter, windows и backup

Production `AndroidSessionSignals` принимает явные start/resume/top/focus/pause/
stop/leave/detach и API31-compatible `SessionWindowRoot.onWindowVisibilityChanged`.
Читает фактические PowerManager/Keyguard/decor visibility; одного RESUMED нет.
Application receiver переживает configuration handoff, screen-off и Home/system
close-dialog hints только отзывают доступ. ContextCompat receiver flags совместимы
с API31+, не используется API35-only метод. Exported receiver никогда не выдаёт
полномочия: поддельный сигнал способен лишь закрыть доступ. Доставка Home hint
во время внешнего picker/OEM не считается доказанной сборкой: automatic case
проверяет настоящий Home и late result, manual расширяет OEM coverage.

Handoff — явный memory token в том же ViewModel; известный recreate начинается до
Activity.recreate. OS isChangingConfigurations проверяется в callbacks и переносит
тот же token; old window/seq не управляет новой. Unknown handoff hidden, без общего
pause debounce. Ранние OEM rotation/top/focus ordering, resize/multiwindow и IME
проверяются separately guided, пока NOT_RUN. Обычная IME обычно не забирает window
focus; наличие IME не превращается в blanket focus exemption. Scoped API поддерживает
Modal/Menu/Ime, устройство проверяет actual IME поведение отдельно.

`SecureSessionPresentation` использует weak UI references, не хранит content.
Hide реально удаляет editor text/selection/composing spans, снимает focus, отключает
input/long-click, GONE; dismiss/clear own dialogs. Child focus наблюдается отдельно,
`FLAG_SECURE` устанавливается до show/первого чувствительного кадра и на Locked.
API33+ recents screenshots disabled; min31 использует secure flag. JVM/IME/RAM
zeroization в фиксированное время не обещается. Production MainActivity получил
только secure-by-default baseline; legacy demo не authenticated storage session.
Coordinator/worker не имеют Activity/View; Bundle/SavedState/Intent/preferences
не содержат session/password/editor data. Synthetic host save/restore отключён,
ViewModel держит только memory runtime. Отдельный helper IPC не переносит ключи/UI.

allowBackup=false; actual compiled release/opt-in manifests и оба AXML проверяются.
SDK AAPT2 разрешает actual manifest resource IDs через таблицу ресурсов APK, включая
сокращённые пути после optimization; проверка фиксированного res/xml имени недостаточна.
Также проверяются instrumentation APK и его собственные exclusions.
Full backup, cloud-backup и device-transfer имеют path="." exclusions по9 допустимым
domains: root/file/database/sharedpref/external/device_root/device_file/
device_database/device_sharedpref. device_external не используется. NoBackup,
keys in RAM и encrypted scratch policy прежние. Rules приложения не контролируют
общий SAF файл/чужие копии и не доказывают запрет любого OEM transfer.

Первичные источники, применимость31–37:

- [Activity API](https://developer.android.com/reference/android/app/Activity): top-resumed callback с API29, independent window-focus ordering, isChangingConfigurations/recreate; API33 setRecentsScreenshotEnabled guarded.
- [ContextCompat.registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int)): receiver export flags с совместимой ветвью через существующий AndroidX Core.
- [PowerManager](https://developer.android.com/reference/android/os/PowerManager#isInteractive()) API20; [KeyguardManager](https://developer.android.com/reference/android/app/KeyguardManager#isDeviceLocked()) API22.
- [FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE): window flag, не полная LEAK/OEM сертификация.
- [Auto Backup/XML](https://developer.android.com/identity/data/autobackup): supported domains, noBackup, Android12+ extraction rules и OEM allowBackup/device-transfer ограничения.
- [AOSP multi-resume](https://source.android.com/docs/core/display/multi_display/multi-resume), [ActivityThread Android16 QPR2](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java): framework ordering — контекст исследования, не доказательство Honor15/Android17 OEM поведения.

Evidence исходников: [координатор](../../../app/src/main/java/com/fantadrom/copperscroll/storage/session/SessionCoordinator.kt),
[worker](../../../app/src/main/java/com/fantadrom/copperscroll/storage/session/SessionWorker.kt),
[Android adapter](../../../app/src/main/java/com/fantadrom/copperscroll/session/android/AndroidSessionSignals.kt),
[presentation](../../../app/src/main/java/com/fantadrom/copperscroll/session/android/SecureSessionPresentation.kt).
[JVM suite](../../../app/src/test/java/com/fantadrom/copperscroll/storage/session/SessionCoordinatorTest.kt)
связывает LIFE-01/07/08 с `allForegroundFactsAndFreshProcessLocked`,
`eachRevokeIdempotentAndReturnNeverUnlocks`, `focusScopesNeverOverrideScreenTopVisibilityOrForeignWindow`;
LIFE-02 — с `handoffPreservesEpochButOldWindowAndHomeCannotOpen`;
LIFE-03/04 и MAINT-02 — с двумя picker tests;
LIFE-06/09 и MAINT-09 — с N+1, Unknown, cleanup, late-login и blocking-factory barriers;
MAINT-01/05/08 и LEAK-04 — с real-role, stale-login и обоими AcceptSwitch orders.
[Восемь Android методов](../../../app/src/p6b1AndroidTest/kotlin/com/fantadrom/copperscroll/storage/runtime/P6b1SessionRuntimeTest.kt)
и [отдельный guided probe](../../../app/src/p6b1AndroidTest/kotlin/com/fantadrom/copperscroll/storage/runtime/P6b1LifecycleProbe.kt)
подготовлены, но у агента не исполнялись. LEAK-03 проверяют
[compiled APK audit](../../../tools/p1/P6b1ManifestAudit.java) и
[мутации actual APK](../../../tools/p6b1/check-backup.py).

## Матрица нормативных subcases

PASS ниже относится только к исполненным JVM/host subcases после завершения проверок;
Android/manual всё ещё NOT_RUN. Полный ID одним subcase не закрывается.

| ID | Реализованный subcase и evidence | JVM/host subcase | Device/manual | Осталось |
| --- | --- | --- | --- | --- |
| LIFE-01 | policy revoke/hide, blocked force N+1 unit; Home runtime case | PASS | NOT_RUN | screen-off/lock actual probe |
| LIFE-02 | handoff token/epoch/window unit; actual recreation case prepared | PASS | NOT_RUN | actual rotation/resize/dialog/selection/OEM |
| LIFE-03 | window/document/purpose/single-use/cancel/late picker unit + actual Activity/Home case | PASS | NOT_RUN | DocumentsUI/OEM late RESULT_OK/process-death/grant observation |
| LIFE-04 | Locked bound SafPickerPort, resolver IO только после consume | PASS | NOT_RUN | actual tree grant/settings flow on devices, no auto main |
| LIFE-05 | P4/legacy folder/cursor semantics unchanged, новый engine UI не подключён | PASS | NOT_RUN новых регрессий | full editor integration P6c |
| LIFE-06 | force barrier, UI-independent revoke, Busy/release/Unknown/primary+suppressed unit; P3 owner and P2 KDF regression | PASS | NOT_RUN | actual blocked IO/KDF device + power/storage qualification separately |
| LIFE-07 | all foreground facts, stale events/foreign window unit; Android port/root/top implemented | PASS | NOT_RUN | actual shade/system dialog/multiwindow/OEM probe |
| LIFE-08 | own modal/menu/IME scopes + screen-off/Home/config unit; actual secure Dialog/recreate prepared | PASS | NOT_RUN | real IME/menu/rotation/resize corners, handoff interrupted by Home/screen-off |
| LIFE-09 | real last-valid Card checkpoint, N→N+1 no N+2, late result/epoch/Unknown unit | PASS | NOT_RUN | finalCard actual Home/IO runtime, final app editor P6c |
| LEAK-01 | no session saved-state/intents/logs API; memory-only test host; metadata-only bounded diagnostics | NOT_RUN (device); review выполнен | NOT_RUN device inspection | full noBackup/temp/IME/RAM/log review P6b.2 |
| LEAK-02 | production secure baseline, clear/disable/GONE editor, secure own window, actual runtime assertions prepared | NOT_RUN (device); review выполнен | NOT_RUN | screenshots/recents/capture/autofill/clipboard/accessibility/reveal/share/drag P6b.2 |
| LEAK-03 | actual manifest/XML audit + negative backup rule/APK mutations | PASS | NOT_RUN OEM/cloud | real backup/restore/device transfer P6b.2; external SAF excluded from claim |
| LEAK-04 | no CurrentCredential→user Open; main/decoy real auth and main-only coordinator gates; P5 service contexts reused | PASS | NOT_RUN | service key leak/closure/thumbnail/full screenshot policy independent review |
| MAINT-01 | actual Main/Decoy credential path + coordinator direct dispatch rejection unit | PASS | NOT_RUN | new device command bypass regression |
| MAINT-02 | separate Locked picker, bounded op/doc/purpose/window tokens; shared P6a harness preserved | PASS | NOT_RUN | valid/cancel/stale DocumentsUI grants + own picker corner cases |
| MAINT-03 | serialized command claim; prepared checkpoint+Unknown pin; existing P5 freeze/drain | PASS | NOT_RUN | final device P6a/P5 suites |
| MAINT-04 | existing source/candidate fingerprint/verify unchanged | PASS | NOT_RUN новых регрессий | full P5/P6a device regression |
| MAINT-05 | actual owner AcceptSwitch vs Session revoke order unit, no second accept | PASS | NOT_RUN | Home/device race on shared P5/P6a |
| MAINT-06 | accepted-before-revoke marks unreported; existing P5 lost ACK matrices | PASS | NOT_RUN | fresh P5/P6a outcomes while Locked |
| MAINT-07 | existing journal/binding atomic codec and P3–P6a fault tests reused | PASS | NOT_RUN новых регрессий | real durability remains UNKNOWN |
| MAINT-08 | new session cannot use service credentials; fresh helper process Locked test prepared | PASS | NOT_RUN | final old/new credential-context device regressions |
| MAINT-09 | owner lifetime/stop/quiescence/cleanup; safe primary stack metadata | PASS | NOT_RUN | actual cancellation/lifecycle/OEM + full leak audit |
| MAINT-10 | existing destination/space/retention contract unchanged | PASS | NOT_RUN новых регрессий | P5/P6a regression, no storage admission upgrade |

## Первоначальные проверки и передача (21f0b57)

Локально исполнено агентом на Linux / Oracle JDK 25.0.4.1, Gradle 9.6.0:

| Проверка | Результат |
| --- | --- |
| Полный `:app:testDebugUnitTest` | PASS: 190 методов, 30 suites; failures/errors/skipped=0 |
| Focused `SessionCoordinatorTest` + полный JVM | PASS: 17 методов; factory-before-owner fence, последующий real worker login и fail-closed picker overflow |
| Host result gates P1/P2/P3/P4/P5a/P5b/P6a | PASS: 33/15/13/18/18/18/18 fixture checks |
| `testP4SourceIdentity` | PASS: 92 checks; Windows-specific runtime здесь не исполнен |
| `testP6aRuntimeDiagnostics`, `testP6a2Qualification` | PASS: 20/242 checks |
| `lintDebug`, `lintRelease` | PASS: 0 errors; 13/15 warnings в прежних UI/resources |
| `assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest` | PASS; ordinary release обработан существующим R8 |
| Fixture tools P1/P2/P4 | PASS: 28; 31 фиксированное поле; 3 positive + 12 negative |
| Documentation/static/Python/diff checks | PASS; повторяются перед публикацией |
| P6b1 host result/manual runner gate | PASS: 24 checks; в том числе missing/duplicate/failing instrumentation exit и crash после строки OK |
| P6b1 opt-in сборки, `lintP1Release` и APK/source/isolation gates | PASS: target + instrumentation APK; 0 lint errors (34 warnings); actual manifests/XML, linkage/package access, 0 duplicate definitions, backend только target |
| Negative APK audits | PASS: 12 backup mutations; isolation positive + 4 negatives, external-provider manifest negative + 2 ACL negatives; оригиналы не изменены |
| Automatic/manual task graphs | PASS dry-run; один runtime flag; фактический Android runtime NOT_RUN |
| Android automatic/manual у агента | NOT_RUN; сборки и dry-run не являются runtime PASS |

Команды сборки (из корня, с сохранённым wrapper; служебные
`--offline --no-daemon --no-parallel --max-workers=2 --console=plain`):

```bash
bash gradlew :app:testDebugUnitTest --tests '*SessionCoordinatorTest' :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
bash gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:testP6aRuntimeDiagnostics :app:testP6a2Qualification :app:testP6b1RuntimeResults
bash gradlew -Pp6b1ReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity :app:verifyP6aIsolation :app:lintP1Release :app:testP6b1RuntimeResults --no-configuration-cache
python3 tools/p6b1/check-backup.py
python3 tools/p6a/check-isolation.py p6b1
bash gradlew -Pp6b1ReleaseRuntime=true :app:p6b1ReleaseRuntimeCheck --no-configuration-cache --dry-run
bash gradlew -Pp6b1ReleaseRuntime=true :app:p6b1LifecycleProbe --no-configuration-cache --dry-run
```

В окружении нет system images/KVM или предоставленного Android runner. Попытка
ADB в этом окружении завершалась abort при создании read-only `/home/agent/.android`;
это не свидетельство успешного перечисления либо проверки устройств. Android
результаты пользователя P6a.2 сохранены отдельно, к новой P6b.1 сборке не относятся.
Локальная сборка выполнена **до commit**: sourceRevision отражает исходный
master `4f55be9ebff9603c727df45bf547ea22bd490f2a`, sourceFingerprint
`6b98b3ca980c6bb2c8bc2267b1e3ed8a5e26c5ab2934988a06585c646fa99469`.
sourceCodeDirty/sourceDirty/workingTreeDirty=true, wrapperModified=false;
Gradle 9.6.0, Oracle JVM 25.0.4.1+1-LTS-5. Это не clean HEAD build и не
пользовательский Android receipt. Опубликованный commit содержит проверенные
исходники; пользовательская команда заново захватывает actual HEAD/fingerprint,
собирает APK и создаёт собственную привязанную к ним provenance.

Проверенные локальные артефакты (runtime=NOT_RUN):

```properties
artifactBundleId=85b619be40b5e1cb420ca86f4f9a1089c56b7868c166dde971f8702924719a8c
targetSha256=15b971ae37b35b149d244e6fec2dfcee9e1bc68b0185818882892bf8c8081213
testSha256=286183775df4cc2b957be811d806074d3ed94e780170ffca9cbe59b7e69c268b
targetMapId=fd9831715316a3f86191e51acc9a30f9f7456fe38f8031609b8c2a11dd3da1e4
testMapId=a33f5bb33d649a3d7370bab2aca61b29cebe76324f4205d8944fb68d3dfb0721
targetMappingSha256=5dc25a9cbb2a87b3417d033ed261267a12226ebab0a129bb3b9e1d8fb749f824
testMappingSha256=1121a17046f4a012006d9e2c0a70fcaf229351b16e01c0d4d00831f125d5b73e
ordinaryReleaseSha256=9b58787fc506bda5587f4a5ac942b473957fb74f4e191650f10685f9aeb33032
```

Оба полных mapping и hashes/configuration IDs сохранены в
`app/build/reports/p6b1-runtime/artifacts/` / `artifact-manifest.properties` и
`p1-diagnostics.zip`. APK audit подтвердил 3950 ICU resources, pinned Unicode16
и независимые fixtures; production backend только target, duplicate definitions=0.
Негативные аудиты мутируют отдельные копии APK, не подписанные оригиналы.
Точные original APK SHA256 не изменились после отрицательных проверок.

Новый JVM SessionCoordinatorTest содержит 17 содержательных методов, включая barriers,
real credentials/core, switch orders, picker identity и epoch exhaustion. Старые
fault matrices не скопированы ради count. Новый runtime exact set — **8 методов**
`P6b1SessionRuntimeTest`, перечислены в Windows-инструкции. Recreate/Home/dialog
реальны, IO/KDF-result барьеры управляемые; IPC — реальный helper death/new PID/session.
Обычный test-host login использует production SessionWorker; только отдельный
late-completion сценарий удерживает доставку настоящего credential результата
управляемым barrier. Локальное состояние LoginSource заимствовано: его владелец
удерживает/закрывает его после использования session/maintenance, не Activity.
Первичный безопасный worker failure доступен runtime assertions без content/message.
Отдельный manual probe не выбирается автоматической suite, не ждёт PIN посреди неё,
сохраняет safe receipt/первичный ответ до gate, требует explicit test login/step ACK.
User observations не объявляются automatic PASS. ADB exit=0 и строка OK не
достаточны для guided controller: нужен один `INSTRUMENTATION_CODE: -1`, без
shortMsg/failed/aborted/failures. Ограниченный журнал может вытеснять старые labels;
actual screen-off отдельно сохраняется как memory-only факт, поэтому вытеснение
первой метки не превращает завершённый сценарий в ложный отказ.

Toolchain сохранён: Gradle9.6.0/checksum, AGP9.4.1, Kotlin2.2.10, min31/SDK37,
Java target11, зависимости прежние. Agent JDK Oracle25.0.4.1 — не пользовательский
JetBrains25.0.3; warnings не повод менять зависимости/JDK. R8 scope только
androidx/kotlin/kotlinx, без blanket keep/Startup обхода; storage/ICU/application
session classes вне optimization scope. Target и instrumentation mappings сохраняются
отдельно вместе с SHA256 APK/map IDs; linkage audit читает реальные DEX.
Production backend только target. Test-signature/suffix/nondebuggable p1Release
и generated instrumentation ABI прежние; новый host отсутствует в ordinary release.
p1Release runtime не равен runtime настоящего ordinary release.

Нужны новые пользовательские Honor15 + Android17: P6b1=8/device и guided probe,
P6a=13/device. Owner/core/Card/picker и общий APK ABI изменены, поэтому нужны также
P1=7/P2=8/P3=8/P4=10/P5a=8/P5b=8 на каждом устройстве. Старые98 PASS имеют
source501ed72, не этот код. P6a.2 reboot не переигрывать лишь из-за base change.

Осталось отдельно: ограниченная пользовательская приёмка P6b.1; P6b.2 whole leak/
OEM/ordinary-release runtime; перед P6c явное storage решение ADR-001 с исполнимым
путём/гарантиями (app-private canonical path не согласован); затем editor integration
P6c; explicit KDF/performance benchmarks и независимая security review. Самостоятельный
merge не выполнять. Build/audit/host/model не заменяют Android acceptance.

## Исправление foreground-синхронизации после reports2.zip — 23 октября 2026

Работа в том же Draft [PR №15](https://github.com/fantadrom/CopperScroll/pull/15),
base изменения `21f0b57f39508c7c3e707154c8ca1bae78cefb42`. Перед изменениями API
подтвердил head, Draft/open, merged=false; исходное дерево чистое. Никакого merge.
Применён cloud-environment-runtime skill при возобновлении managed environment;
VPN не настроен, зависимости/toolchain не изменены.

### Проверенные входные данные и retrace

Архив `reports2.zip`, SHA256
`9c96d4abd56062dd941594579d07f768a60df2a6554aa0d9327b8ca27b314585`,
содержит вложенный runtime diagnostics ZIP с XML обоих устройств, per-method logcat,
protobuf/runner exit, обеими mappings и source/artifact manifests. Архив распакован
в отдельный ignored work-каталог; содержимое журналов не выполнялось как инструкции.

```properties
sourceRevision=21f0b57f39508c7c3e707154c8ca1bae78cefb42
sourceFingerprint=10d2df4e2111e9d083180174450f86c15572db7780d16c876360ce2efc474572
artifactBundleId=d19b38e822fc5d632acb400c2a5b7ae067d81da6d0fb82a30ab98b7287b8c266
targetSha256=c3fd81ab9377a954473cc20e4604ed142cf5515aff4e37ae5bdf436371772d35
testSha256=0344a1c8d6e7a84121c34be33ba9421b60a27af7417ce7dd61836c1418af226b
targetMapId=fd9831715316a3f86191e51acc9a30f9f7456fe38f8031609b8c2a11dd3da1e4
testMapId=a33f5bb33d649a3d7370bab2aca61b29cebe76324f4205d8944fb68d3dfb0721
```

Обе mapping SHA256 совпали с manifest:
target `5dc25a9cbb2a87b3417d033ed261267a12226ebab0a129bb3b9e1d8fb749f824`,
test `1121a17046f4a012006d9e2c0a70fcaf229351b16e01c0d4d00831f125d5b73e`.
ID из test stack совпадает с test mapping. Применён именно предоставленный
`test-mapping.txt`, R8 Retrace 9.4.24 из существующего AGP9.4.1 builder jar;
production storage frames вне optimization scope уже имеют исходные имена/строки.
Mapping другой сборки не подставлялся. Raw APK в архиве нет: их hashes здесь —
проверенная связь manifests/diagnostics, не повторный SHA256 отсутствующих APK.

Это **пользовательский Windows-прогон**, Gradle9.6.0 / JetBrains
25.0.3+-15898627-b508.16, sourceCodeDirty=false,
sourceDirty/workingTreeDirty=true, wrapperModified=true. Он не назван clean или
запуском агента. XML: Honor `tests=8, failures/errors/skipped=0`;
emulator `tests=8, failures=2, errors/skipped=0`. Runner exit=1,
runId `51381b6b-bf9c-40fc-b3f3-ec8277466023`; strict gate корректно отказал с
`Nonzero runtime failures`. Honor PASS сохранён отдельно, новый код этим не принят.

| Отказ | Установлено из соответствующего стека/журнала | Граница вывода |
| --- | --- | --- |
| homeRevokesWithoutAutomaticReentry | Retrace: test login:54, повторный вход в case:77; cause SessionCoordinator.beginUnlock:119. Logcat текущего test process: PAUSED 05:51:27.538, failure 05:51:27.721; нового RESUMED до отказа нет. hasWindowFocus не означал, что координатор получил новое foreground-состояние. | Синхронный admission Busy до создания нового unlock; это не сбой криптографии или закрытия store. |
| finalCardWaitsForIoWithoutDelayingHide | Retrace: case:119 → login:55 → await:41. beginUnlock вернулся без Busy, выполнение дошло до ожидания presentation. Предыдущие assertions Committed для обычной и closing записи, release уже пройдены; итоговое чтение содержимого карточки после повторного входа ещё не достигнуто. PAUSED 05:51:30.001, RESUMED 05:51:30.397. | Стенда на21f недостаточно, чтобы доказать, отвергнут ли submit/claim/late result или был краткий Open с последующим revoke. Не приписываем второму отказу недоказанную точную последовательность. |

Точные времена приведены как raw logcat timestamps архива. Сообщения crash_dump64
другого процесса не являются доказательством падения target или причиной отзыва.

### Исправление тестового стенда

- `home()` сохраняет sequence marker foreground Activity (host либо собственный
  picker) до настоящего Home; ждёт последующего STOPPED/DESTROYED и потери focus,
  одновременно с отсутствием presentation. Hide сам по себе больше не достаточен.
- `returnHost()` ждёт нового RESUMED относительно Home fence, current window и
  **всех** фактов production foreground predicate; старый focus не завершает wait.
  Живой pending IO при этом не маскируется: возврат окна и готовность нового unlock
  различаются, поэтому late-credential barrier может оставаться занятым.
- `login()` проверяет actual lifecycle/current window/fixture/production unlockReady
  и вызывает единственный login в том же UI dispatch. После принятого epoch нет
  повторного входа, catch-and-retry Busy или восстановления доступа. Отклонение/
  отзыв результата завершается FAIL с конкретным outcome. Poll20ms и deadline40s
  сохранены; ни фиксированной задержки стабилизации, ни увеличения timeout нет.
- Readiness/history helper находится только в p6b1Model (opt-in target/JVM), Android
  этапы поступают из настоящих Application lifecycle callbacks. Восемь исходных
  device методов и exact-set/count gate сохранены.
- При отказе safe trace присоединяется к исходной ошибке как suppressed либо
  включается в await failure до cleanup. Fallback после teardown явно помечен
  `phase=after_cleanup`; он не подменяет состояние в момент первоначальной ошибки.
  XML/logcat и существующий diagnostics ZIP сохраняют trace без отдельной ручной
  команды. Первичная ошибка не теряется.

### Изменения production-кода и безопасность диагностики

Только `SessionCoordinator`: read-only `Snapshot.foreground` (unknown/null и
visible/interactive/unlocked/effective top-resumed/focus/own-focus/window/sequence),
`unlockReady` для свежей idle попытки и opt-in ring из128 metadata transitions.
`diagnostics()` возвращает history + current snapshot атомарно под тем же коротким
guard. UnlockAccepted/SubmissionRejected/ClaimRejected/CompletionRejected записываются
в момент решения, до presentation callback. Поэтому краткий Open, отозванный до
следующего UI poll, не теряется и отличается от никогда не принятого результата.

History выключена по умолчанию; включена только synthetic host и явными JVM tests.
Нет новых production логов, файлов, окон, ключей/URI/контента/credentials в metadata,
нет observer/UI callback/IO под guard. Формат содержит access, epoch, reason, owner,
pendingWork, handoff, foreground facts, тип перехода и attempt epoch. Lifecycle labels
также bounded128; editor диагностируется только флагами visible/enabled, без текста.

Admission/revoke/owner/ClosingSave/maintenance/SafPickerPort правила, Android adapter,
ResourceOwner, TransactionCore, R8 scope/keep/signing и зависимости не менялись.
`beginUnlock` остаётся единственной авторитетной проверкой; read-only readiness не
даёт capability и не разрешает обход отрицательных foreground signals.

### Детерминированная регрессия и локальные результаты

Новый `RuntimeForegroundFenceTest` — пять содержательных методов:

1. Старые положительные facts/focus после hide, затем PAUSED → STOPPED → STARTED/
   focus → RESUMED без top → полный foreground; только явный login открывает UI.
2. Очередь воспроизводит pause между off-thread ready и UI action: новая проверка
   в UI не делает попытку; после положительных новых событий один явный вход.
3. Настоящий credential/core result после revoke отвергается; отдельно настоящий
   Open немедленно отзывается presentation callback до наблюдения caller. Outcomes
   CompletionRejected и OpenedThenRevoked различаются по сохранённому решению.
4. Отказы submit и owner claim имеют разные evidence/outcomes.
5. Ring ограничен128, serial монотонен, metadata не содержат synthetic secrets или
   document identity; в обычном coordinator history выключена.

Локально исполнено агентом на Linux (это не Android runtime):

| Проверка исправления | Результат |
| --- | --- |
| Focused SessionCoordinatorTest + RuntimeForegroundFenceTest | PASS: 17 + 5 методов |
| Полный testDebugUnitTest | PASS: 195 методов, 31 suite; failures/errors/skipped=0 |
| P6b1 result gate | PASS: прежние24 host checks; exact8/device не ослаблен |
| Source identity / runtime diagnostics | PASS: 92 / 20 host checks |
| lintDebug / lintRelease / lintP1Release | PASS: 0 errors |
| assembleDebug / assembleRelease / assembleDebugAndroidTest | PASS |
| p1Release target + instrumentation build, DEX/linkage/package access/source/manifest/isolation | PASS; backend только target, duplicate definitions=0, actual backup XML checked |
| Negative backup / isolation ACL audits | PASS: 12 / 7 negatives, оригиналы APK не изменены |
| Runtime task graph | PASS dry-run; восемь методов, без Android execution |
| Fixtures / static docs / diff | PASS: P1=28, P2=31 fixed fields, P4=3 positive+12 negative; ссылки/таблицы/diff |

Команды (плюс `--offline --no-daemon --no-parallel --max-workers=2 --console=plain`):

```bash
bash gradlew :app:testDebugUnitTest --tests '*SessionCoordinatorTest' --tests '*RuntimeForegroundFenceTest' :app:testP6b1RuntimeResults
bash gradlew :app:testDebugUnitTest :app:testP6b1RuntimeResults :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
bash gradlew -Pp6b1ReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity :app:verifyP6aIsolation :app:lintP1Release :app:testP4SourceIdentity :app:testP6aRuntimeDiagnostics :app:testP6b1RuntimeResults --no-configuration-cache
python3 tools/p6b1/check-backup.py
python3 tools/p6a/check-isolation.py p6b1
bash gradlew -Pp6b1ReleaseRuntime=true :app:p6b1ReleaseRuntimeCheck --no-configuration-cache --dry-run
```

Предкоммитная provenance исправления, отдельно от пользовательского reports2:

```properties
sourceRevision=21f0b57f39508c7c3e707154c8ca1bae78cefb42
sourceFingerprint=623027b5856552215b11ca6c6456316058a6142f056f6b379a929cf8b31769d7
sourceCodeDirty=true
sourceDirty=true
workingTreeDirty=true
wrapperModified=false
gradleVersion=9.6.0
gradleJvmRuntime=25.0.4.1+1-LTS-5
gradleJvmVendor=Oracle Corporation
artifactBundleId=f1ec3ececdfd13e5127ef0813d3746b2d9f6273569d179db35f3b1ccf8d7f5b3
targetSha256=10ae62e21f91b870e87a751a32ce9121cabb6e19489030a26933d22fc882171c
testSha256=492039531b5a425558011e98f3f6906e85d03893633863d69ad88fd23bd16f6f
targetMapId=4addffef33c1eb7629fe01ce0286032d1df043830058e2d4beb7b560bb0218ce
testMapId=afa6684af7b8f63989f800aaf922d0482b4ccb16eed603d145f0f1a3226d8b74
targetMappingSha256=63702f576b0dffbfc8eb39919d4055c63fa146dbf2ebd6465b2b97d036eb0c57
testMappingSha256=705f52ec00f9a6c6184cdf90ebd6f8e2acad1a6d66c2eff4b7b0f25f7cafbb34
runtime=NOT_RUN
```

Mappings сохранены вместе с APK hashes в текущем artifact bundle / diagnostics ZIP.
Исходники проверены до commit: sourceRevision указывает21f, dirty=true; опубликованный
commit содержит эти проверенные исходники. Пользовательская команда заново фиксирует
актуальный HEAD и собирает свои связанные артефакты. Старая Windows-сборка и новый
локальный build не объединяются в один runtime PASS.
Android у агента NOT_RUN: предоставленного runner нет. Новый runtime необходимо
проверить пользователем на обоих устройствах; прежний Honor PASS относится к21f,
первоначальная причина второго отказа ограничена отсутствовавшей диагностикой.
