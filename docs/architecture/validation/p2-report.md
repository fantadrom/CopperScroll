# P2 — криптографические примитивы, ключи зон и envelopes

Дата: 19 октября 2026. База — master
`0e7b46cc1eff4059da4ce2f32b6194711801a16c`, пользовательский merge PR6 с P1 head
`b510f0f270653a371a23414cbd46463c5fb18a74`. Перед началом GitHub API подтвердил
merge и чистое локальное дерево с теми же bytes. Ветка — `codex/copper-scroll-p2-crypto-keys`.
AGENTS.md в репозитории/родителях не найден. Прочитаны design/format/test/prototype/
review-resolution1.1, p1-report и P1 codecs/S1/runtime tools. Эти нормативные документы
не переопределяются реализацией; новые wire поля/версии не вводились.

**Статус: реализация P2 и пользовательские Android runtime-прогоны подтверждены;
device KDF benchmarks остаются открытыми.** Реализован memory-only crypto слой.
Android execution выполнен пользователем, не агентом; его PASS подтверждён
приложенными runtime-result, отдельно от сборки и APK audit.
Это не файловый движок, ChangeSecret commit и не production approval.

### Пользовательские результаты после PR №7

В задании P3 от 19 октября 2026 пользователь предоставил итоговые
`p2-runtime-result.txt` и `p1-runtime-result.txt` для head P2
`134dbbb0cee49d3623f40f557e6adf837c206e5e`. Оба содержат `runtime=PASS`,
`variant=p1Release`, по два устройства и точные отдельные ожидаемые suite:

| Suite | Honor ABR-LX1 / Android15 | Эмулятор / Android17 | runId |
| --- | --- | --- | --- |
| P2CryptoRuntimeTest |8 passed,0 failures/errors/skipped |8 passed,0 failures/errors/skipped |`c5e23190-c110-4e53-a8d5-03d8a81b379f` |
| P1BackendRuntimeTest |7 passed,0 failures/errors/skipped |7 passed,0 failures/errors/skipped |`9b4a0d20-c17b-4023-9fee-405fcafa086d` |

Проверена одна пара APK:

```properties
artifactBundleId=d7fec5127795aecc51acdfd4d164b34d7a03089d5d2964b6e8db2cdf5d8d1d1c
targetSha256=70ddaf1d070215dc06c7f169f0b3293214532a17bac786dfd0aa107e5393c933
testSha256=02117eed8f02416f19b763fc17c1b9ea968e58bddb59747d6e66856da1810179
targetMapId=65a9b9b15f4099b68059e4913d3e863b385252d5eac4967e6da2839516d33fc6
testMapId=4185c1c04c688d5db9effa97580d5bf06dc86ee2e0c4a0135b8e3f953209f5be
```

Это пользовательские прогоны release-derived target, не обычного production
release и не выполнение агента. Версия Gradle в этих двух receipts не записана;
нормативный wrapper репозитория остаётся 9.6.0. Даты/условия фактических запусков
не выводятся из runId. Прежние ограничения R8/test-target ниже сохраняются.
Новое ТЗ явно разрешает P3 на синтетических данных без ожидания device KDF
benchmarks; оно заменяет прежний входной gate P3 в конце исторического отчёта.

## Зависимости и первичные источники

Добавлена одна production зависимость:
`org.bouncycastle:bcprov-jdk15to18:1.86`, закреплённая в version catalog.
Она доступна в Maven Central, POM не объявляет runtime transitive dependencies.
BC — lightweight Argon2id/HKDF-SHA256/HMAC-SHA256; AES256/GCM/tag128 —
`Cipher.getInstance("AES/GCM/NoPadding")` через JCA, без выбора/регистрации BC provider.
На JVM KAT использует JCA хоста; Android JCA подтверждён пользовательским P2
runtime-прогоном выше в пределах выбранного release-derived target.
Java15to18 artifact выбран для legacy classfile/API совместимости, не как готовый
сертификат Android. SDK37/min31, wrapper9.6.0, AGP9.4.1, Kotlin2.2.10 и Java11 сохранены.

Binary JAR SHA256:
`fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81`.
Лицензия Bouncy Castle MIT-style сохранена из **LICENSE.licenseText точного JAR**
в `app/src/main/assets/third-party/BC-1.86.txt`; NOTICE обновлён. Алгоритмы не переписаны.
Версии/хэши oracle и fixtures приведены в `tools/p2/provenance.json`.

