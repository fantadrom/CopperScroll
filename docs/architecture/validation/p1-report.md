# P1 — codecs, строгий parser и S1

Дата отчёта: 19 октября 2026 (Asia/Bishkek). Исходная база master:
`42c7bb262b0f1ae5e5281af774fd68f0b43c6758` (PR5 слит; согласование
`e2b59ab67f29ca4e49bcf902adf92f9929d63b6c` включено). Повторный read master перед
публикацией подтвердил тот же SHA. Ветка: `codex/copper-scroll-p1-codecs-parser-s1`.
Нормативная основа: согласованные design/format/test/prototype/resolution1.1,
reconciliation-materials и текущий `editor/NoteDocument.kt`.

**Статус: P1 частично выполнен; после исправления Kotlin access runner упал на Trace.
Подготовлено согласование target/test ABI; нужны семь подтверждённых PASS после R8
на Honor и второй Android-конфигурации.**
Независимые Unicode conformance и normative-byte проверки выполнены. Сборка Android
и упаковка ресурсов проверяются отдельно от исполнения: Android runner здесь
отсутствует. Пользователь подтвердил debug instrumentation на Honor/Android15;
эта отдельная категория evidence описана ниже. P1 не создаёт защищённый пользовательский контейнер.

## Реализация

Core находится в `app/src/main/java/com/fantadrom/copperscroll/storage/format`,
S1 — в `storage/access`; Android/Compose/View/URI/ContentResolver зависимостей нет.
Работающий UI и модель редактора не подключены к новому слою. Сохранены SDK37,
minSdk31, Gradle9.6.0, AGP9.4.1, Kotlin2.2.10 и исходная область release optimization.
После подтверждённого startup crash добавлено узкое правило сохранения пакета
Kotlin collections; диагностика и влияние на настоящий release приведены в конце отчёта.
В production добавлены только две точно закреплённые зависимости. Текущий test runner
закреплён отдельно в уже использовавшейся версии; детали нового test-only ABI ниже.

- Codecs superblock1.1, descriptor18 и отдельно его точных logicalBytes0…15;
  PhysicalRef/LogicalRef, record header76 и bounded opaque ciphertext/tag;
  catalogue leaf/branch pages16384B.
- Самостоятельные plaintext codecs root, objects/children indexes, folder/note/media
  objects, settings, note paragraphs/todo/styles и media chunk-list pages.
  Успех их разбора **не** означает AEAD authentication. Дополнительные проверки
  note chunk lengths/media lengths принимают уже предоставленные caller chunks;
  получения или аутентификации plaintext здесь нет.
- Folder payload строго CBOR null; восемь settings fields обязательны. Unknown
  uint32 sort/view и допустимый note icon сохраняются raw; display fallback вычисляется
  отдельно. Fixed folder/media icons, pinned/null relationship и title/name проверяются.
  Rich text сохраняет combining marks без NFC, trailing paragraphs, todo tri-state,
  исходный порядок overlapping styles и UTF16 code-unit offsets, включая границу
  внутри surrogate pair. Persisted cursor не переписывается; clamp — отдельный метод.
- Два header slots выбираются по checksum/generation. Checksum-valid unknown framing
  блокирует fallback, равные generation требуют идентичных bytes, container/instance
  должны совпасть. Сначала выбирается slot, потом проверяется его descriptor/catalogue;
  ошибка нового root не переключает reader на старый slot. Это структурный выбор,
  не crash recovery или доказательство долговечности.
- Read-only inspector следует только проверенным refs, проверяет выбранный полный
  публичный граф обеих зон: hashes, zone/record/kind/length, root locator, levels,
  child max keys, глобальный порядок keys, cycles/aliases и пересечения records/pages.
  У других header slots проверены checksum и общий supported framing; их descriptor
  и graphs не объявляются проверенными. Структурная сводка называет именно эти свойства.

## Строгость и ресурсы

`co.nstant.in:cbor:0.9` используется за строгим framing/schema adapter. До библиотечной
десериализации сканируются фактические bytes: только definite arrays/bytes/text,
shortest uint/negative integers, true/false/null, strict UTF8, exact payload consumption;
maps/tags/floats/indefinite/прочие simple values запрещены. До encode также проверяются
размер модели/строк, nodes/depth и byte budget. Формат ради библиотеки не менялся.

До read/allocation/Long→Int проверяются fixed sizes, magic, supported versions/features,
reserved/padding, bounded committedLength, unsigned widths, checked ranges, alignment,
page fanout/height, nesting≤16, per-schema counts и budgets. File limit1TiB проверяется
до header reads. Недопустимы переполнение offset+length и uint64→negative Long.
Универсальный CBOR uint64 сохраняется ULong; signed dates поддерживают весь Int64.

Ограничения сканера:≤1,100,010 nodes, array≤100,000, консервативная оценка allocations
≤128MiB до object tree; metadata≤16KiB, note body≤16MiB/text≤8MiB. Максимальные
100,000 paragraphs +100,000 styles проходят отдельно; превышение counts отклоняется.
Это не обещание точного пикового RSS: generic CBOR tree и schema model кратковременно
сосуществуют; Android heap поведение остаётся частью runtime gate.

Inspector: map accounting≤32MiB (256B/range), один bounded record buffer≤1MiB+92B,
page16KiB, recursive height≤8;≤16,777,216 records/zone, по умолчанию≤1GiB cumulative
reads и30s elapsed. Budget можно только уменьшить для map/count; cancel проверяется
перед read/на каждой странице. При исчерпании — ResourceLimit без обхода границ.
Нет plaintext scratch или выделения declared whole-file size. Old unreachable gaps
не сканируются и не интерпретируются как padding/records. Вход инспектора — статический
synthetic snapshot; согласованный snapshot изменяемого SAF-файла относится к P3.

Ошибки содержат только стабильный код, без вложенных library exceptions или входных
строк. Models не являются data classes с автоматической печатью заметок/секретов.
Bytes печатают только длину; CanonicalSecret — redacted, close затирает owned byte array,
закрытый экземпляр не отдаёт bytes. Это не обещание полного стирания immutable String
и всех VM copies; управление сессией/ключами относится к P2/P3.

## S1, зависимости и независимость

| Компонент | Закрепление / происхождение | Лицензия / результат |
| --- | --- | --- |
| CBOR | `co.nstant.in:cbor:0.9`, Maven Central; upstream c-rack/cbor-java, POM/source JAR проверены | Apache2.0; без runtime transitives; Java7 bytecode; strict adapter обязателен |
| Unicode backend | `com.ibm.icu:icu4j:76.1`, Maven Central / unicode-org/icu release-76-1 | Unicode License V3 и включённые third-party notices сохранены; без runtime transitives |
| Независимый reference | `unicodedata2==16.0.0`, PyPI/fonttools; CPython-derived C normalization + generated UCD16 | Apache2.0 по LICENSE wheel/sdist; UCD license сохранена отдельно, reference не распространяется в APK |
| Полный corpus | Unicode NormalizationTest16.0.0, официальный unicode-org/unicodetools snapshot `4fd8eca90b30aa4c72543ccddb4c15d05becdf8d` | Unicode data license сохранена; deterministic gzip в test resources |

