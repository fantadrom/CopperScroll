# P1 runtime: Android Studio / Windows / Honor Android15

Ветка: `codex/copper-scroll-p1-codecs-parser-s1`, Draft PR6.
Обновите её через Git → Fetch, Checkout этой ветки и Pull. Дождитесь Gradle Sync.
Не изменяйте Gradle JDK, версии зависимостей или исходники. Предыдущий debug run
`StorageDependencyTest.pinnedUnicodeAndCborOnAndroid` на Honor ABR-LX1/Android15
уже зафиксирован отдельно; команда ниже проверяет другой вариант.

1. Подключите Honor через USB, разблокируйте его, подтвердите USB debugging.
   Закройте эмуляторы и отключите другие устройства: Gradle connected task выбирает
   подключённые устройства самостоятельно, выбор в toolbar Android Studio его не ограничивает.
2. Выберите Run configuration **P1 Release Runtime** и Run (не Debug).
   Это сохранённая Gradle-конфигурация из `.run/`, ручные изменения не нужны.
   Если Android Studio ещё не показала её, откройте Terminal в корне проекта и
   выполните одну эквивалентную команду в PowerShell:

   ```powershell
   .\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
   ```

Флаг включает instrumentation именно для `p1Release`. Без него эта connected task
не существует и команда завершится ошибкой, а не подменит проверку debug.
`p1Release` наследует release с включённой обработкой R8 и неизменной областью
`androidx.**`, `kotlin.**`, `kotlinx.**`; storage/ICU/CBOR в неё не входят.
Это проверка работы backend **в APK после этого pipeline**, не доказательство
оптимизации R8 самого storage/ICU/CBOR.

Перед установкой автоматически выполняется `verifyP1ReleaseApks`. Она проверяет
реальные определения классов DEX: backend есть в обработанном target APK, отсутствует
в test APK; общих определений классов нет, superclass/interface с package-private
доступом не вынесен в чужой пакет. Также проверяются фактические DEX-ссылки runner
на классы, методы и поля, включая унаследованные members, доступ и static/instance.
Pinned ICU resources и независимые fixtures совпадают с эталонами.
Сообщение `artifactAudit=PASS` означает только готовность артефактов.

Затем `prepareP1RuntimeRun` удаляет прежние P1 XML/HTML/служебные результаты и итоговый
runtime PASS, записывает новый run ID и список подключённых устройств через SDK adb.
Offline/unauthorized, API ниже31 или отсутствие устройств завершают команду ошибкой.
Если задан `ANDROID_SERIAL`, выбирается это одно устройство; иначе — все подключённые.
Устройства с одинаковыми model/version запускайте последовательно. Не подключайте
новое устройство до окончания команды. Connected task всегда выполняется заново.

Устанавливаются два APK:

| Файл | Назначение |
| --- | --- |
| `app/build/outputs/apk/p1Release/app-p1Release.apk` | Target `com.fantadrom.copperscroll.p1runtime`, debuggable=false; production sources/dependencies и R8 scope release |
| `app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk` | Runner `com.fantadrom.copperscroll.p1runtime.test`, нацелен на target выше; backend не включён повторно |

Оба подписываются локальным Android debug/test key; приватного ключа в репозитории
нет. Штатный release signing не меняется. От настоящего release тестовый target
отличается application ID suffix `.p1runtime`, локальной тестовой подписью,
matchingFallbacks=`release` и служебным asset marker.
Теперь есть дополнительные правила **только для p1Release**: текущий R8 автоматически
сохраняет API target, используемый runner/test classes, с сохранением возможности
обфускации. Отдельно закреплены имена трёх coroutine compiler-интринсиков DebugProbesKt:
проверка DEX обнаружила их некорректный вызов после применения target mapping.
Штатный release этих тестовых keep rules не получает. Поэтому результат относится
к этому release-derived варианту; он не доказывает побайтное тождество обычного release.
Только из runtime classpath test APK исключены compiler annotations
`com.google.errorprone:error_prone_annotations`: их CLASS-retention annotation
ссылалась на отсутствующий Android JDK enum и вызывала реальный R8 build failure.
Исключение не применяется к compile configuration. Production dependencies и R8 scope
не изменены; test classpath соответствует сокращённому набору выше. Debug implementation dependencies
в нём отсутствуют. Отдельный application ID позволяет сохранить обычную установку
Copper Scroll; тесты не требуют открытия приложения, пароля или заметок.
Диагностический UI/скрытый пользовательский вход не добавлен.
Неиспользуемые Compose/Espresso test libraries остаются в обычном debug, но не
включаются в P1 backend suite. AndroidJUnitRunner1.7.0 теперь подключён прямо в той
же версии. Два точных compiler-metadata dontwarn относятся только к исключённым
CLASS-retention Error Prone annotations; отсутствующие runtime API не подавляются.

Исправление подтверждённого startup crash применяется и к настоящему release:
`app/src/main/keepRules/kotlin-package-access.keep` содержит только
`-keeppackagenames kotlin.collections.**`. Оно сохраняет пакет Kotlin collections,
чтобы R8 не разместил подкласс вне пакета его package-private родителя. R8 продолжает
shrinking/optimization/переименование классов и методов; область optimization прежняя.
InitializationProvider и ProcessLifecycleInitializer остаются включёнными.
Нормативный wrapper — Gradle9.6.0; менять его на9.8.0 для этой проверки не требуется.