Проверка опубликованных advisories выполнена 19 октября 2026 по
[BC wiki](https://github.com/bcgit/bc-java/wiki) и
[release1.86](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/):

| Advisory | Применимость к выбранному пути |
| --- | --- |
| [CVE-2026-59648](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9059648) | OpenPGP Argon2 S2K resource bounds, исправлен1.85; OpenPGP не используется. Наш whitelist проверяется до lightweight Argon2 |
| [CVE-2026-17508](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9017508) | Другие PBE entry points, исправлен1.86; эти API/provider не используются |
| [CVE-2026-97873](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9097873) | Legacy PBES1/PKCS12 unbounded iteration count, исправлен1.86; не используемый путь |
| [CVE-2026-71883](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9071883), [71884](https://github.com/bcgit/bc-java/wiki/CVE%E2%80%902026%E2%80%9071884) | Native LTS packet ciphers; upstream прямо исключает обычный pure-Java bcprov. У нас JCA AES-GCM |

В просмотренных первичных материалах не найден неустранённый advisory для используемых
Argon2id/HKDF/HMAC1.86 entry points. Это датированная проверка выбранного пути,
не обещание отсутствия неизвестных дефектов или независимый security audit.

Known-answer sources: [RFC9106§5.3](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3)
(Argon2id19 с secret/additional), [RFC5869 A.1](https://www.rfc-editor.org/rfc/rfc5869.html#appendix-A.1)
(42-byte HKDF), [RFC4231§4.2](https://www.rfc-editor.org/rfc/rfc4231.html#section-4.2)
(HMAC-SHA256), [NIST GCM examples](https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Standards-and-Guidelines/documents/examples/AES_GCM.pdf)
(GCM-AES256 Examples1/2). Малый Argon RFC vector32KiB/p4 вызывается только внутренним
test path; production whitelist16/32/64MiB, t2/3/4, p1 не расширен.

Независимые project fixtures рассчитаны Python cryptography50.0.0 / OpenSSL4.0.1,
не BC/Kotlin. Отдельный P1 fixture encoder даёт deterministic CBOR. Frozen31 поле
содержат точные intermediate/output bytes, включая Kraw/Kwrap/Kdesc/Krecord,
wrapAAD, logicalBytes/MAC, recordInfo/AAD и весь record; отдельный chunk с другим owner
и ordinal0x01020304 проверяет LE/order за пределами нулевого root ordinal. JVM и Android читают
одинаковые test-only resources; round-trip одной реализации не служит единственным oracle.

## API, владение и границы аутентификации

| API | Обязательство |
| --- | --- |
| ZoneKeys.generate / CredentialManager.create | Независимый random ZDK32, собственные salt16/nonce12/envelope; public API не экспортирует raw ZDK и не оборачивает существующий ZDK как ChangeSecret |
| Envelope / KdfParameters | Непроверенные поля проходят identity/version/S1/KDF/salt/nonce/output/input bounds до Argon2 и больших выделений |
| CredentialManager.unwrap | Один текущий envelope; tag failure = CredentialMismatch, то есть неверный секрет **или** повреждённая обёртка |
| CredentialManager.login |1/2 current envelopes последовательно, даже после первого match; два matches → AmbiguousCredential. При одном match проверить descriptor MAC; unsupported другая envelope отмечается для read-only-only продолжения будущим caller |
| verifyCurrent | Открывается именно expected zoneId/role, MAC и optional existing key сравниваются; generic main login не удовлетворяет decoy prompt |
| prepareChange | Old verification ticket, new twice, new S1≠old, inequality другой current зоны, затем новый независимый ZDK/envelope в RAM. Никакого IO или заявления Changed |
| RecordCrypto.descriptorMac/verifyDescriptor | P1 logicalBytes0…15; Kdesc/MAC domains из format§2.2, catalogueRoot намеренно вне MAC |
| RecordCrypto.encrypt/decrypt | Krecord/AAD из format§2.4, header76 через P1 codec, tag до выдачи Plaintext; hash/ref/identity проверены |
| RecordIdReservation | Future writer обязан атомарно резервировать ID против source/candidate. Синтетический set обнаруживает collision; полного каталога здесь нет |

Envelope AAD — точный deterministic CBOR format§3.1, HKDF domains включают NUL.
LE/u8/u16/u32 поля берутся из нормативного представления; instance/generation/offsets
не добавлены в record derivation/AAD. S1 используется из P1 без изменения Unicode/PIN/pattern.
После unwrap+MAC caller ещё обязан проверить root AEAD/hash/seq/schema/graph перед Open.
CredentialMatch — **не** пользовательская сессия. Другой неверный tag не удостоверяет
здоровье другой зоны; inequality относится только к её поддерживаемому current
представлению. Известная порча или unsupported блокирует prepareChange.
Управление правами main-only и файловый snapshot/lease — последующие coordinator этапы.

ZoneKey имеет одного владельца, private key array и close; наружу внутри crypto scope
передаются только выведенные subkeys. Close ждёт синхронный scope и запрещает новые
операции. Plaintext выдаётся после one-shot doFinal и тоже имеет close; его копии
принадлежат caller. Keys/credentials/results не data class, diagnostics redacted,
ошибки содержат только код без library cause, секретов/заметок в logs/state/files нет.

CurrentCredential временно владеет independently unwrapped old key и old S1 для
new-secret equality. При успешной подготовке old S1 потребляется/зануляется, old key
остаётся у ticket для будущего re-encrypt и закрывается владельцем. Повтор preparation
после успеха требует новой current verification. При отказах ticket надо закрыть
или явно удержать для разрешённого retry; новый случайный key при ошибке очищается.
Caller-owned CanonicalSecret не закрывается менеджером; операция очищает свою копию.
Kraw/Kwrap/subkeys и временная проверочная расшифровка очищаются в finally.

BC1.86 Argon2 поддерживает public BlockPool/Block.clear. Operation-owned pool
отслеживает все выделенные BC blocks и очищает их в finally, включая ошибку/OOM;
это memory wrapper, не собственная реализация Argon2. Argon builder/parameters clear
вызываются. Внутренние BC temporary/hash arrays, HKDF PRK/currentT/keyed digest copies,
SecretKeySpec/JCA Cipher state и JVM/JIT/IME/String copies полностью контролировать
и физически стереть нельзя. HMAC keyed state заменяется после использования;
нет обещания нулевых всех heap copies или2с уничтожения секретов.

## KDF admission, отмена и случайность

Один process-wide dedicated KDF worker, admission AtomicBoolean, нулевая очередь:
конкурентный submit получает Busy. Максимум два derivations на submit, последовательно.
Current-target и new-secret requests разделены. API блокирующий, обязан вызываться
вне UI thread. CryptoCancellation проверяется до/между/после вычислений. Interrupt
ожидающего caller запрашивает отмену, но ждёт фактический возврат worker и только затем
возвращает Cancelled с восстановлением interrupt flag. Следующий submit не занимает
память прежнего KDF. Coroutine cancellation сама по себе не остановка BC; будущий
adapter должен передавать cancel token и сохранять ownership до quiescence.

ResourceLimit/OOM — явная ошибка, без автоматического downgrade. Default64MiB/3/1
сохранён как гипотеза нормативного writer; функциональные fixtures явно16MiB/2/1.
SecureRandom — единственный public production источник entropy. Подмена RNG/KDF
доступна только внутренним test constructors; никаких deterministic seeds в production.
Каждое новое шифрование получает fresh random recordId/nonce; collision или RNG failure
прекращают операцию. Уже зарезервированный ID при ошибке nonce не переиспользуется
как версия записи: будущий writer должен прервать/корректно владеть reservation.

## Матрица выполненных частей

| Test ID | Выполненная P2 проекция | Оставшаяся часть |
| --- | --- | --- |
| KEY-01/02 | Независимые memory-only envelopes; другой secret не открывает чужую; чистый manager даёт тот же ключ | Файл create/add/reinstall/перенос устройства в P3/P4 |
| KEY-05 | Wrong current/expected target; new twice; unchanged canonical S1; equality другой зоны; известная порча/unsupported fail | Полный ChangeSecret/backup/commit/reset запреты P5b |
| KEY-06/07 | P1 S1, PIN/text leading zeros и pattern/PIN единый namespace; forbidden equality в credential preparation | UI/density/create/add интеграция |
| KEY-09 | Два специально созданных envelopes с одним secret → AmbiguousCredential | Полный контейнер login после root validation |
| KEY-10 | Whitelist/length/version/S1/input bounds до KDF;1 worker/2 calls/Busy/cancel quiescence | Android resource/device подтверждение и будущий coordinator |
| CRYPTO-01 | Independent KAT/fixture; container/zone/record/kind/owner/ordinal/header/nonce/cipher/tag/AAD changes reject; tag до plaintext | Полный граф/locator/file attack/recovery в P3/P4 |
| CRYPTO-02 | Retry fresh IDs/nonces; reservation collision; RNG failure; новый ZDK в RAM | Rollback/kill/durable writer/полный rekey P3/P5b |
| CRYPTO-03 | Hint входит в descriptor MAC; изменение выявлено после unwrap; physical catalogue relocation MAC не меняет | Plain-text UI rendering/hint T |
| REKEY-04 / MAINT-09 / LIFE-06 | Подготовка/ожидание KDF/закрытие handles без файлов и cleanup | Main authorization, файловое обслуживание, device revoke/IO/power loss |

Эти строки не закрывают целиком интеграционные ID. KEY-04/08 и полное REKEY-01…08,
TX/SAF/VFS/UI не объявляются выполненными. Подготовка нового ключа не изменяет
старый descriptor/key и не является пользовательским rewrap-only режимом.

## Android, release и результаты проверок

P2 использует проверенный путь **p1Release**: non-debuggable `.p1runtime`, test signing,
тот же production backend и R8 packageScope `androidx.**`, `kotlin.**`, `kotlinx.**`.
**Storage/ICU/CBOR/BC вне optimization scope.** Generated instrumentation ABI и
compiler-intrinsics rules применяются только к этому test target. Обычный release
их не получает, поэтому runtime этого target не доказывает идентичный обычный release.
Startup/lifecycle не отключены; прежний kotlin.collections package-access fix сохранён.

P1 suite/команда остаются7 тестов; P2 имеет отдельные8 методов и result profile.
Перед connected запуском удаляются старые reports, фиксируются устройства/runId и
artifact identities. Итог проверяет точный per-device набор, XML и protobuf completed
statuses, exit code и свежесть. Ноль/partial/skipped/crash — ошибка; wrappers не
удваивают testcase count. Audit проверяет реальные target/test DEX definitions,
access/members/наследование, отсутствие второго storage/BC backend, private ZDK/no public
byte exporter, pinned ICU/fixture/license assets и mappings обоих APK с SHA256.
Снимки configurations/API rules/maps неизменяемы по bundle ID; runtime gate связан с ними.

В этом окружении нет emulator executable/system image или /dev/kvm; локальный adb
не смог создать `/home/agent/.android` в read-only home. Список подключённых устройств
не получен. Honor пользователя вне среды агента. Агент Android runtime не запускал;
пользовательское подтверждение P2/P1 после PR №7 записано в начале отчёта.
Более ранние P1 пользовательские7 PASS на
Honor15 и эмуляторе17 / Windows / Gradle9.8.0 относятся к head b510f0f, сохранены
в p1-report и не являются собственным Android запуском агента или P2 PASS.

Проверки агента выполнены 19 октября 2026 на Linux, JDK25.0.4.1, нормативном
wrapper9.6.0 и SDK37. Все Gradle-команды ниже использовали `--offline --console=plain`
после загрузки закреплённых зависимостей:

```bash
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:lint :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --console=plain
bash ./gradlew -Pp2ReleaseRuntime=true :app:verifyP1ReleaseApks :app:lintP1Release --offline --console=plain
P1_AUDIT_ARGUMENTS=app/build/reports/p2-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
bash ./gradlew -Pp2KdfBenchmark=true :app:verifyP1ReleaseApks --offline --console=plain
```

Benchmark APK был собран отдельно; после этого correctness-пара P2 заново собрана
и проверена. На момент передачи она не содержит benchmark suite. Дополнительно
`p1ReleaseRuntimeCheck`, `p2ReleaseRuntimeCheck` и `p2KdfBenchmarkCheck` проверены
с соответствующим opt-in flag через `--dry-run`: это проверка графа задач, без
запуска prepare/connected/runtime verifier и без Android PASS.

| Проверка | Фактический результат |
| --- | --- |
| JVM unit tests |43 tests:35 прежних +8 P2;0 failures/errors/skipped |
| Host result verifier |33 P1 +15 P2/benchmark checks PASS; в том числе отдельные устройства, zero/partial/skipped/failure/error/stale/crash/missing proof и вложенные XML wrappers |
| Lint и сборки |`lint`, `lintP1Release`, debug/release/debugAndroidTest и обработанная P2 target/test пара — BUILD SUCCESSFUL; lint debug/p1Release:0 errors,15 прежних warnings |
| R8 APK audit |PASS:5609 test method references,1671 field references,23272 code edges; package access/linkage PASS,0 duplicate definitions, crypto/BC только в target |
| Manifest/signature audit |Оба APK проходят apksigner verify, имеют общий test certificate; target non-debuggable, правильный instrumentation targetPackage, Startup/lifecycle сохранены |
| Отрицательный APK audit |8 подмен корректно отклонены: debug target, backend copy, NFC data, package access, отсутствующие runner class/method, недоступный method, чужой mapping; исходные signed APK не изменены |
| Independent fixtures |`python3 tools/p2/fixtures.py --check`:31 поле совпало в JVM/Android; прежний P1 fixture check:28 fixtures PASS; SHA256 fixture/license совпали с provenance |
| Benchmark preparation |Отдельный обработанный benchmark target/test APK и его audit PASS; измерения не запускались; PowerShell-скрипт прочитан, в Windows не исполнялся |
| Документация/diff |Reconciliation validator PASS (32 links/26 tables/106 IDs), новые локальные ссылки проверены; whitespace check чист, кроме намеренно сохранённых upstream spaces точного BC notice |
| Android correctness / device benchmarks |**NOT RUN**: ни debug, ни release-derived runtime P2, ни измерения KDF агентом не выполнены |

Последняя локальная **correctness**-пара P2 связана следующими identities:

```properties
artifactBundleId=578d47a090649f0247a137113df9c17df19055c8229aa670af92629f5b7bdc40
targetSha256=3fccfd07cc339fc1018a6f9feaf3483664cdd7ffcbc86881df65947695eeede0
testSha256=f6e5b1d9f5617f9a188faabcfd2335fb25082fdf49ee4bdc5f396393eaea9a8e
targetMapId=65a9b9b15f4099b68059e4913d3e863b385252d5eac4967e6da2839516d33fc6
testMapId=4185c1c04c688d5db9effa97580d5bf06dc86ee2e0c4a0135b8e3f953209f5be
runtime=NOT_RUN
```

В `app/build/reports/p2-runtime/` сохранены `apk-audit.txt`,
`artifact-manifest.properties` с отдельными SHA256 mappings/configurations/rules,
`p1-diagnostics.zip` с обоими mappings и immutable `artifacts/<bundleId>/`.
Это identities локальной сборки до коммита, не требование совпадения хэшей Windows APK.
Пользовательская команда сохраняет собственную пару и связывает с ней свежий runId.

## Benchmark и оставшиеся условия

Benchmark-код компилируется **только** с `-Pp2KdfBenchmark=true`, не включён в
обычные debug/P1/P2 correctness APK/tests. Отдельный suite/profile проверяет ровно
один завершённый measurement method. Prepared18-point Windows script запускает
новый instrumentation process для каждого m/t/1-or-2 envelope, один cold +20 warm
по умолчанию, записывает wall/warm p50/p95, PSS/heap/VmHWM, API/model/ABI/thermal/
power/low-memory и число повторов. ResourceLimit измеряется явно без понижения профиля.
PSS не называется RSS; VmHWM не per-call peak. Benchmark method PASS не выбирает профиль.

Скрипт поддерживает `-ColdRepeats 20`:20 новых processes на точку и20 warm samples
в первом процессе, всего360 явных запусков; это длительный отдельный benchmark.
**Реально выполненных device benchmarks нет.** Двадцать cold repeats и слабый/средний
физические телефоны, thermal/low-memory/OOM остаются открытыми. Скорость эмулятора
не основание выбора параметров телефона; универсальный creation profile не объявлен.

Для повторения: [точная Windows-инструкция](../../../tools/p2/android-runtime-windows.md),
одна команда `p2ReleaseRuntimeCheck` на Honor15 и эмуляторе17; затем P1 regression
прежней командой. Критерии —8 P2 PASS и7 P1 PASS на каждом выбранном устройстве,
успех итоговых gates, сохранённые reports/diagnostic ZIP. Эти correctness-условия
закрыты пользовательскими receipts выше. Benchmarks запускаются
отдельно по [README](../../../tools/p2/README.md).

Готовность к P3: пользовательские P1/P2 runtime условия закрыты записью выше;
новое задание разрешает JVM/fault-store этап на синтетических данных.
Device KDF/profile/provider/security условия остаются открытыми.
P3 не получает права real-data/SAF production записи по этому отчёту.
Исходный PR №7 слит пользователем в master `cbc4d44dd7928d753374503ee1eb566d528332b0`.
Открытые device/KDF/provider условия не скрываются за BUILD SUCCESSFUL.
