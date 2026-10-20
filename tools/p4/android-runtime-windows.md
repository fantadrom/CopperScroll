# P4 Release Runtime — Windows / Android Studio

1. В Android Studio выполнить Git → Fetch, выбрать
   `origin/codex/copper-scroll-p4-vfs-cards` → Checkout, затем Pull и Gradle Sync.
   Ветка создана от master с merged PR №8. Ничего вручную в исходниках,
   Gradle или keep rules менять не нужно. Нормативный wrapper9.6.0 с SHA256
   сохранён; локальный9.8.0 не требуется. Фактические Gradle/JVM/source/wrapper
   identities теперь записываются автоматически.
2. Подключить Honor ABR-LX1 / Android15 (USB debugging, подтверждённый RSA grant),
   запустить эмулятор Android17. Для одновременного запуска на обоих убрать
   ранее заданный `ANDROID_SERIAL`; если он задан, проверяется только выбранный
   serial. Offline/unauthorized devices приводят к отказу. Допустимы отдельные
   запуски, но надо сохранить receipt каждого перед следующим запуском.
3. Выбрать **P4 Release Runtime** → Run либо открыть Terminal в корне проекта
   и выполнить одну команду:

```powershell
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
```

Команда собирает/аудирует и устанавливает два APK:

- `app/build/outputs/apk/p1Release/app-p1Release.apk` — target
  `com.fantadrom.copperscroll.p1runtime`, non-debuggable **p1Release после R8**;
- `app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk` —
  `com.fantadrom.copperscroll.p1runtime.test`, instrumentation после собственного R8.

Реальный production backend находится только в target. Instrumentation использует
его реальные классы; второй storage backend, production fixtures или скрытый
диагностический вход в приложение не добавляются. Данные синтетические.
Startup/lifecycle сохранены. Подпись локальная тестовая, общая для обоих APK;
штатная release-подпись не меняется. Это release-derived test target, отличающийся
от обычного release suffix/marker/signing и дополнительными instrumentation ABI/
intrinsics rules. Optimization scope прежний `androidx.** kotlin.** kotlinx.**`;
storage/ICU/CBOR/BC вне него. PASS не подтверждает byte identity обычного release,
Android SAF или физическую power-loss durability.

На **каждом** выбранном устройстве ожидаются ровно10 методов
`com.fantadrom.copperscroll.storage.runtime.P4VfsCardRuntimeTest`:

1. `vfsBatchDepthTrash`
2. `noteRichTextRevision`
3. `cardFieldRoundTripAndGolden`
4. `cardAtomicSaveAndDraft`
5. `masksDatesAndConstraints`
6. `templatesAndGlobalMessagePolicy`
7. `mediaStreamingAndCow`
8. `faultCommitUnknownAndRevoke`
9. `compatibilityRecoveryAndBudgets`
10. `sortingSettingsAndZoneIsolation`

Тяжёлые JVM fault/random/stress и KDF benchmark сюда не входят. Синтетическое
streaming media в runtime — около2MiB. Полный Unicode conformance не повторяется.

Успех: итоговая **p4ReleaseRuntimeCheck** завершилась `BUILD SUCCESSFUL`, а файл
`app/build/reports/p4-runtime/runtime-result.txt` содержит новый runId и:

```text
runtime=PASS
variant=p1Release
suite=com.fantadrom.copperscroll.storage.runtime.P4VfsCardRuntimeTest
...
device=... tests=10 failures=0 errors=0 skipped=0
device=... tests=10 failures=0 errors=0 skipped=0
```

Там же автоматически записываются actual Gradle/JVM versions, sourceRevision,
sourceFingerprint, dirty/wrapper flags, wrapper SHA256, APK SHA256/map IDs и
artifactBundleId. Не редактировать исходники/wrapper и не переключать ветку
во время сборки/прогона. Источник фиксируется до build и повторно проверяется
до install и после run. Старые connected XML удаляются; zero/partial/skipped/
crash/stale/missing protobuf/runner exit/identity mismatch дают отказ gate.
Один `BUILD SUCCESSFUL` задачи connectedAndroidTest не заменяет этот результат.

При успехе прислать **runtime-result.txt**, модель/API двух устройств.
Версии Gradle/JVM по памяти сообщать не нужно. До следующей suite сохранить receipt:
общий connected HTML/XML перезаписывается, p4 compact receipt хранится отдельно.

При ошибке прислать полный вывод команды и
`app/build/reports/p4-runtime/p1-diagnostics.zip`, `apk-audit.txt`,
`build-identity.properties`, `run.properties`, если они созданы;
HTML из `app/build/reports/androidTests/connected/p1Release/` и XML/protobuf/
runner logs из `app/build/outputs/androidTest-results/connected/p1Release/`.
ZIP сохраняет оба mappings/configurations/generated rules и их SHA256 связь с
APK; immutable копия находится в `p4-runtime/artifacts/<artifactBundleId>/`.
Если процесс упал — добавить свежий logcat с временем/процессом `.p1runtime`.
Mapping target и test различаются; для retrace нужен соответствующий ID/SHA.
Системные crashes не смешивать с падением приложения. Не присылать реальные
контейнеры/секреты: этот профиль работает только с synthetic fixtures.

Регрессии запускаются отдельно, **без P4-флага**:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
```

Ожидание —7/8/8 PASS соответственно на каждом устройстве. P3 receipt пользователя
уже принят отдельно; он не заменяет новый P4 runtime. До подтверждения P4 на Honor
и эмуляторе статус P4 остаётся частичным/Draft.
