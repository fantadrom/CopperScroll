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
   .\gradlew.bat -Pp1ReleaseRuntime=true :app:connectedP1ReleaseAndroidTest --console=plain
   ```

Флаг включает instrumentation именно для `p1Release`. Без него эта connected task
не существует и команда завершится ошибкой, а не подменит проверку debug.
`p1Release` наследует release с включённой обработкой R8 и неизменной областью
`androidx.**`, `kotlin.**`, `kotlinx.**`; storage/ICU/CBOR в неё не входят.
Это проверка работы backend **в APK после этого pipeline**, не доказательство
оптимизации R8 самого storage/ICU/CBOR.

Перед установкой автоматически выполняется `verifyP1ReleaseApks`. Она проверяет
реальные определения классов DEX: backend есть в обработанном target APK, отсутствует
в test APK; pinned ICU resources и независимые fixtures совпадают с эталонами.
Сообщение `artifactAudit=PASS` означает только готовность артефактов.

Устанавливаются два APK:

| Файл | Назначение |
| --- | --- |
| `app/build/outputs/apk/p1Release/app-p1Release.apk` | Target `com.fantadrom.copperscroll.p1runtime`, debuggable=false; production sources/dependencies и R8 scope release |
| `app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk` | Runner `com.fantadrom.copperscroll.p1runtime.test`, нацелен на target выше; backend не включён повторно |

Оба подписываются локальным Android debug/test key; приватного ключа в репозитории
нет. Штатный release signing не меняется. От настоящего release тестовый target
отличается application ID suffix `.p1runtime`, локальной тестовой подписью,
matchingFallbacks=`release` и служебным asset marker.
Только из runtime classpath test APK исключены compiler annotations
`com.google.errorprone:error_prone_annotations`: их CLASS-retention annotation
ссылалась на отсутствующий Android JDK enum и вызывала реальный R8 build failure.
Compile classpath, production dependencies, R8 scope и keep rules не изменены. Debug implementation dependencies
в нём отсутствуют. Отдельный application ID позволяет сохранить обычную установку
Copper Scroll; тесты не требуют открытия приложения, пароля или заметок.
Диагностический UI/скрытый пользовательский вход не добавлен.

В `P1BackendRuntimeTest` должны завершиться все тесты:

- `targetApkLoadsPinnedUnicode16Data`;
- `unicode16CompositionAndEquivalentSecrets`;
- `pinLeadingZerosAndRejectedRepertoire`;
- `unsupportedBackendDoesNotFallBackToSystemNfc`;
- `cborUnknownEnumsAndIndependentGolden`;
- `rejectsNoncanonicalGolden`;
- `all21SyntheticS1GoldenVectors` (21 исходный S1 vector, включая отрицательные).

Перед каждым проверяются package целевого APK, marker, debuggable=false и разрешение
классов через target class loader. Затем вызываются реальные production S1/codecs.
Используются только synthetic vectors. Полный Unicode NormalizationTest на телефоне
не запускается: он уже прошёл на JVM двумя независимыми реализациями.

Признаки успеха: `minifyP1ReleaseWithR8` в сборке (или UP-TO-DATE),
`artifactAudit=PASS`, задача **`:app:connectedP1ReleaseAndroidTest`**, **7 tests passed**, все перечисленные
тесты passed, 0 failed/skipped и **BUILD SUCCESSFUL**. Только этот runtime-результат
закрывает проверку на Honor после R8. Успех assemble/verify без connected task — нет.

Результаты:

- HTML: `app/build/reports/androidTests/connected/p1Release/index.html`;
- XML/логи: `app/build/outputs/androidTest-results/connected/p1Release/`;
- APK audit с SHA256: `app/build/reports/p1-runtime/apk-audit.txt`;
- архитектурный статус: `docs/architecture/validation/p1-report.md`.

При ошибке пришлите название failed task/test, последние строки ошибки из Build/Terminal,
модель/версию Android и папку HTML/XML отчёта (если создана), вместе с `apk-audit.txt`.
Не нужны пароли, содержимое заметок или целый пользовательский контейнер.
Warnings `sun.misc.Unsafe`/`com.google.protobuf` сами по себе не означают отказ: оценивать
exit status и результаты тестов. Не менять JDK/зависимости только для скрытия warnings.

После Honor повторите ту же команду на второй Android-конфигурации API31+
(например, другое физическое устройство или эмулятор с другой версией ОС), оставив
подключённой только её. Повтор на том же Honor не заменяет вторую конфигурацию.
Пришлите результаты обоих прогонов; до них P1 и PR6 остаются в частичном/Draft статусе.