SHA256 артефактов, sdist/wheel, corpus и URLs: [provenance.json](../../../tools/p1/provenance.json).
Полные licenses/notices: [licenses](../../../tools/p1/licenses/); app assets включают
ICU/CBOR license и NOTICE. Проверены POM и опубликованные источники; это проверка
происхождения/совместимости, не отдельный аудит безопасности всего upstream.

Runtime capability gate проверяет `UCharacter.getUnicodeVersion()==16.0.0.0` и
Unicode16-specific composition U+11382 U+113C9→U+11383. При отсутствии/несовпадении
backend text возвращает UnsupportedSecretEncoding; OS normalization fallback нет.
White_Space для folder names также берётся из закреплённых таблиц ICU16.

S1: repertoire GC≠Cn/Cs до и после готовой NFC, NUL/CR/LF forbidden, Co allowed,
raw≤1024 code points/4096B, normalized1…256/≤1024B; strict UTF16/UTF8, без trim,
case-fold/NFKC. ASCII PIN4/6 сохраняют нули; pattern сначала игнорирует выбранный
endpoint, потом добавляет midpoint, результат4…9 unique points. Повторные hits не
меняют принятую последовательность. Одинаковые bytes разных input methods равны
через готовый `MessageDigest.isEqual`.

Полный corpus проверен **двумя разными алгоритмами/наборами данных16**: ICU4J76.1
и unicodedata2 C code, без ICU linkage в reference. Не две обёртки одного backend.
В каждом:19,965 rows,399,300 assertions всех NFC/NFD/NFKC/NFKD invariants;
4,379,916 Part1-exclusion identity assertions по остальным Unicode scalar values.
Дополнительно ICU проверяет каждую source row через S1 NFC path (без repertoire/length
ограничений для conformance). Все результаты PASS.

Raw corpus SHA256: `d811971453e7075e1ad56fb1b301eece5aa80757b81f6156e74a1bfb3ae5ceb1`.
Сравнение с официальным ICU76.1 corpus подтвердило все19,971 non-comment lines,
включая section markers; file SHA различается из-за сокращённых комментариев.
Оба runner проверяют raw SHA и data version. System Python имеет UCD15.0.0,
не выполняет указанную Unicode16 composition и **не** использован как normalizer.
21 исходный S1 vector исполнен ICU и независимым reference, включая malformed UTF16.

## Fixtures и проверки

[Инструкции и инструменты](../../../tools/p1/README.md).
Frozen golden bytes написаны независимым Python stdlib author (`struct`, `hashlib`,
fixture-only CBOR); production encoder не использован для expected bytes. JSON задаёт
ожидаемые поля, hex/binary загружаются JVM tests. Включены superblock/descriptor,
logicalBytes, refs, record/header, catalogue leaf/branch, root/index/chunk lists,
folder null, settings defaults/unknown enums, unknown note icon, media и rich note.
Fixture компоненты самостоятельны; они не изображают аутентифицированный plaintext graph.
Непрозрачный ciphertext/tag в synthetic files произвольный, для настоящего AES-GCM
невалиден. Одно-/двухзонные файлы имеют структурно согласованные публичные refs/hashes.

Есть fixed negative TSV и binary graphs: null vs [], truncation, noncanonical CBOR,
unsupported versions, u64/u32 overflow, misalignment, malformed UTF8, cycles/overlap.
Дополнительно mutations проверяют fanout/height/nesting, IDs/owners/kinds/ordinals,
reserved/padding, equal-generation conflict, unknown newer-slot fallback и bad locator/hash.
Существующие6 wire fixtures проверены старым независимым inspector и новым codec;
21 S1 fixture перенесён без изменения expected bytes. `fixtures.py --check` проверяет28
frozen files; полная Unicode gzip проверяется отдельным SHA gate.

Воспроизводимые seeds: `0x43535031` —10,000 malformed/random CBOR inputs;
`0x53315031` —2,000 valid settings/note models и2,000 public page/record mutations.
Разрешены mutations, оставшиеся валидными; unchecked exceptions, OOM и выход за timeout
проваливают тест. Random tests timeout20s, max-count test30s, Unicode test120s.
JVM unit worker default max heap512MiB. Все проверки завершились без OOM/зависания.
Во время разработки исправлены ошибки Kotlin interop с CBOR Array/ICU category types,
а также возможность сужения kind/level до byte до проверки encode bounds.
При финальном review также запрещены пустые branch pages во всех трёх деревьях;
для будущего media display name сохранён общий metadata budget без переноса
неуказанного в спецификации folder-name limit1024B. Wire/product specification не изменена.

## Выполненные команды

Среда: Linux x86_64, JDK25.0.4.1; проект по-прежнему target Java11.
Локальные пути среды для повторения (не часть Gradle settings):

```bash
export JAVA_HOME=/workspace/tooling/jdk-25.0.4.1
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_USER_HOME=/workspace/.android
export GRADLE_USER_HOME=/workspace/.gradle
python3 tools/p1/fixtures.py --check
PYTHONPATH=/workspace/work/p1-unicode-reference python3 tools/p1/unicode_reference.py
PYTHONPATH=/workspace/work/p1-unicode-reference python3 docs/architecture/reconciliation-materials/check_fixtures.py --adapter python3 tools/p1/s1_reference.py
python3 docs/architecture/reconciliation-materials/validate_docs.py
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain
bash tools/p1/inspect.sh app/src/test/resources/p1/synthetic.cscroll
bash tools/p1/inspect.sh app/src/test/resources/p1/two-zones.cscroll
bash tools/p1/inspect.sh app/src/test/resources/p1/cycle.cscroll
```

Чистый последовательный прогон: **BUILD SUCCESSFUL**,124 tasks,3m24s.
После финального review локальных bounds повторены те же обязательные задачи:
**BUILD SUCCESSFUL**,124 tasks,1m39s (22 executed,102 up-to-date). После добавления
последнего media-name assertion отдельно выполнен `:app:testDebugUnitTest --offline`:
**BUILD SUCCESSFUL**,24 tasks,8s. Unit totals:35 tests (12 новых P1),
0 failures/errors/skips. Lint:0 errors,15 warnings и1 hint, все locations относятся
к прежним UI/resources/manifest; storage warnings нет.

| Артефакт / проверка | Результат |
| --- | --- |
| debug APK | Сборка PASS;3,950 ICU data files, NFC resources и3 license/NOTICE assets сохранены |
| release unsigned APK / R8 | Сборка PASS, прежний optimization scope; те же data/notices присутствуют |
| Android test APK | Сборка PASS, execution не выполнена |
| CLI synthetic | exit0; generation5, length180415,1 zone,3 pages,2 records |
| CLI two zones | exit0; length164031,2 zones,2 pages,2 records |
| CLI cycle / overlap | exit1 CorruptData; нет вывода record contents/hints |