В `P1BackendRuntimeTest` должны завершиться все тесты:

- `targetApkLoadsPinnedUnicode16Data`;
- `unicode16CompositionAndEquivalentSecrets`;
- `pinLeadingZerosAndRejectedRepertoire`;
- `unsupportedBackendDoesNotFallBackToSystemNfc`;
- `cborUnknownEnumsAndIndependentGolden`;
- `rejectsNoncanonicalGolden`;
- `all21SyntheticS1GoldenVectors` (21 исходный S1 vector, включая отрицательные).

Перед каждым проверяются package целевого APK, marker, debuggable=false и разрешение
классов через target class loader, включая Trace; его три runner API вызываются
с фиксированной синтетической trace label. Затем вызываются реальные production S1/codecs.
Используются только synthetic vectors. Полный Unicode NormalizationTest на телефоне
не запускается: он уже прошёл на JVM двумя независимыми реализациями.

Признаки успеха: `minifyP1ReleaseWithR8` в сборке (или UP-TO-DATE),
`artifactAudit=PASS`, выполнение **`:app:connectedP1ReleaseAndroidTest`**, затем
успешная **`:app:p1ReleaseRuntimeCheck`** и **BUILD SUCCESSFUL**. Итоговый gate печатает:

```text
runtime=PASS
variant=p1Release
runId=<новый ID>
device=<serial> tests=7 failures=0 errors=0 skipped=0
```

Последняя строка должна быть для каждого выбранного устройства. Gate требует ровно
семь перечисленных методов именно `P1BackendRuntimeTest` на каждом устройстве.
Missing/zero/partial XML, любой failure/error/skipped, неизвестный тест, повторный
отчёт и старый XML дают ошибку; вложенные `testsuites/testsuite` не суммируются повторно.
Ошибка runner также прерывает итоговую команду. **Один BUILD SUCCESSFUL от прямой
connected task, особенно с tests="0", не считается PASS.** Пользовательские прежние
release-запуски Honor15/эмулятор17 с нулём тестов не закрывают runtime gate.
Успех assemble/verify или host-тестов самого валидатора тоже не означает Android PASS.
Дополнительно автоматически сверяются свежие машинные отчёты runner:
`test-result.pb` и `test-result-exit-code.txt` в папке XML. Это обязательно, потому что
текущий XML writer не отражает все ошибки runner/пропуски. Каждый завершённый тест
должен иметь PASSED, suite — PASS без platform errors, exit code —0. Эти файлы также
нужно сохранить при ошибке; ручного запуска instrumentation не требуется.

Результаты:

- HTML: `app/build/reports/androidTests/connected/p1Release/index.html`;
- XML/логи: `app/build/outputs/androidTest-results/connected/p1Release/`;
- APK audit с SHA256: `app/build/reports/p1-runtime/apk-audit.txt`;
- готовый архив **обоих** mappings/конфигураций: `app/build/reports/p1-runtime/p1-diagnostics.zip`;
- их связь с SHA256 target/test APK: `app/build/reports/p1-runtime/artifact-manifest.properties`;
- проверенный итог: `app/build/reports/p1-runtime/runtime-result.txt` (создаётся только при PASS);
- текущий run ID/ожидаемые устройства: `app/build/reports/p1-runtime/run.properties`;
- архитектурный статус: `docs/architecture/validation/p1-report.md`.

При ошибке пришлите название failed task/test, последние строки ошибки из Build/Terminal,
модель/версию Android и папку HTML/XML отчёта (если создана), вместе с
`p1-diagnostics.zip`, `apk-audit.txt` и `run.properties` из `reports/p1-runtime/`.
При Process crashed также пришлите журнал именно процесса
`com.fantadrom.copperscroll.p1runtime`, время последнего запуска и map ID из стека.
Сборка автоматически сохраняет mappings **target и test** и проверяет совпадение
их ID с R8 markers соответствующего APK. В ZIP есть SHA256 каждого APK и mapping.
Target mapping нельзя использовать для стека runner с другим ID.
Не смешивайте старые падения и системный uwb-service с последним запуском.
Не нужны пароли, содержимое заметок или целый пользовательский контейнер.
Warnings `sun.misc.Unsafe`/`com.google.protobuf` сами по себе не означают отказ: оценивать
exit status и результаты тестов. Не менять JDK/зависимости только для скрытия warnings.

После Honor повторите ту же команду на второй Android-конфигурации API31+
(например, другое физическое устройство или эмулятор с другой версией ОС), оставив
подключённой только её. **Перед повтором сохраните первую папку XML/логов, HTML и
`reports/p1-runtime/` вне `app/build/`**: следующий запуск удаляет прежние результаты.
Архивы mappings дополнительно сохраняются в `reports/p1-runtime/artifacts/<bundleId>/`;
обычный повтор их не удаляет, но Clean Project или удаление build удалят и их.
Run manifest и итоговый runtime summary содержат bundle ID и SHA обоих APK;
изменение этой идентичности во время прогона завершает команду ошибкой.
Повтор на том же Honor не заменяет вторую конфигурацию.
Пришлите результаты обоих прогонов; до них P1 и PR6 остаются в частичном/Draft статусе.
