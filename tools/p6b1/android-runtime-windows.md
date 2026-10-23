# P6b.1 — Windows / Honor Android15 / emulator Android17

Ветка `codex/copper-scroll-p6b1-session-lifecycle`, новый Draft PR; не master.
Android Studio: Git → Update Project, Gradle JDK проекта (нормативный wrapper9.6.0),
дождаться Sync. Исходники/Gradle/keep rules вручную не редактировать. Подключить
разблокированный Honor с разрешённым USB debugging и запустить Android17 AVD.
Оба должны быть online; если ANDROID_SERIAL ранее задан, удалить его в этом
Terminal (`Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue`).
Личные контейнеры/пароли не используются. Не отключать PIN/биометрию.

## Автоматическая suite

Run configuration **P6b1 Release Runtime** либо одна команда из корня проекта:

```powershell
.\gradlew.bat -Pp6b1ReleaseRuntime=true :app:p6b1ReleaseRuntimeCheck --no-configuration-cache --console=plain
```

Устанавливаются `app/build/outputs/apk/p1Release/app-p1Release.apk` и
`app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`.
Target `com.fantadrom.copperscroll.p1runtime`, nondebuggable, тестовая debug-подпись,
существующий release R8 scope; backend только в target. Это p1Release с opt-in
host/provider/helper, suffix/test-signature/ABI export; **не идентичный ordinary release**.
Optimization scope остаётся androidx/kotlin/kotlinx; storage, ICU и новые application
session/adapter classes находятся вне него. Ни R8, ни Startup/lifecycle не отключены.
Test host держит экран включённым только пока виден (FLAG_KEEP_SCREEN_ON), чтобы
таймер бездействия не требовал PIN посреди correctness suite; кнопка Power/ручная
блокировка по-прежнему работают. Production Activity этот флаг не получает.
Автоматический запуск не просит выбирать папку/разблокировать экран посреди теста.
Не нажимать кнопки test host во время automatic suite.

Ожидается **ровно8 completed PASS на каждом устройстве**, 0failed/errors/skipped,
класс `P6b1SessionRuntimeTest`, методы:

1. `secureLoginRolesAndCommands`
2. `homeRevokesWithoutAutomaticReentry`
3. `ownedDialogIsSecureAndHomeRevokes`
4. `configurationHandoffRetainsMemoryOwner`
5. `lateCredentialCompletionCannotReopen`
6. `finalCardWaitsForIoWithoutDelayingHide`
7. `boundPickerRejectsLateWithoutGrant`
8. `helperRestartStartsLocked`

Home/dialog/recreation/IPC — реальные Android transitions. KDF-result и IO barriers
управляемые, credential/core настоящие synthetic. Picker test использует настоящий
Activity transition и отмену/late rejection; не квалифицирует DocumentsUI/OEM grant
поведение. Process test убивает helper, не основной UI process. Его fresh Locked
не считать полным OEM process-recreation evidence.

`BUILD SUCCESSFUL` недостаточно без строгой сводки exact suite/device/count.
Gate отклоняет zero/partial/skipped/crash/foreign/stale XML/protobuf/runner exit;
старые результаты перед запуском удаляются. Compact receipt:
`app/build/reports/p6b1-runtime/runtime-result.txt`. В receipt находятся source/fingerprint/dirty/wrapper/Gradle/JVM,
artifactBundleId, hashes APK и обоих mappings. Completed count читается в этом файле,
а не выводится из числа Gradle tasks. HTML: `app/build/reports/androidTests/connected/p1Release/`.

После обновления PR №15 Home test ждёт настоящий уход Activity и новое foreground-
состояние; один focus недостаточен. Вход по-прежнему один, без автоматического retry.
При новом отказе XML/logcat содержат `P6B1_SAFE_TRACE`: attempt epoch/outcome,
access/reason/owner/pendingWork/handoff, foreground facts и ограниченную lifecycle
history. `SUBMISSION_REJECTED`/`CLAIM_REJECTED`/`COMPLETION_REJECTED` отличаются от
`OPENED_THEN_REVOKED`. Trace с phase=at_failure/at_login_failure относится к отказу;
phase=after_cleanup — только fallback после teardown. Не считать возврат в Open
автоматическим разрешённым результатом: после каждого Home нужен явный test login.

## Отдельный guided lifecycle probe

Run **P6b1 Lifecycle Probe** либо:

```powershell
.\gradlew.bat -Pp6b1ReleaseRuntime=true :app:p6b1LifecycleProbe --no-configuration-cache --console=plain
```

Открывает изолированный test host поочерёдно на выбранных устройствах; на каждое
отведено15мин. Terminal печатает `hostRunId=...`, затем имя устройства. Не путать его
с `deviceRunId` в individual receipt. Test host содержит только synthetic текст.
После каждого revoke нажимать **Synthetic Main**/**Synthetic Decoy** явно, когда
owner завершён; возврат/Back/focus сами вход не выполняют. Состояние показывается
над editor. При revoke editor должен исчезнуть, очистить текст/selection и перестать
принимать ввод, свой dialog закрыться. Сравнивать результат с шагами:

1. Войти, выключить экран, заблокировать обычным способом; разблокировать вручную.
   Должен быть Locked; явный новый вход. Нажать `Confirm observed: screen_off_lock`.
2. Войти, раскрыть notification shade; вернуться — Locked. Повторить со штатным
   системным dialog/permission UI, доступным на конкретной ОС, и с собственным
   dialog (`Own dialog`): собственное окно само доступ не отзывает, Home/shade
   поверх него отзывает. Нажать `Confirm observed: shade_system_dialog` только
   после проверки; недоступный системный сценарий записать пользователем как NOT_RUN.
3. Войти, вызвать обычную IME касанием editor, ввести только synthetic текст.
   Обычная IME сама не отзывает доступ; Home/выключение поверх неё отзывает.
   Подтвердить `ime`. Не вводить реальные секреты, не проверять PIN автоматизацией.
4. Войти, split-screen/multiwindow, передать top-resumed другому приложению.
   Resumed само по себе недостаточно: потеря top/видимости должна дать Locked.
   Подтвердить `multiwindow`.
5. Войти, rotation/resize, а также кнопку `Recreate`; без background сохраняются
   memory epoch/worker и сессия, скрытая на неопределённом handoff. Home/screen-off
   во время перехода всегда блокируют. Подтвердить `rotation_resize`.

Если наблюдение не соответствует требованиям, не подтверждать шаг ради зелёного
результата: завершить probe и прислать диагностику/описание. `Finish manual probe`
сохраняет ограниченный metadata receipt, отсутствие шагов/реального screen-off
отклоняется. Это **USER_OBSERVATIONS_COLLECTED**, не автоматическая полная LIFE/LEAK
сертификация. Все API callbacks помечены ACTUAL, user ACK отдельно; injected model
тесты не создают manual PASS. Переходы системных окон/OEM, rotation и IME требуют
ручного описания результата даже при collected receipt.

`app/build/reports/p6b1-manual/<hostRunId>/device-0/manual-result.txt`
и `device-1/manual-result.txt`: deviceRunId, наблюдения, source/artifacts provenance.
Там же `instrumentation.txt` и `lifecycle-diagnostics.zip`; ошибочный ответ сохраняется
до строгого gate. Диагностика compile/R8/audit/connected/manual:
`app/build/reports/p6b1-build-attempts/<attemptId>/build-diagnostics.zip`.
Artifact/runtime diagnostics: `app/build/reports/p6b1-runtime/p1-diagnostics.zip`.
Старые mappings при неуспешном текущем audit не выдаются за текущие.

## Регрессии на финальном коде

Нужен новый P6a13/device: общие helper/manifest/variants изменены. Изменены также
production owner/core/card/picker и общий APK ABI, поэтому нужны P1–P5b заново,
отдельно по одному флагу (никаких KDF benchmarks):

```powershell
.\gradlew.bat -Pp6aReleaseRuntime=true :app:p6aReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --console=plain
```

Ожидания на **каждом** устройстве: P6a13, P1=7, P2=8, P3=8, P4=10, P5a=8, P5b=8.
P6a.2 reboot/power qualification не повторять из-за одной смены base;
production admission остаётся REFUSED.

При успехе прислать compact receipts автоматических suites и оба manual-result,
короткое описание пяти ручных сценариев. При ошибке — полный текущий Terminal,
актуальный diagnostics ZIP; для manual также lifecycle-diagnostics.zip. Не нужен
полный build tree. Не прикладывать скриншоты реальных данных/пароли/URI личных файлов.
P6b.1 до этих прогонов NOT_RUN у агента и не принят; P6b.2/P6c и storage decision
ADR-001 остаются отдельными условиями.