CLI javac/java и `inspect.sh` wrapper выполнены. SHA256 входов до/после проверены,
ни один fixture не изменён. Сводка явно содержит cryptographicVerification=NONE и
durabilityVerification=NONE; cumulative bytesRead может превышать file length из-за
повторного чтения record header, это не размер сохранённых данных. Первоначальный `./gradlew --version` требовал запуска через bash
из-за исходного non-executable wrapper; wrapper и его настройки не изменялись.
AGP по умолчанию пытался создать недоступный `/home/agent/.android`; ANDROID_USER_HOME
позволил собрать приложение. Не влияющее на результат предупреждение lint analytics
об этом каталоге сохранялось. Один перекрывшийся повторный запуск дал incremental
asset merger duplicate resources; оба запуска остановлены, только `app/build` очищен,
проверки повторены последовательно. Не было изменения исходных assets для обхода ошибки.
В managed sandbox Gradle требовал разрешения локальных сетевых сокетов даже с --offline;
без него старт завершался Could not determine a usable wildcard IP. Зависимости
финального прогона загружались из уже заполненного cache. Upstream licenses сохранены
побайтно, включая исходные blank lines at EOF; diff whitespace проверен отдельно от
этих vendored license files.

## Матрица и оставшиеся gates

| Test ID | Выполненная P1 часть | Не объявляется проверенным |
| --- | --- | --- |
| SCHEMA-01 | Независимые fixed bytes + Kotlin decode/encode, folder f6, settings mandatory fields/defaults/types | Полный encrypted create/open |
| SCHEMA-03 | Raw unknown enum/icon round-trip, отдельный display fallback, fixed icons rejected | UI selection, copy/rekey сохранение в настоящем файле |
| FILE-04 | Strict1.1/feature/schema отказ, newer-slot unknown не скрывается старым | Writer lockout, read-only UI sessions, selected/unselected maintenance |
| FILE-05 | Bounded parser/public graph, overflow/overlap/cycles/fanout/depth/nesting, fixed-seed malformed tests | FD/SAF races и полный authenticated object graph |
| S1-01 |21 synthetic vectors двумя независимыми реализациями | Два Android устройства, create/open |
| S1-02 | Полный официальный conformance обоих backend; raw/normalized/repertoire/malformed limits | Повторение pinned-library path на Android |
| S1-03 | Runtime data-version/probe gate; unit simulation версии15/missing backend; отсутствие OS fallback | Android capability failures при packaging/runtime incompatibility |
| S1-04 | Cross-method equality, leading zeros, whitespace/case, private-use, unassigned/newer-than16 и midpoint order | Равенство результата на старом/новом Android устройстве |

Android runner отсутствует: нет emulator executable или system image; installed SDK
содержит platforms/build-tools/platform-tools. `adb devices -l` завершился до discovery:
`adb_utils.cpp: Cannot mkdir '/home/agent/.android': Read-only file system`;
ANDROID_USER_HOME/ANDROID_SDK_HOME этот adb binary не перенаправили. Поэтому список
подключённых устройств не установлен, API31+ instrumentation **не выполнена агентом**.
`StorageDependencyTest` добавлен и test APK собран. Позже пользователь подтвердил
его фактический debug-запуск; это не собственный запуск агента и не release runtime.

Release/R8 build и наличие ICU data/notices в APK проверены. S1/CBOR runtime **после
R8 на Android не выполнен**. Новый storage слой пока недостижим из UI; исходный optimization packageScope сохранён.
Сборка и наличие resources сами по себе не доказывают выполнение backend calls после R8.
Перед приёмкой P1 требуются полный runtime suite API31+, two-device S1 matrix и runtime smoke
release/R8 с реальными вызовами storage backend. Новые keep rules и ослабление R8
для получения формального PASS на исходном этапе не добавлялись. Последующее
обоснованное исправление package access после реального crash описано ниже.

P2 может использовать проверенные модели/bytes и S1 для отдельной crypto разработки,
сохраняя указанный gate открытым. P1 полностью принятым и пользовательским хранилищем
не объявлен. Argon2/encryption/key management, authentication, transactional writer,
recovery/durability, SAF, lifecycle и UI integration не реализованы и не проверены.


## Дополнение: подготовка release runtime и результат пользователя (19 октября 2026)

Перед изменениями актуальная ветка PR6 подтверждена через GitHub API:
`codex/copper-scroll-p1-codecs-parser-s1` = `7ec26766befbc6613a33ec48a5d9d9ed4e161de5`.
PR open/Draft, без merge. Ниже отделены новые evidence от исходного JVM отчёта.

| Категория evidence | Статус / источник |
| --- | --- |
| JVM + независимый Unicode16 conformance | PASS агента в исходном P1: ICU4J76.1 и unicodedata2, полный официальный corpus;35 unit tests |
| Debug instrumentation на Honor | **PASS пользователя**: физический Honor ABR-LX1, Android15; по описанию скриншота `:app:connectedDebugAndroidTest`, `pinnedUnicodeAndCborOnAndroid`,1 passed / BUILD SUCCESSFUL |
| Сборка release/R8 и release-derived instrumentation | Подготовка и проверки артефактов агента; результаты команд ниже, это не исполнение на устройстве |
| Runtime после R8 на Honor | **НЕ PASS**: позднейший пользовательский запуск дал XML tests="0"; требуется повтор после исправления |
| Вторая Android-конфигурация API31+ | **НЕ PASS**: эмулятор Android17 также дал tests="0"; успешная семитестовая матрица не выполнена |

Пользовательский debug run не обозначается release-проверкой или прогоном нового
семитестового suite. Warnings `sun.misc.Unsafe` / `com.google.protobuf` не помешали
его успеху; JDK и версии зависимостей ради них не менялись. Полный Unicode conformance
повторять на телефоне не требуется.

### Выбранная конфигурация и предел результата

Отдельный build type `p1Release` делает `initWith(release)`; AGP9.4.1 выполняет
`minifyP1ReleaseWithR8`. Исходные `optimization.enable=true` и packageScope
`androidx.**`, `kotlin.**`, `kotlinx.**` сохранены. **Storage `com.fantadrom...`,
ICU `com.ibm.icu...` и CBOR `co.nstant.in...` не входят в область optimization.**
Runtime PASS подтвердит их работу из APK после существующей обработки, но не
прохождение их собственного кода через optimization. Область не расширялась,
на шаге подготовки keep/dontwarn rules не добавлялись, R8 не отключался (включая test APK).
Текущая версия добавляет только обоснованное `keeppackagenames` после выявленного crash;
область optimization остаётся прежней.

Production sources, SDK/minSdk/Gradle/AGP/Kotlin, common dependencies и release
поведение не менялись. Отличия installable test target от настоящего release:
application ID suffix `.p1runtime`; локальная Android debug/test signingConfig;
matchingFallbacks=`release`; небольшой asset marker `p1-runtime-target.txt`.
Штатная release-подпись не изменена. Ключ создаётся/хранится обычными Android tools
локально, не коммитится. `debugImplementation` в target не подмешивается.
Нового diagnostic UI или скрытого пользовательского входа нет.

Первый instrumentation R8 build выявил реальную ошибку, отличную от предупреждений
пользователя: `IncompatibleModifiers` из `error_prone_annotations` ссылается на
`javax.lang.model.element.Modifier`, отсутствующий в Android. Проверен bytecode:
annotation имеет retention CLASS, target ANNOTATION_TYPE и compiler-only API.
Артефакт исключён **только** из `p1ReleaseAndroidTestRuntimeClasspath`, остаётся
на compile classpath. Production dependencies/версии и обычный debug classpath
не изменены. Предложенный R8 `-dontwarn` не применялся.

