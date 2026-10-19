# P2 Runtime — Windows / Android Studio / Honor15 / эмулятор17

Открыть проект в Android Studio, переключиться на ветку
`codex/copper-scroll-p2-crypto-keys`, выполнить Pull и Gradle Sync.
Не редактировать Gradle/исходники/keep rules. Используется wrapper9.6.0 проекта;
пользовательское отличие9.8.0 надо указать вместе с результатом, если оно сохранено локально.

На Honor ABR-LX1 с Android15 включить USB debugging и подтвердить RSA-разрешение.
Для второго прогона запустить AVD с Android17. Можно подключить оба сразу;
при одинаковых именах устройств запускать последовательно. Offline/unauthorized
устройства приводят к отказу. Удалить старый ANDROID_SERIAL из Terminal, если он
должен выбирать оба устройства; установленный ANDROID_SERIAL выбирает только одно.

Выбрать готовую Run configuration **P2 Release Runtime** и нажать Run либо
в Terminal Android Studio из корня проекта выполнить:

```powershell
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
```

Устанавливаются:

- target `app/build/outputs/apk/p1Release/app-p1Release.apk`,
  `com.fantadrom.copperscroll.p1runtime`, **non-debuggable, после R8**;
- instrumentation `app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`,
  `com.fantadrom.copperscroll.p1runtime.test`, после собственной обработки R8.

Имя variant намеренно `p1Release`: P2 переиспользует проверенный release-derived путь P1.
Локальная debug/test подпись общая для пары APK; production release signing не меняется.
Это отдельный test target с suffix/marker и instrumentation ABI rules; он не побайтно
идентичен обычному release. R8 включён с прежним packageScope androidx/kotlin/kotlinx;
storage/ICU/CBOR/BC вне этого scope. Backend берётся из target APK, test APK второй
копии не содержит. Пользовательского диагностического экрана нет.

Должны завершиться ровно **8 методов P2CryptoRuntimeTest на каждом выбранном устройстве**:

1. `primitiveKnownAnswers`
2. `independentFormatFixture`
3. `independentEnvelopesAndExpectedZone`
4. `ambiguousCredentialAndUnchangedSecret`
5. `kdfLimitsAndCancellation`
6. `recordTamperingAndNoPlaintext`
7. `freshRandomnessAndRngFailure`
8. `prepareNewKeyAndCleanup`

Итог — BUILD SUCCESSFUL **p2ReleaseRuntimeCheck**, а в
`app/build/reports/p2-runtime/runtime-result.txt` — `runtime=PASS`,
`variant=p1Release`, suite=P2CryptoRuntimeTest, новый runId и для каждого устройства
`tests=8 failures=0 errors=0 skipped=0`. При двух устройствах это16 завершённых tests.
Один BUILD SUCCESSFUL connected task или HTML с0 tests не считается успехом.
Новый запуск удаляет предыдущие Android reports, поэтому сохраните их до повторения.

HTML: `app/build/reports/androidTests/connected/p1Release/index.html`.
XML/protobuf/runner logs: `app/build/outputs/androidTest-results/connected/p1Release/`.
APK audit и manifest: `app/build/reports/p2-runtime/`.
Diagnostic ZIP по сохранённому имени общей утилиты:
`app/build/reports/p2-runtime/p1-diagnostics.zip`. Он содержит **оба** mappings,
эффективные configurations, generated API rules и SHA256/map IDs обоих APK;
immutable копия в `artifacts/<bundleId>/`. ZIP не содержит APK/секреты.

При ошибке прислать полный вывод **итоговой** команды, модель/API/ABI и версию Gradle,
`runtime-result.txt` (если появился), `run.properties`, HTML/XML/runner logs,
`apk-audit.txt` и diagnostic ZIP. При Process crashed добавить свежий logcat с временем
и процессом `.p1runtime`; чужие системные crashes отделять. Не retrace тестовый стек
mapping целевого APK: ID и SHA256 должны соответствовать своему артефакту.
Все тесты синтетические; реальные пароли и заметки для проверки не нужны.

Проверку P1 выполнить отдельно прежней командой:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
```

У неё по-прежнему7 методов P1BackendRuntimeTest на каждом устройстве.
Тяжёлые KDF benchmarks запускаются только отдельно по [README](README.md).
