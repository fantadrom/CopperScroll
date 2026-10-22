# Windows / Honor15 и emulator17: P6a.2

Ветка `codex/copper-scroll-p6a2-provider-qualification`, Draft PR P6a.2.
Android Studio Terminal (PowerShell), корень проекта. Wrapper из Git — Gradle9.6.0;
не менять Gradle/AGP/Kotlin/JDK/dependencies ради warnings. Подключить разблокированный
Honor ABR-LX1 Android15 и запущенный Virtual_Pixel_6 Android17, включить USB debugging,
подтвердить доверие ПК. Device selector IDE сам не фильтрует host controller:
по умолчанию проверяются ВСЕ online API31+ устройства. При необходимости выбрать
одно через `$env:ANDROID_SERIAL = 'серийный номер из Device Manager'`; перед общей
матрицей убрать `Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue`.

Устанавливаются тестово подписанные nondebuggable target `app-p1Release.apk`
(package com.fantadrom.copperscroll.p1runtime) и instrumentation
`app-p1Release-androidTest.apk` (.p1runtime.test). Используется настоящий backend
целевого APK после существующего R8. Storage/ICU/CBOR/crypto вне optimization scope;
тестовый target включает bounded harness policy, provider/helper/probe, test ABI
keep API, иной applicationId/подпись. Это не runtime обычного production release.

## 1. Автоматические correctness проверки: без picker/reboot

На финальном коде изменён SAF cleanup и target/test ABI. Нужен новый прогон обоих
устройств. Последовательно выполнить каждую готовую команду (по ОДНОМУ opt-in flag):

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true :app:p6aReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --console=plain
```

Counts на КАЖДОМ устройстве: P6a13, P1/P2/P3 7/8/8, P4/P5a/P5b10/8/8.
Всего124 завершённых runtime tests на двух устройствах,0 failures/errors/skipped.
Ожидаются runtime=PASS и BUILD SUCCESSFUL итогового gate. Ноль/частичный suite/crash
не PASS даже при BUILD SUCCESSFUL промежуточной connected-задачи. Старые команды
и exact XML/protobuf/runner exit/source/APK/freshness gates сохраняются. Run selectors
`P6a Release Runtime`, `P1…P5b Release Runtime` в Android Studio доступны как прежде.

Сразу скопировать `app\build\reports\p6a-runtime\runtime-result.txt` и аналогичные
`p1-runtime`, `p2-runtime`, `p3-runtime`, `p4-runtime`, `p5a-runtime`, `p5b-runtime`;
новый запуск соответствующей suite заменяет её текущие результаты. Не запускать KDF
benchmarks: они отдельный незакрытый этап. Прежняя приёмка P6a.1 сохраняется историей,
но не заменяет новый runtime изменённых APK.

## 2. Явный real-provider process experiment

Создать через Files отдельную ПУСТУЮ папку во внутренней общей памяти, например
`CopperScroll-P6a2-Scratch`. Не выбирать личные контейнеры/backup/Downloads root/
Android/data/USB/SD/cloud. На каждом устройстве будет системный picker: нажать
«Выбрать тестовую папку», выбрать эту папку и подтвердить реальный read/write grant.
Эксперимент создаёт/сохраняет только собственные synthetic scratch, не удаляет их.

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true :app:p6a2ProcessQualification --no-configuration-cache --console=plain
```

Готовая Run-конфигурация: **P6a2 Process Qualification**. Без ручных правок Gradle/
исходников/ADB. Она собирает/аудирует/устанавливает opt-in APK-пару, запускает ровно
prepareWorker и recoverWorker на каждом устройстве. В первом шаге принудительно
завершается ТОЛЬКО собственный helper после forced checkpoint; во втором обязательны
новый PID/session, тот же boot count, grants/bytes/local record/identity observations.
Android/OEM provider не убивается. Перед вторым сценарием можно выбрать ту же
выделенную папку; scratch имена уникальны, посторонние документы не перебираются.

Успех: **2 completed methods/device**, experimentOutcome=PASS,
productionAdmission=REFUSED и BUILD SUCCESSFUL. Три guarantee-поля UNKNOWN — ожидаемо.
Это process/cache-preserving проверка, не power loss или production qualification.

Результаты: `app\build\reports\p6a2-qualification\<hostRunId>\<deviceHash>\`
`qualification-result.txt`, `ledger.properties`, отдельные method receipts/logs;
общий `qualification-diagnostics.zip` в `<hostRunId>`. Пути печатаются в Terminal.

## 3. Возобновляемый штатный reboot

Сначала сохранить receipts шага2, затем:

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true :app:p6a2RebootPrepare --no-configuration-cache --console=plain
```

