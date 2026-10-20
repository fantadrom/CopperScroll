# P3 Release Runtime — Android Studio / Windows

1. Открыть проект, выполнить **Git → Fetch**. В меню веток выбрать
   `origin/codex/copper-scroll-p3-transactions` → **Checkout** (при первом запуске
   создать одноимённую локальную ветку), затем Pull и Gradle Sync.
   Ничего вручную в Gradle/исходниках/keep rules менять не нужно.
   Нормативный wrapper —9.6.0. Если локально сохранён9.8.0, указать это вместе с результатом.
2. Подключить Honor ABR-LX1 / Android15 с USB debugging и подтверждённым RSA grant.
   Запустить эмулятор Android17. Можно проверять оба одновременно; offline/unauthorized
   devices приводят к отказу. `ANDROID_SERIAL`, если задан, выбирает одно устройство:
   для двух убрать прежнее значение из Terminal либо запустить по одному.
3. Выбрать **P3 Release Runtime** в списке Run configurations и Run либо выполнить
   из корня проекта в Terminal Android Studio одну команду:

```powershell
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
```

Устанавливаются `app/build/outputs/apk/p1Release/app-p1Release.apk`
(`com.fantadrom.copperscroll.p1runtime`, non-debuggable target после R8) и
`app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`
(`com.fantadrom.copperscroll.p1runtime.test`, собственная обработка R8).
Это тот же release-derived путь P1/P2: production backend в target APK, второй
копии в test APK нет. Общая локальная test подпись, marker/suffix и дополнительные
instrumentation ABI rules отличают его от обычного release. Production release
signing не изменяется. R8 scope прежний: androidx/kotlin/kotlinx; storage/ICU/CBOR/BC
вне optimization scope. Startup/lifecycle остаются включены.

Должны завершиться ровно8 методов `P3TransactionRuntimeTest` **на каждом** устройстве:

1. `createAddMutateReopen`
2. `commitUnknownReconciliationAndRevision`
3. `recoveryIsolationAndNoPasswordFallback`
4. `lazyVerificationAndUnsupportedSchema`
5. `ownerRevokeAndClosingSave`
6. `namespaceAndLocalStateDurability`
7. `catalogueSplitsAndLimits`
8. `boundedStoreAndConflict`

Последний включает короткий FileChannel smoke в cache целевого приложения;
остальные исполняют настоящий core/crypto на синтетическом fault-store. Длительная
JVM fault enumeration, subprocess tests и device KDF benchmark сюда не включены.
Эти проверки не подтверждают Android SAF, namespace/power-loss или production durability.

Успех: итоговая команда `p3ReleaseRuntimeCheck` завершилась BUILD SUCCESSFUL,
а `app/build/reports/p3-runtime/runtime-result.txt` содержит:

```text
runtime=PASS
variant=p1Release
suite=com.fantadrom.copperscroll.storage.runtime.P3TransactionRuntimeTest
...
device=... tests=8 failures=0 errors=0 skipped=0
device=... tests=8 failures=0 errors=0 skipped=0
```

runId должен быть новым; APK SHA256/map IDs/bundle записываются автоматически.
Ноль/partial/skipped/runner crash/старые XML дают отказ итогового gate. Один
BUILD SUCCESSFUL connected task не считается успешным runtime. Старые Android
reports удаляются перед запуском; до следующей команды сохраните нужные receipts.

При успехе достаточно прислать **`p3-runtime/runtime-result.txt`**, модель/API и
использованную версию Gradle. При ошибке нужны полный вывод итоговой команды,
HTML `app/build/reports/androidTests/connected/p1Release/`, XML/protobuf/runner logs
из `app/build/outputs/androidTest-results/connected/p1Release/`, `run.properties`,
`apk-audit.txt` и `app/build/reports/p3-runtime/p1-diagnostics.zip`.
Сохранённое имя ZIP общее с P1: внутри оба mappings, configurations, generated
rules и связь с SHA256; immutable копия находится в `artifacts/<bundleId>/`.
При Process crashed добавить свежий logcat с временем и процессом `.p1runtime`.
Не использовать target mapping для достоверного retrace test APK или смешивать
системные crashes с падением приложения. Все данные синтетические.

P1/P2 regression, отдельными командами без P3-флага:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
```

Их критерии остаются7 P1 и8 P2 PASS на каждом выбранном устройстве.