Флаг `-Pp1ReleaseRuntime=true` выбирает `testBuildType=p1Release` и класс
`com.fantadrom.copperscroll.storage.runtime.P1BackendRuntimeTest`; без него
default debug instrumentation остаётся обычной, P1 connected task отсутствует.
Перед каждым тестом проверяются expected variant/package, non-debuggable target,
marker из его assets и тождественность S1/codecs/ICU/CBOR classes из target loader.
Тесты напрямую вызывают production API; нормализатор или CBOR не переписаны в suite.
`textUsing(...,null)` вызывает существующий production UnsupportedSecretEncoding path,
не меняя реальный pinned backend, и проверяет отсутствие system NFC fallback.

Suite: загрузка UCD16/NFC resources; Unicode16 composition и composed/decomposed equality;
PIN4/PIN6 leading zeros; repertoire reject; отсутствие fallback; unknown settings enums;
независимый golden; неканонический CBOR reject; все21 исходных S1 vectors с отказами,
включая malformed UTF16. Всего7 tests, без полного NormalizationTest на телефоне.
Только synthetic values, assert diagnostics не печатают secrets/notes.

### Подготовка артефактов и запуск

`verifyP1ReleaseApks` автоматически предшествует connected task. Java audit читает
class_defs DEX target/test, подтверждает backend в target и запрещает вторую копию
storage/ICU/CBOR в test APK. Проверяет3950 ICU files, SHA256 `nfc.nrm`/`uprops.icu`
из закреплённого ICU4J76.1, license/NOTICE assets, точность Android fixtures относительно
frozen independent JVM fixtures. Test APK содержит только fixtures и suite, не копию
production backend. Audit выводит `runtime=NOT_RUN` и никогда не выдаёт runtime PASS.