Run: **P6a2 Reboot Prepare**. Вновь явно выбрать отдельную scratch-папку на каждом
устройстве. Итог PREPARED, **1 completed method/device**, experimentOutcome=NOT_RUN;
BUILD SUCCESSFUL подготовки не означает завершённый reboot experiment.
Скопировать напечатанный **hostRunId** и всю его папку результатов. Не удалять данные
тестового приложения, не переустанавливать APK, не менять исходники/provider/OS,
не переключать набор устройств/ANDROID_SERIAL до resume.

Выполнить обычную «Перезагрузка» в меню питания Honor и обычную Restart в Android
эмулятора. Пользователь сам выбирает момент; автоматические команды НЕ перезагружают
телефон. После загрузки разблокировать оба устройства, дождаться adb online и выполнить,
заменив ТОЛЬКО `HOST-RUN-ID` на UUID из предыдущего вывода:

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true -Pp6a2RunId=HOST-RUN-ID :app:p6a2RebootResume --no-configuration-cache --console=plain
```

Run **P6a2 Reboot Resume** запрашивает тот же UUID через `$Prompt$`; Terminal-команда
выше подходит независимо от поддержки macro конкретной IDE. Вручную Gradle/код не
редактируются. Resume проверяет идентичность обоих УСТАНОВЛЕННЫХ APK, current source/
artifact/device set, private checkpoint, увеличившийся public BOOT_COUNT, новую
worker session, неизменное окружение, retained data и whole local state. Без реального
reboot — FAIL. Чужой/старый/consumed runId, потерянный checkpoint/grant или обновление
окружения — FAIL, не silent reset/fallback. Если resume не прошёл, сохранить диагностику
перед новой подготовкой. Повторный успешный resume того же checkpoint запрещён.

Успех пары: **2 completed methods/device в сумме prepare+resume**, experimentOutcome=PASS,
productionAdmission=REFUSED. В resume исполняется ровно1 новый метод/device. Штатный
shutdown может flush caches и не подтверждает внезапное исчезновение питания.

## 4. Прерывание VM/storage и физического питания

**NOT_RUN; готовой команды для отсутствующего безопасного стенда нет.** Нужны
описанные guest/host cache/flush/reset semantics и отдельный synthetic recovery стенд;
для Honor также документированный FS/mount/storage contract и безопасный отдельный
аппаратный стенд. Обычный Stop/закрытие эмулятора не выдавать за power loss. Не нужны
factory reset, разряд, батарея, bootloader/root или воздействия на личные данные.
Решение по стандартному SAF и возможному рабочему пути — отдельный
[ADR](../../docs/architecture/adr/001-saf-durability-boundary.md), не скрытая staging копия.

## Что прислать

При успехе: новые runtime-result.txt всех7 correctness suites + compact
qualification-result.txt/ledger.properties каждого device из process и, если выполнен,
reboot сценария. Raw XML вручную разбирать не требуется. В отчётах указать устройство,
API, source/artifact IDs и кто запускал. Для обычного успеха большие ZIP не нужны.

При сбое: весь Terminal output; текущий `qualification-diagnostics.zip` и
`app\build\reports\p6a2-build-attempts\<attemptId>\build-diagnostics.zip` (точный путь
в Terminal). Для reboot — тот же hostRunId, per-device prepare/recover logs и compact
phase report. При падении correctness suite — текущий
`app\build\reports\p6a-runtime\p1-diagnostics.zip` и runtime-diagnostics.txt.
Сохранить файлы ДО повторного запуска. Build finalizer не прикладывает старые mappings
как evidence новой неуспешной сборки. Source fingerprint/Windows readonly/EOL gates
не отключать через -x. Agent Android новых APK остаётся NOT_RUN до реального исполнения.

Scratch cleanup: без qualified compare-delete контракта документы retained, не ошибка
наблюдений. После сохранения evidence пользователь может удалить ТОЛЬКО собственную
выделенную scratch-папку средствами Files. Не удалять личные backup/контейнеры.
Лимит32 private sessions: при исчерпании сначала сохранить evidence, затем отдельно
переустановить ТЕСТОВОЕ `.p1runtime` приложение; это сброс диагностических checkpoints,
не обычного приложения. Активный reboot attempt таким действием уничтожается.
