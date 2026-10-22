# P5b Release Runtime — Windows / Android Studio

Ветка `codex/copper-scroll-p5b-change-secret`, отдельный Draft PR. Fetch/Pull,
Gradle Sync. Wrapper репозитория — Gradle9.6.0 с checksum; AGP9.4.1, Kotlin2.2.10,
SDK37/min31/Java target11 прежние. Не редактировать wrapper/Gradle/исходники ради запуска.
При локальных изменениях receipt честно записывает фактические bytes/dirty/wrapper,
версии Gradle/JVM; изменившиеся во время build/run исходники отклоняются.

1. Подключить Honor ABR-LX1 с Android15, USB debugging, подтвердить RSA.
2. Запустить ранее использованный эмулятор Android17 (API31+). Оба должны быть online;
   проверить Device Manager. Убрать лишние устройства. Не задавать ANDROID_SERIAL,
   если требуется проверить оба сразу; переменная ограничивает набор устройств.
3. В Android Studio выбрать **P5b Release Runtime**, Run. Эквивалент из Terminal
   в корне проекта (PowerShell или cmd):

```powershell
.\gradlew.bat -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --console=plain
```

Команда сама выполняет host gates/source identity, build, APK audit, удаляет старые
результаты, фиксирует online устройства, устанавливает target
`app/build/outputs/apk/p1Release/app-p1Release.apk` и instrumentation
`app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`.
Target package — `com.fantadrom.copperscroll.p1runtime`, test package — с `.test`.
Это non-debuggable **p1Release**, обработанный R8, с локальной тестовой подписью.
Обычное приложение/debug не подменяет target; тестовые helpers находятся только
в instrumentation APK, production coordinator/rekey/reader — в target APK.
Постоянных диагностических экранов/пользовательских входов не добавлено.

В `P5bChangeSecretRuntimeTest` должны завершиться ровно **8 методов на каждом устройстве**:

- `mainOnlyAndCompatibility`
- `mainRekeyPreservesDecoy`
- `decoyRekeyPreservesMain`
- `credentialsAndNoArtifacts`
- `oldKeyCannotReadRekeyOrFutureTransactions`
- `revokeSwitchAndLease`
- `restartWithDistinctCredentialContexts`
- `preferredInputAndRepeatedChange`

Нужны итоговый **BUILD SUCCESSFUL**, `runtime=PASS`, `variant=p1Release` и две строки
`device=… tests=8 failures=0 errors=0 skipped=0`. Connected BUILD SUCCESSFUL с нулём
тестов не PASS: итоговый gate проверяет точный набор XML/protobuf/exit code/freshness.
Новый запуск очищает старые отчёты, поэтому сохраните receipt перед следующей командой.
Полный Unicode conformance и KDF benchmarks в этот запуск не входят.

Успешный компактный receipt для передачи:
`app/build/reports/p5b-runtime/runtime-result.txt`.
Он связывает новый runId, оба APK SHA256/mapping IDs, revision/fingerprint,
sourceCodeDirty/workingTreeDirty/wrapperModified, Gradle/JVM и serial устройств.
HTML: `app/build/reports/androidTests/connected/p1Release/index.html`;
XML/protobuf: `app/build/outputs/androidTest-results/connected/p1Release/`.

При ошибке прислать полный вывод Terminal **итоговой команды**, а также
`app/build/reports/p5b-runtime/p1-diagnostics.zip` (имя общее для существующего
инструмента P1–P5b) и, если созданы, `run.properties`, `runtime-result.txt`,
`build-identity.properties`, свежие XML/protobuf/exit-code из указанного каталога.
Если падение процесса: добавить logcat именно последнего запуска, отдельно от
старых/system crashes. В ZIP обе точные mappings и manifest с привязкой к APK SHA;
не использовать mapping другой сборки для достоверного retrace. До APK audit
ZIP может ещё не появиться — тогда нужен лог сборки, не старый ZIP.
Тесты используют только synthetic данные; реальные контейнеры/секреты не присылать.

Регрессии P1–P5a после этой реализации запустить **отдельными командами**, с теми же
двумя online устройствами. Сохранить каждый receipt перед следующей командой:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
```

Ожидаются соответственно **7,8,8,10,8 PASS на каждом устройстве**, ноль
failures/errors/skipped, итоговый BUILD SUCCESSFUL. Receipts:
`app/build/reports/p1-runtime/runtime-result.txt` … `p5a-runtime/runtime-result.txt`.
Не включать несколько profiles одновременно. Для диагностики каждый profile
имеет свой `p1-diagnostics.zip` в соответствующем report directory.

Границы результата: R8 scope остаётся androidx/kotlin/kotlinx; storage/ICU/CBOR/crypto
вне оптимизируемой области. p1Release наследует release, но отличается suffix,
локальной подписью и сохранённым ABI для instrumentation/компиляторных intrinsics.
Runtime подтверждает эту обработанную APK-пару, не идентичность/исполнение обычного
release. Synthetic IO на Android не подтверждает SAF/provider/power-loss/lifecycle/OEM
гарантии. Production SAF, power-loss, lifecycle и рабочий UI остаются P6. До пользовательского PASS
P5b и регрессий на двух устройствах PR остаётся Draft; merge агент не выполняет.


P5b использует только synthetic данные. Успех означает полное rekey цели с новым
ZDK и durable switch в модели. Retained source/backup сохраняют прежнюю защиту;
физическое стирание, отзыв прежнего plaintext, production SAF/power-loss/OEM
этим прогоном не подтверждаются. Старые receipts P5a не подтверждают P5b.