Готовая Windows/Honor инструкция: [android-runtime-windows.md](../../../tools/p1/android-runtime-windows.md).
Android Studio Run configuration: **P1 Release Runtime**. Эквивалентная одна команда:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
```

APK target: `app/build/outputs/apk/p1Release/app-p1Release.apk`, package
`com.fantadrom.copperscroll.p1runtime`. Test APK:
`app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`, package
`com.fantadrom.copperscroll.p1runtime.test`, instrumentation target — предыдущий package.
Оба устанавливаются Gradle автоматически, отдельная ручная установка не нужна.

APK audit: `app/build/reports/p1-runtime/apk-audit.txt`;
runtime HTML: `app/build/reports/androidTests/connected/p1Release/index.html`;
XML/logs: `app/build/outputs/androidTest-results/connected/p1Release/`.
Для полного runtime результата теперь требуется итоговый `p1ReleaseRuntimeCheck`,
свежий `runtime=PASS`, ровно7 passed на каждом устройстве,0 failures/errors/skipped
и BUILD SUCCESSFUL. Один успех connected task с tests="0" недостаточен.
На второй Android-конфигурации запускается та же команда.

Здесь подходящий Android runner отсутствует: emulator/system image не установлены,
Honor пользователя находится вне среды агента. Фактическое выполнение остаётся
пользователю. P1 не объявляется полностью принятым, PR6 остаётся Draft до результатов.

### Выполненные агентом проверки этого дополнения

Использованы те же JDK25.0.4.1, SDK37, ANDROID_USER_HOME и Gradle cache, что в P1;
новых production dependencies/версий нет. Реально выполнены:

```bash
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:verifyP1ReleaseApks :app:lintP1Release --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:connectedP1ReleaseAndroidTest --dry-run --console=plain
JAVA_HOME=/workspace/tooling/jdk-25.0.4.1 python3 tools/p1/check-apk-audit.py
```

| Проверка | Фактический результат |
| --- | --- |
| Обычные debug/release,35 unit tests,lint,debug test APK | BUILD SUCCESSFUL,22s;35 passed,0 failures/errors/skips |
| P1 target/test R8,APK audit,lintP1Release | BUILD SUCCESSFUL; после добавления config invariant guard21s,81 tasks; оба `minifyP1ReleaseWithR8` / `minifyP1ReleaseAndroidTestWithR8` выполнены или UP-TO-DATE |
| Lint debug и p1Release | В каждом0 errors,15 прежних warnings и1 hint; новых runtime test warnings нет |
| DEX class_defs / ICU / golden audit | artifactAudit=PASS; backend definitions в target, отсутствуют в test APK;3950 data files, pinned NFC/repertoire hashes и independent fixtures PASS |
| `aapt2 dump xmltree ... --file AndroidManifest.xml` | В обработанных APK target/test packages корректны; instrumentation target=`com.fantadrom.copperscroll.p1runtime`; debuggable target не включён |
| `apksigner verify --print-certs` | Обе подписи валидны, V2 certificate SHA256 target/test совпадают; private key не копировался/не коммитился |
| `dexdump -d` test APK | Есть реальные invoke к production `SecretEncoding.text/pin4/pin6/pattern/textUsing/verifyDataVersion` и `SettingsCodec.encode/decode` |
| R8 configuration comparison | После нормализации только путей variant `release`/`p1Release` configuration.txt совпадают; config guard дополнительно требует одинаковые enable/packageScope и non-debuggable target |
| Release vs p1Release APK | Все3953 production ICU/license entries побайтно совпали; DEX bytes не идентичны, это отдельный release-derived target, а не утверждение о побайтном тождестве штатного release APK |
| Connected task `--dry-run` | BUILD SUCCESSFUL,4s; граф включает оба R8 tasks, verifyP1ReleaseApks перед connected task; все tasks SKIPPED по смыслу dry-run, runtime не выполнялся |
| P1 connected task без opt-in | Ожидаемый отказ task not found, проверен отдельно; debug fallback отсутствует |
| Негативные проверки APK audit | Debug substitution, внедрение production DEX в test APK и изменённый nfc.nrm отклонены; исходные подписанные APK остались прежними |

Первый offline dry-run connected task не нашёл закэшированные UTP runner dependencies
(`gradle-work-action:32.4.1`); повторный разрешённый сетевой dry-run успешно загрузил
необходимое и проверил граф. Это подготовка runner, не Android execution.
Windows-команда намеренно без `--offline`, ручных изменений Gradle не требует.

SHA256 APK именно этой сборки (локальная тестовая подпись; у пользователя hashes
могут отличаться, pinned resource hashes проверяются отдельно автоматически):

```text
target: 7d68730e40087bf951a05be199dd48cce70c9c618d8d7da34bc4f07730d98661
test:   7c0f4809c40474f8363d4bb69c2875e39fbe497ddb17d1ccc998be720404c12f
```

Открытые условия не сняты: **runtime7 tests на Honor после R8**, затем тот же suite
на **второй Android-конфигурации API31+**. User debug PASS не распространяется на них.
Ожидаемые имена тестов, какие APK устанавливаются, отчёты и состав сообщения при
ошибке указаны в Windows-инструкции. PR остаётся Draft, P1 — частично выполненным.

## Исправление P1 Release Runtime crash и защита от нулевого результата

Перед этой работой локальная и удалённая ветки PR6 совпали:
`8c40277e4b38fc5487b7ea45e671b1f59fe782c6`; рабочее дерево было чистым.
PR6 open/Draft, без merge. Заголовок выше и инструкция запуска обновлены по новым
данным; прежние таблицы сборок остаются историческими, а не доказательством runtime.

### Установленная причина

Пользователь сообщил два release-запуска: Honor Android15 и эмулятор Android17,
оба с XML `tests="0"` несмотря на BUILD SUCCESSFUL connected task. Прямой
instrumentation сообщил Process crashed. **Оба результата не являются PASS.**
Подтверждённый ранее пользовательский debug run Honor (`StorageDependencyTest`,
один тест) сохраняется отдельным PASS и не распространяется на release suite.

Из приложенного `p1-crash.txt` выделен только AndroidRuntime процесса
`com.fantadrom.copperscroll.p1runtime`; системный `uwb-service` относится к другому
процессу и не использован для диагностики приложения. До пересборки сохранены
локальные target/test/release APK и target/test mapping. Заголовок target mapping
**точно совпал** с map ID пользовательского стека:

```text
6ef4545cbf2624cbf7f9bf58499d96cdcae12dcf8d0385638364d05128fb76e9
```

Retrace выполнен R8 из исходного AGP9.4.1 builder JAR, compiler9.4.24, с этим
mapping. Mapping новой/чужой сборки для расшифровки не применялся. Расшифрованная
цепочка: `InitializationProvider.onCreate:45` → `AppInitializer.doInitialize:168`
→ `ProcessLifecycleInitializer.dependencies:39`; StartupException оборачивает
IllegalAccessError при загрузке superclass. Соответствия точного mapping и
**реальные DEX class_defs**, включая access_flags и superclass_idx:

| Исходный класс | Обработанный descriptor | Access flags | Superclass |
| --- | --- | --- | --- |
| kotlin.collections.CollectionsKt__IterablesKt | Lkotlin/collections/j; | 0x0000: package-private | Lkotlin/collections/i; |
| kotlin.collections.CollectionsKt__MutableCollectionsJVMKt | Lbh; | 0x0401: public abstract | Lkotlin/collections/j; |

`bh` оказался в default package и наследует недоступный package-private класс
из `kotlin.collections`. Эта недопустимая связь найдена **внутри target APK**;
она не требует конфликта двух ClassLoader для возникновения IllegalAccessError.
Старый target и обычный production release содержат по4544 определения и ровно
одну такую межпакетную связь; старый test APK содержит1722 определения, не определяет
ни `bh`, ни `kotlin.collections.j`, недопустимых связей в нём нет. Пересечение
определений target/test пусто, включая Kotlin и AndroidX. Старый audit проверял
backend/resources, но не superclass access, поэтому пропустил дефект.

При текущей частичной optimization scope R8 переупаковал подкласс, оставив родителя
package-private в исходном пакете. Проверены конфигурация, mapping и class_defs;
`-allowaccessmodification` уже присутствовал, его повторное добавление не было бы
исправлением. Дефект найден и в production release, поэтому test-only обход
не устранил бы проблему обычной сборки. Это установленный дефект выходной иерархии
при данной конфигурации, без утверждения о единственной внутренней причине в R8.
Совпадение resolved runtime версий и отсутствие общих DEX definitions проверены
отдельно; вторую копию backend в instrumentation не добавляли.

Пользовательское локальное отличие Gradle9.8.0 учтено. Недопустимая иерархия уже
была в сохранённых APK агента, собранных нормативным wrapper9.6.0. Следовательно,
9.8.0 не требуется для её появления; причинной связи с обновлением wrapper нет.
Gradle/AGP/Kotlin/зависимости и JDK ради предупреждений не менялись.

### Минимальное исправление и границы

В штатный AGP source folder `app/src/main/keepRules/` добавлен один файл
`kotlin-package-access.keep`, одна директива:

```proguard
-keeppackagenames kotlin.collections.**
```

Сохраняется пакет взаимозависимых Kotlin collections classes. Их имена и members
по-прежнему могут обфусцироваться; shrinking/optimization не отключены, blanket
keep отсутствует. Правило действует в настоящем release и наследуемом p1Release.
После обработки оба затронутых класса находятся в одном пакете
(`kotlin.collections.r` и `kotlin.collections.s`), superclass доступен; проверяется
вся известная APK-иерархия superclass/interfaces, а не только эти два имени.
Startup provider и ProcessLifecycleInitializer остаются в manifest.

**Optimization scope остаётся `androidx.**`, `kotlin.**`, `kotlinx.**`.**
Storage, ICU, CBOR по-прежнему вне неё. Проверка подтверждает реальные backend calls
из processed target, но не оптимизацию самого storage/ICU/CBOR. Сохраняются отличия
p1Release: package suffix, локальная test signing, fallback и marker. Штатная
release signing не меняется; приватные ключи/пароли не добавлены.

`VerifyRuntimeApks` теперь читает access_flags, superclass и interfaces из class_defs,
отклоняет cross-package nonpublic parent и любые общие target/test definitions.
Также остаются проверки backend,3950 ICU files, pinned hashes, независимых golden
и licenses. Режим `--hierarchy <APK>` используется для обычного release и негативной
проверки исходных APK. Это структурный guard; он не эмулирует ART и не доказывает
корректность всех обращений к полям/методам. Реальный Android runtime обязателен.

### Проверка результата входит в единую пользовательскую команду

Run configuration **P1 Release Runtime** теперь вызывает `p1ReleaseRuntimeCheck`.
Граф: APK audit и host tests валидатора → `prepareP1RuntimeRun` →
`connectedP1ReleaseAndroidTest` → строгая проверка XML. Прежний прямой connected
запуск больше не является итоговой проверкой.

Подготовка всегда заново удаляет прежние XML/HTML, промежуточные P1 results,
additional output и runtime summary; сохраняет новый UUID и snapshot выбранных
online API31+ устройств через SDK adb. Поддержан одиночный ANDROID_SERIAL;
без него ожидаются все подключённые устройства. Offline/unauthorized/отсутствие
устройств — ошибка. При одинаковых model/version требуется последовательный запуск,
чтобы не угадывать соответствие AGP device name и serial. Connected task принудительно
не UP-TO-DATE. Поздно подключённое/исчезнувшее устройство не может дать общий PASS.

`VerifyRuntimeResults` требует семь конкретных методов `P1BackendRuntimeTest`
**для каждого ожидаемого устройства**, каждый ровно один раз. Проверяет failure,
error, skipped, suite counters/status, неполный/чужой набор, дубликаты, свежесть XML
относительно manifest. Missing/zero/stale XML и crash runner завершают итоговую
команду ошибкой. `testsuites` и вложенные `testsuite/testcase` обходятся по testcase
один раз, родительские counts повторно не суммируются. DTD/external entities запрещены.
Если connected task сама упала, Gradle останавливает цепочку без runtime PASS.

В bytecode текущего `android-test-engine:1.0.1` дополнительно установлен изъян
reporting: `XmlTestRunListener.testRunFailed` не записывает ошибку, результат
создаётся уже при testStarted, XML errors/skipped выводятся как0. Поэтому семь
XML cases сами по себе не доказывают семь завершённых PASS. Итоговый gate также
требует свежие `test-result.pb` и `test-result-exit-code.txt` из той же results folder:
exit0, suite PASSED без platform error, ровно7×число устройств **завершённых** test
results, каждый PASSED без error, каждый ожидаемый method встречается ровно по
числу устройств. Per-device XML проверяется отдельно, объединённый protobuf не
суммируется с ним. Поля schema и пути выходных файлов проверены в bytecode
AGP32.4.1 `AndroidTestEngineRunner`/`UtpTestUtilsKt` и его result listener.

Для чтения используется host-only `p1ResultProtocol`: test-result-proto1.0.1 и его
protobuf3.25.5, те же версии, которые уже использует AGP9.4.1 runner. Это новая
связь **host-валидатора** с существующим форматом отчёта, не обновление инструментов
или Android dependencies; ни в target, ни в instrumentation APK эти host JARs
не добавляются. При смене AGP формат результата нужно пересмотреть, отсутствие
обязательного machine result сейчас считается ошибкой, не PASS.

Host regression suite из30 проверок покрывает полный PASS, вложенные wrappers,
два устройства по7, zero, incomplete, failure, error, skipped, ненулевой summary,
отсутствующий/устаревший/дублированный отчёт, неверный method/class/device,
отсутствующие результаты одного устройства, status notrun, DTD, отсутствие manifest
и удаление старого PASS перед запуском. Также проверяются противоречащие чистому XML
runner crash/skipped/STARTED, отсутствие/неполный/старый/повреждённый protobuf,
platform error и failed machine suite. Это fixtures для валидатора, не Android tests.
Задача `testP1RuntimeResults` включена в `check`; Android test dependencies не менялись.

Готовая команда PowerShell из корня актуальной ветки:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
```

