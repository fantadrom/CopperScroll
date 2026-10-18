# P1 — codecs, строгий parser и S1

Дата отчёта: 19 октября 2026 (Asia/Bishkek). Исходная база master:
`42c7bb262b0f1ae5e5281af774fd68f0b43c6758` (PR5 слит; согласование
`e2b59ab67f29ca4e49bcf902adf92f9929d63b6c` включено). Повторный read master перед
публикацией подтвердил тот же SHA. Ветка: `codex/copper-scroll-p1-codecs-parser-s1`.
Нормативная основа: согласованные design/format/test/prototype/resolution1.1,
reconciliation-materials и текущий `editor/NoteDocument.kt`.

**Статус: P1 частично выполнен; полная приёмка ожидает Android runtime gate.**
Независимые Unicode conformance и normative-byte проверки выполнены. Сборка Android
и упаковка ресурсов проверяются отдельно от исполнения: Android runner здесь
отсутствует. P1 не создаёт защищённый пользовательский контейнер.

## Реализация

Core находится в `app/src/main/java/com/fantadrom/copperscroll/storage/format`,
S1 — в `storage/access`; Android/Compose/View/URI/ContentResolver зависимостей нет.
Работающий UI и модель редактора не подключены к новому слою. Сохранены SDK37,
minSdk31, Gradle9.6.0, AGP9.4.1, Kotlin2.2.10 и исходные настройки release optimization.
В version catalog добавлены только две точно закреплённые зависимости.

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
подключённых устройств не установлен, API31+ instrumentation **не выполнена**.
`StorageDependencyTest` добавлен и test APK собран; это не заменяет запуск.

Release/R8 build и наличие ICU data/notices в APK проверены. S1/CBOR runtime **после
R8 на Android не выполнен**. Новый storage слой пока недостижим из UI; исходный optimization packageScope сохранён.
Сборка и наличие resources сами по себе не доказывают выполнение backend calls после R8.
Перед приёмкой P1 требуется API31+ execution, two-device S1 matrix и runtime smoke
release/R8 с реальными вызовами storage backend. Новые keep rules и ослабление R8
для получения формального PASS не добавлялись.

P2 может использовать проверенные модели/bytes и S1 для отдельной crypto разработки,
сохраняя указанный gate открытым. P1 полностью принятым и пользовательским хранилищем
не объявлен. Argon2/encryption/key management, authentication, transactional writer,
recovery/durability, SAF, lifecycle и UI integration не реализованы и не проверены.
