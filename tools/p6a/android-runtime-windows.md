# P6a.1 — Windows / Android Studio / Honor и эмулятор

Ветка: `codex/copper-scroll-p6a1-saf-adapters`. Обновить checkout, открыть проект,
дождаться Sync. Использовать закреплённый wrapper **Gradle9.6.0**, существующие
SDK37/AGP9.4.1/Kotlin2.2.10. Не редактировать Gradle/исходники и не добавлять `-x`.
KDF benchmarks автоматически не запускаются.

Подключить разблокированный Honor ABR-LX1 Android15 с разрешённой USB debugging
и запустить эмулятор Android17 (API31+). Команда проверяет все online devices,
либо одно устройство, если ANDROID_SERIAL уже ограничивает выбор. Убрать это
ограничение для матрицы двух устройств. Offline/unauthorized устройство — ошибка.

## Автоматическая проверка

Run-конфигурация **P6a Release Runtime**, либо одна команда в Terminal корня:

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true :app:p6aReleaseRuntimeCheck --console=plain
```

Устанавливаются `app/build/outputs/apk/p1Release/app-p1Release.apk`
(`com.fantadrom.copperscroll.p1runtime`) и
`app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`.
Это non-debuggable p1Release с существующей R8 scope, Startup/lifecycle,
production backend в target и13 методов P6aSafRuntimeTest в test APK.
Тестовый контракт/provider/helper/probe находятся только в этом opt-in target.
Provider и helper работают отдельными процессами того же UID, что target;
instrumentation исполняется с UID target. Это собственный provider, ему не
подделывают внешний persistable grant. Проверка реальной выдачи grant — ниже.

p1Release отличается от ordinary release applicationId suffix, локальной
тестовой подписью, instrumentation ABI и тестовой policy/компонентами P6a.
R8 включён с прежней scope androidx/kotlin/kotlinx; storage/ICU/CBOR/crypto вне
optimization. Runtime результат не означает исполнение идентичного ordinary
release, qualification provider или physical power loss. Ordinary release
собирается и проходит отдельный audit отсутствия harness.

Ожидаются **13 passed,0 failures/errors/skipped на каждом устройстве**, строка
`runtime=PASS` и финальный `BUILD SUCCESSFUL`. Названия методов:

- explicitModesAndNegativeAdmission
- createTransactionCopyCompactRekey
- readOnlyPipeVirtualAndStaleMetadata
- grantLossReplacementAndProfileMismatch
- positionalShortInterruptedAndErrorIo
- dataForceDoesNotPublishNamespaceAndUnknownCreate
- noBackupPublicationFaultsAndWholeRecords
- aliasInterprocessLocksAndContinuousReopen
- blockingIoRevokeRetainsOwnerUntilQuiescence
- processRestartLocalLockAndBoundGenerationProgress
- pickerCancellationStaleAndRequiredFlags
- corruptLostAndVersionedLocalStateNeverReset
- switchRevocationLostAckAndDistinctRecoveryCredentials

Gate требует точный набор XML+protobuf+exit+freshness+APK/mappings/source identity
на каждом устройстве. Ноль/неполный/старый/skipped/crash результат — ошибка даже
при успешном connected task. Automatic suite не ждёт picker.
Receipt: `app/build/reports/p6a-runtime/runtime-result.txt`. Сохранить его перед
следующим runtime запуском. Диагностика: та же папка, `p1-diagnostics.zip`, оба
mapping, APK SHA256/map IDs и build-identity. Сборка не даёт runtime PASS.

## Отдельный P6a Provider Probe

Run-конфигурация **P6a Provider Probe**, либо:

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true :app:p6aProviderProbe --console=plain
```

Controller устанавливает текущую audited APK-пару и по очереди открывает
диагностическую Activity на каждом online устройстве. На текущем устройстве:

1. Нажать «Выбрать тестовую папку».
2. В системном SAF picker выбрать локальное хранилище и создать отдельную пустую
   папку, например `Documents/CopperScroll-P6a-Probe`. Это исключительно тестовая
   папка; личные контейнеры/каталоги не выбирать.
3. Нажать штатное «Использовать эту папку»/«Разрешить», выдав read+write grant.
4. Дождаться сообщения о сохранении наблюдений и продолжения Terminal.
   Повторить на втором устройстве, когда controller откроет там Activity.

Android может запрещать корень накопителя/Download/Android/data/obb; выбрать
разрешённую вложенную папку, не обходить SAF и не использовать root телефона.
Отмена, недостающие flags, ошибка или timeout не дают успешный probe. UI остаётся
отзывчивым при IO;10минутный timeout не означает остановку syscall или PASS.

Создаётся только `cscroll-probe-<runId>.bin` с synthetic данными. Probe не
перечисляет и не читает прочие файлы. URI/root остаются в app-private noBackup;
публичный receipt содержит hash root и статусы, без имён пользовательских файлов.
Он фиксирует фактически наблюдённые create/positional IO/fsync/reopen/length/hash/
межпроцессный lock/cleanup. При неизвестном provider scratch сохраняется со
статусом `RETAINED_NO_QUALIFIED_DELETE_CONTRACT`: stat→delete имеет гонку замены.
После сбора receipt пользователь может удалить только явно созданный тестовый
`cscroll-probe-<runId>.bin` в выбранной тестовой папке. Controller не ищет файлы
по имени и не удаляет неизвестный результат create. Это не namespace durability и не qualification.
Provider/backend/volume, которые нельзя надёжно определить, остаются `unknown`.
Process restart, reboot и physical interruption не заявляются этим probe.

Результаты: `app/build/reports/p6a-provider/<runId>/<device>/provider-result.txt`;
точный путь печатается в Terminal. Успешный сбор наблюдений заканчивается
`productionApproved=false`. Три durability evidence status остаются UNKNOWN.
Отдельный provider receipt прислать вместе с runtime receipt; system picker grant
не заменяется `grantUriPermission` из test context.

## Регрессии после P6a

Каждую команду выполнить отдельно, сохранив соответствующий runtime-result.txt
перед следующей. Не включать несколько flags одновременно.

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --console=plain
```

Counts на каждом Honor15/emulator17: **7/8/8/10/8/8**;0 failures/errors/skipped,
`runtime=PASS`, `BUILD SUCCESSFUL`. Receipts в `app/build/reports/p1-runtime`,
`p2-runtime`, `p3-runtime`, `p4-runtime`, `p5a-runtime`, `p5b-runtime`.
Прежние P5b receipts не подтверждают новую пару P6a.

При обычном успехе достаточно компактных receipts (P6a,6 регрессий,отдельный
provider). При ошибке — весь Terminal output и текущий `p1-diagnostics.zip`
соответствующей suite; для probe также его instrumentation.txt и provider receipt,
если создан. Старые ZIP/mapping другой сборки не подходят. PR остаётся Draft
до подтверждения матрицы; production admission/P6a.2/P6b/P6c этим не закрываются.