После выполнения ожидаются `runtime=PASS`, `variant=p1Release`, новый `runId`, строка
`device=<serial> tests=7 failures=0 errors=0 skipped=0` для каждого устройства и
BUILD SUCCESSFUL. Итог: `app/build/reports/p1-runtime/runtime-result.txt`;
snapshot: `run.properties` рядом; XML/HTML и SHA256 audit — по путям выше.
Windows-инструкция перечисляет точные семь имён тестов, устанавливаемые два APK,
ошибки и нужные материалы, включая matching mapping при новом Process crashed.

### Проверки исправления

Результаты сборки, host tests и APK audit описаны далее отдельно от Android execution.

Использованы нормативный wrapper9.6.0, AGP9.4.1, Kotlin2.2.10, JDK25.0.4.1,
SDK37 и заполненный Gradle cache. Команды из корня репозитория (с настроенными
JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME), без обновления Android dependencies:

```bash
bash ./gradlew :app:testP1RuntimeResults :app:testDebugUnitTest :app:lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:testP1RuntimeResults :app:verifyP1ReleaseApks :app:lintP1Release :app:assembleRelease --offline --console=plain
bash ./gradlew :app:testP1RuntimeResults :app:testDebugUnitTest --rerun --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:verifyP1ReleaseApks :app:lintP1Release :app:testP1RuntimeResults --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:dependencies --configuration p1ReleaseRuntimeClasspath --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:dependencies --configuration p1ReleaseAndroidTestRuntimeClasspath --offline --console=plain
python3 tools/p1/check-apk-audit.py
java -cp app/build/p1-apk-verifier/classes VerifyRuntimeApks --hierarchy app/build/outputs/apk/release/app-release-unsigned.apk
python3 tools/p1/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
git diff --check
```

Также выполнены `VerifyRuntimeApks --hierarchy` на сохранённых **до исправления**
target/release APK с ожидаемым отказом, retrace по точному mapping,
`aapt2 dump xmltree` обоих новых manifest и `apksigner verify --print-certs` обоих APK.
Граф итоговой команды повторно проверен после добавления machine-result gate.

| Проверка | Результат агента |
| --- | --- |
| Debug/release сборки, debug instrumentation APK, lint | BUILD SUCCESSFUL; debug lint0 errors/15 прежних warnings |
| JVM unit tests, принудительный новый запуск `--rerun` | 35 tests,0 failures/errors/skipped; XML timestamp текущего запуска; включён ICU conformance, независимый unicodedata2 PASS остаётся исходным evidence |
| Результат-валидатор, финальная версия | 30 host checks PASS, включая crash/skip/незавершённый test при чистом XML; Android NOT RUN |
| P1 target/test R8, APK audit, lintP1Release | BUILD SUCCESSFUL; R8 включён для обоих APK; lint0 errors/15 прежних warnings |
| Новый target и production release | По4547 definitions; `s` flags0x401 → `r` flags0x0000 внутри kotlin.collections; недопустимых superclass/interface package-access связей нет |
| Новый instrumentation APK | 1722 definitions;0 общих definitions с target, включая Kotlin/AndroidX; backend/ICU resources не продублированы |
| Исходные APK до исправления | Target и production release отклонены новым audit по точной связи Lbh; → Lkotlin/collections/j; |
| APK audit negative tests | 4 отказа: debug substitution, backend DEX в test, повреждённый NFC resource, синтетический недоступный superclass; исходные signed APK не изменены |
| Ресурсы/fixtures | 3950 ICU files, pinned NFC/repertoire hashes,21 S1 vectors, независимый CBOR golden/licenses PASS;28 frozen fixtures не изменены |
| Runtime dependencies | В обоих resolved classpaths kotlin-stdlib2.2.10, lifecycle-process2.11.0, startup-runtime1.1.1; test constraints согласованы с target; конфликт версий причиной не установлен |
| Конфигурация настоящего release / p1Release | configuration.txt совпадают после нормализации variant paths; packageScope не менялась, новое package rule присутствует в обоих |
| Manifest/signing | Target non-debuggable, provider и ProcessLifecycleInitializer на месте; test targets p1runtime, обе подписи валидны с одинаковым локальным test certificate |
| Host protocol reader | Читает существующий AGP schema через отдельную host configuration; target/test APK hashes после добавления reader не изменились |
| Static docs checks / diff | 32 local links,26 tables,106 future IDs; git diff --check PASS |
| Android execution | **НЕ ВЫПОЛНЕН АГЕНТОМ**: физический Honor недоступен; emulator executable/system image и /dev/kvm отсутствуют |

Hashes конкретных локально собранных APK (подпись пользователя может дать другие):

```text
target SHA256: 70711b88cb826b5dc7222adeddf94b84557b3798e6bd1ad11fd6da79877bfb94
test SHA256:   ea5e7f67223bfb35c8e6d97073ec6e4d1e91dd258eb7409efcb1f9a152c008f3
release SHA256:e788f93374684efc638f375ad09c6c00ddaa0bdd5245399975b65b50281f4afa
target/release new R8 map ID: d4b4cd746c84fb9df1c6e5274a6567ee1bbc29e3a3820dee0e3ca5e30fccd96d
```

Новый mapping **не** использован для старого crash с ID6ef4545c… . Build/audit/dry-run
не выдаются за успешное Android execution. Unsafe/protobuf warning появился и при
host protocol tests на JDK25, но не вызвал отказ; зависимости/JDK ради него не менялись.

Оставшиеся условия: пользователь обновляет ветку и запускает одну готовую команду
из Windows-инструкции сначала на Honor15, затем на второй конфигурации, например
эмуляторе17, сохраняя результаты каждого запуска до следующего (новый запуск удаляет
старые отчёты). Нужны **семь реально завершённых PASS на каждом устройстве** и
успех итогового gate. До этих результатов исправление runtime требует подтверждения,
P1 не полностью принят, PR6 сохраняется Draft и не сливается.

## NoClassDefFoundError Trace: согласование target/test ABI

Исходная локальная и удалённая ветка перед изменениями:
`20cae6b6f3255bb37afe854f8f56e3b38bf1ff61`; дерево чистое, PR6 Draft.
Это отдельное продолжение после Kotlin package-access fix. Старые сборочные
таблицы выше относятся к прежним коммитам; актуальные отличия p1Release приведены здесь.

### Свежий пользовательский сбой и точный retrace

Прочитаны `p1-honor-crash.txt` и `p1-honor-diagnostics.zip`. Для расследования
выделен только Honor/Android15, время **16:03:54**, процесс p1runtime/PID625:
`NoClassDefFoundError: Failed resolution of: Landroidx/tracing/Trace;`.
Более ранние Kotlin IllegalAccessError из того же файла не объединялись с ним.
Системные ошибки не использованы как evidence падения приложения.

Test stack содержит map ID
`9395cfa3fcb41421f1bf50caf850c29820751e5bb98539fcf0f3907ddd1f1b17`.
Локально сохранённый **test** mapping имеет точно этот ID. Retrace тем же AGP-bundled
R8 выполнен на свежем фрагменте журнала: `AndroidJUnitRunner.onCreate(AndroidJUnitRunner.java:307)`.
Target mapping из ZIP имеет другой ID,
`d4b4cd746c84fb9df1c6e5274a6567ee1bbc29e3a3820dee0e3ca5e30fccd96d`,
и для этого retrace **не применялся**. В ZIP test mapping отсутствовал — это также
устранено новым диагностическим архивированием.

Пользовательский XML — `<testsuites tests="0" .../>`, runner exit-code файл —0.
Сам merged protobuf разобран текущим AGP schema: status=PASSED, completedTests=0,
platformError=false. Поэтому одних suite status/exit0 также недостаточно;
существующий gate по-прежнему требует именно семь completed-test results.
Это **НЕ PASS**, включая случай BUILD SUCCESSFUL. Ранее подтверждённый debug Honor
`StorageDependencyTest`,1 passed, сохраняется отдельным пользовательским evidence.
APK пользователя в ZIP не вложены; их записанные hashes отличаются от локальной
тестовой подписи. Анализ реальных DEX выполнен на сохранённых локальных APK с
соответствующими R8 IDs; не утверждается побайтное тождество пользовательским APK.

### Установленная причина

В старых target/test DEX нет определения `androidx.tracing.Trace`. В test DEX есть
реальные invoke-static к трём API: `beginSection(String)`, `endSection()`,
`forceEnableAppTracing()`, включая runner startup. В target mapping нет class entry
Trace, но есть inline frames его begin/end методов в других классах; то же видно
в пользовательском target mapping. Исходный класс не сохранён как доступный runtime API.

Resolved tracing —1.2.0 в app/test; runner —1.7.0. Общая зависимость исключается
из instrumentation APK, а target R8 оптимизирует её для приложения, не получая
требований внешнего test consumer. Следовательно, Trace удалён/встроен в target
и отсутствует в test из-за исключения общей зависимости. Это разрыв ABI между
APK, а не доказанный конфликт версий или двух копий класса. Самостоятельному
production release не требуется этот внешний runner API.

В test R8 configuration подтверждено применение target `-applymapping`. Оно может
переписать имена сохранённых symbols, но не восстанавливает удалённый класс.
Добавление ещё одной записи той же dependency не гарантировало бы его упаковку.
Прежний аудит absence-of-duplicates недостаточен: ноль копий требуемого класса
также даёт ноль дубликатов. Статически показано отсутствие определения в обоих APK;
любой из их loaders не может получить этот класс из данных двух DEX. Реальные
Android class loaders здесь не запускались; их проверка добавлена в suite ниже.

Анализ старого test DEX также выявил много отсутствующих shared type references,
особенно из неиспользуемых Compose UI-test библиотек. Сырые type_ids включают metadata
и не приравниваются к списку фактически проявившихся runtime crashes. Исправление
проверяет всю оставшуюся границу runner/test → target, а не один Trace.

### Исправление и отличие от настоящего release

P1 instrumentation теперь использует прямой AndroidJUnitRunner1.7.0, уже ранее
разрешавшийся в этой версии, AndroidJUnit4/core/monitor и их зависимости.
Compose/Espresso UI-test dependencies сохранены для обычного debug; opt-in backend
suite их не использует и не включает. Ни один из семи методов P1 не удалён/ослаблен.
Production dependencies, wrapper9.6.0, AGP9.4.1, Kotlin2.2.10, ICU/CBOR и JDK не обновлялись.

`generateP1RuntimeApi` читает реальные pre-R8 app/test class inputs через публичные
ScopedArtifacts API AGP и resolved runtime artifacts. Общие classes исключаются
из source по имени **и совпадению bytes**; различающиеся definitions дают ошибку.
Текущий AGP-bundled R8 TraceReferences строит точечные keep rules для target ABI,
используемого instrumentation. Зависимость графа: compiled test classes → generated
keep rules → target R8 → target mapping → test R8. Цикла через processed APK нет.
Подготовленные JARs используются только host tool, не добавляются в test APK.

Правила генерируются в `app/build/generated/p1-runtime-api/keepRules/` и подключаются
только к p1Release. Они сохраняют требуемые classes/members и их доступность,
разрешая обфускацию там, где её поддерживает TraceReferences. В частности, Trace
содержит три требуемых метода; test invokes переписаны к их фактическим target names.
Таким же способом сохранены shared APIs futures, Kotlin и coroutines.

Расширенный audit до нового пользовательского запуска обнаружил вторую проблему:
test DEX вызывал `jn.probeCoroutineSuspended(Continuation)` после class/type mapping,
а target содержал только переименованный `jn.a(...)`. Внутренний момент обработки R8,
на котором метод перестал согласовываться, отдельно не доказывался. Для compiler-owned
ABI добавлен p1Release-only `compiler-intrinsics.keep`: стабильные имена DebugProbesKt
и трёх probe методов created/resumed/suspended. Это одна семья compiler entry points,
а не отключение оптимизации Kotlin/coroutines целиком. После этого DEX audit проходит.

При сокращённом test classpath R8 отдельно сообщил две отсутствующие compiler
annotations: CanIgnoreReturnValue и MustBeClosed. Bytecode Error Prone проверен:
первая имеет explicit CLASS retention, вторая — default CLASS retention. Два точных
test-only dontwarn относятся только к этому metadata при прежнем исключении Error
Prone runtime JAR с JDK-only enum. Никакие missing AndroidX/Kotlin runtime symbols
не подавляются; generic dontwarn/dontshrink/dontoptimize не добавлялись.

**R8 enable и packageScope остаются `androidx.**`, `kotlin.**`, `kotlinx.**`.**
Storage/ICU/CBOR по-прежнему вне optimization scope. Штатный release не получает
generated instrumentation ABI и compiler-intrinsics rules. Поэтому p1Release теперь
отличается от него не только install ID/signing/fallback/marker, но и этими требованиями
внешнего consumer к сохранению API. Runtime PASS будет evidence данного обработанного
release-derived target; это не доказательство идентичного R8 результата обычного release.
Startup provider/lifecycle не отключены; прежнее kotlin.collections package rule сохранено.

### Расширенный APK audit, class loaders и диагностика

`DexRuntimeLinkage` индексирует реальные DEX definitions, access flags, methods,
fields, prototypes, inheritance и type/member instructions обработанного test APK.
Неплатформенные operational/signature types должны существовать; method/field
references разрешаются через target/test hierarchy и SDK stubs для унаследованных
platform members. Для обращений к target проверяются class/member access и
static/instance согласованность. Конструкторы не считаются унаследованными.
Отсутствующие symbols не заменяются системными или test backend implementations.

Граница проверки: это static instrumentation linkage, не ART verification,
не доказательство reflection/динамической загрузки и не проверка доступности всех
SDK37 APIs на Android15. Platform-owned methods не объявляются APK symbols.
Явно учтён upstream AndroidX hidden bridge ExposedInstrumentationApi.execStartActivity:
скрытые framework overloads отсутствуют в публичных SDK stubs; это отмечено отдельно
в audit. Исключение не относится к missing shared target APIs. Ранний guard hierarchy,
запрет дубликатов/backend copy,3950 ICU files/hashes и fixtures остаются обязательными.

В существующий `@Before` P1 suite добавлены Trace class identity через target loader
и реальные вызовы begin/end/forceEnable с фиксированной synthetic trace label.
Новых test методов нет: ровно прежние семь, тот же production S1/codecs backend.
Без Android runner фактическое разрешение этими loaders не объявляется проверенным.

Каждый audit сохраняет **оба** mappings, обе эффективные R8 configurations и generated
ABI rules в `reports/p1-runtime/artifacts/<bundleId>/`. Bundle ID вычисляется из SHA256
APK/mappings/configurations/rules. Map IDs обязаны совпасть с embedded R8/PartialR8
markers соответствующих DEX; подмена target mapping вместо test mapping даёт отказ.
Снимки неизменяемые, отдельные build outputs не могут затереть прошлую диагностику.

Готовый ZIP: `app/build/reports/p1-runtime/p1-diagnostics.zip`; manifest рядом и внутри
содержит SHA256 обоих APK/mappings, component map IDs, config/rules hashes. Снимок
сохраняется и при последующей неудаче semantic audit; mappingMarkers=PASS сам по себе
не означает artifact/runtime PASS. Новая сборка может заменить latest ZIP, но сохраняет
прежнюю hash-keyed копию; Clean Project удаляет build, поэтому ZIP надо копировать наружу.

Run manifest и успешный runtime summary теперь содержат bundle ID и обе APK/map
идентичности. При смене artifact identity между prepare/verify итоговая команда падает.
Исходная защита от нуля/неполного набора/failed/errors/skipped и старых отчётов
сохранена: требуются XML, completed statuses из protobuf и runner exit status.
Host suite содержит33 проверки, включая binding/смену/отсутствие artifact identity.

### Валидация и оставшиеся условия этого исправления

Единая команда Windows **не меняется**:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
```

Нужны `runtime=PASS`, новый run ID, artifact bundle ID и **семь завершённых PASS
на каждом устройстве**,0 failures/errors/skipped, BUILD SUCCESSFUL итоговой задачи.
Ожидаемые имена методов и материалы при ошибке перечислены в Windows-инструкции.
Последний Honor zero-test run не закрывает gate; второй подтверждённый Android
runtime также остаётся обязательным. PR6 сохраняется Draft, без merge.

Реально выполненные финальные команды (с прежними JAVA_HOME/ANDROID_HOME/Gradle cache):

```bash
bash ./gradlew :app:testDebugUnitTest --rerun :app:testP1RuntimeResults :app:lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:verifyP1ReleaseApks :app:lintP1Release :app:testP1RuntimeResults --offline --console=plain
bash ./gradlew -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --dry-run --offline --console=plain
python3 tools/p1/check-apk-audit.py
python3 tools/p1/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
git diff --check
```

| Проверка | Фактический результат |
| --- | --- |
| Нормативные debug/release и debug test APK, lint | BUILD SUCCESSFUL; debug lint0 errors/15 прежних warnings |
| JVM unit tests, новый `--rerun` | 35 tests,0 failures/errors/skipped; Unicode JVM evidence сохранено отдельно |
| Host result gate | 33 checks PASS; Android NOT RUN |
| P1 target/test R8, полный новый audit, lintP1Release | BUILD SUCCESSFUL; lint0 errors/15 прежних warnings |
| Финальные DEX | target4634/test842 definitions,0 дубликатов; backend только в target |
| Trace после обработки | target Lr81;, public final0x11; a(String)/b()/c() public static0x09; test ссылается именно на эти три definitions, собственной Trace copy нет |
| Compiler probes | target DebugProbesKt с тремя стабильными probe именами, public static final0x19; test refs разрешаются |
| Полный instrumentation linkage | PASS;5292 method refs,1577 field refs,21375 code edges; runtime class/member missing/access/static mismatch не найден |
| Исходная согласованная пара APK до fix | Новый DEX linkage audit её отклоняет,532 distinct static problems в сохранённом test code; это консервативный scan всего test APK, не532 проявившихся P1 crashes |
| APK negative checks | 8 ожидаемых отказов: debug substitution, backend copy, повреждённый ICU data, недоступный superclass, отсутствующий runner class, отсутствующий метод, метод private вместо public, target mapping вместо test mapping |
| Snapshot ZIP | Оба mapping/configuration и API rules побайтно проверены по SHA256 manifest; embedded R8 markers соответствуют своим IDs |
| Manifest/signatures | test targetPackage=p1runtime; Startup/lifecycle на месте; target non-debuggable; обе подписи валидны с общим локальным test certificate |
| Обычный release | Hierarchy audit PASS; generated instrumentation rules ему не добавлены; не утверждается runtime PASS или тождество p1Release |
| Итоговый граф команды | compiled instrumentation → generateP1RuntimeApi → target R8 → test R8 → audit → prepare → connected → result gate; dry-run успешен, это не запуск на Android |
| Frozen fixtures / docs | 28 fixtures PASS;32 links/26 tables/106 IDs; diff whitespace check PASS |
| Android runtime после этого fix | **НЕ ВЫПОЛНЕН АГЕНТОМ**: доступного телефона/эмулятора нет; emulator executable/system image и /dev/kvm отсутствуют |

Конкретные локальные артефакты финального audit (другая локальная подпись меняет APK hashes):

```text
target SHA256: d1d09e3f5a02daf4ace95dff773c4db9779fc021bd2c00b3a59861f32132c012
test SHA256:   581cca91b036b377554cff3ec5ce633052743a616b31869800cc507522299804
target map ID: 529f88a32a9325ad09dd040ae69454f393aa64561e0efcef055d4c8952f9f763
test map ID:   220bd9295f7f5f3f69dfa6dad8637b0fea4f1b16e5a5758b2c6191d70235ba90
bundle ID:     2f3f534cb7a1f709953175c1b10b0f27a5f845a87e8591dee646c57d994e9f90
```

Новые mappings не использованы для retrace старого Honor crash. Предупреждение
Unsafe/protobuf не вызвало отказа host checks; JDK/зависимости ради него не менялись.
После Pull пользователь запускает прежнюю единую команду, сохраняет отчёт Honor
и диагностический ZIP вне build, затем повторяет на второй Android-конфигурации.
До семи завершённых PASS на каждом устройстве runtime fix не подтверждён, P1 не принят полностью.
