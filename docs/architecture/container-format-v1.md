# Copper Scroll: формат контейнера 1.1 и расширение 1.2

Статус: согласованная нормативная спецификация прототипа, редакция 2 от 18 октября 2026.
Поддержаны containerMajor=1, containerMinor=1 и явный minor2 для карточек.
Норма расширения: [P4](container-p4-extension.md); она имеет приоритет над
ограничениями reader1.1 ниже. Envelope/record/zone/note=1 сохраняются.
Есть синтетический движок P1…P5a; P1…P4 runtime подтверждён пользователем, P5a device gate открыт;
пользовательские файлы не используются. Прежний draft 1.0
с неоднозначными S1/folder/enums не является второй допустимой спецификацией 1.1.
Reader этой редакции не пишет в draft1.0 и не угадывает его правила; допускается
только явное побайтовое копирование без заявления о проверке содержимого.
Автоматическая миграция из не выпускавшегося draft не проектируется.
История и совместимость: [решения проверки](container-review-resolution.md#совместимость-и-существующие-файлы).
Обоснование и Android-протокол: [container-design.md](container-design.md).
Слово «должен» задаёт инвариант будущего writer/reader. Криптопримитивы готовые;
собственная здесь только упаковка данных и протокол транзакций.

## 1. Представление и пределы

Все фиксированные integers unsigned little-endian, кроме значений внутри CBOR.
В Kotlin offsets/length арифметика — checked Long, преобразование в Int только
после проверки верхней границы. Конкатенация ниже обозначена `||`.
KiB=1024 B, MiB=1024 KiB, TiB=1024^4 B. UUID-like ID — сырые случайные 16 B,
recordId — случайные 32 B; ID не зависят от имени и не переиспользуются.
Нулевые ID запрещены. SecureRandom генерирует IDs, ZDK, salt и nonce.

Payload encoding — deterministic CBOR (RFC 8949): definite-length arrays и строки,
shortest integers, без maps/tags/floats/indefinite items/duplicate fields, UTF-8
строго без malformed sequences. Схемы ниже позиционные; неописанные поля v1
не добавляются «по умолчанию». SHA-256 и HMAC считают канонические bytes, а не
объекты после произвольной пересериализации. Перед выделением коллекций проверять
длины, общий byte budget и max nesting 16. Прототип использует готовую CBOR-библиотеку
за валидирующим codec; не десериализует произвольные Java/Kotlin классы.

| Ограничение v1 | Значение и реакция |
| --- | --- |
| Файл | 128 KiB … 1 TiB; больше — UnsupportedLimit до крупных чтений |
| Число зон | 1 или 2; первая main, вторая decoy; zoneId уникальны |
| Header slot | 65536 B, ровно 2 slot |
| Public catalogue page / encrypted metadata plaintext | 16384 B / не более 16384 B |
| Encrypted data chunk plaintext | 1 … 1048576 B; пустое содержимое = пустой список chunks |
| Заметка | CBOR body ≤16 MiB; суммарный text UTF-8 ≤8 MiB; paragraphs/styles ≤100000 каждого |
| Название папки / title заметки | ≤1024 UTF-8 B / ≤100 Unicode code points и ≤400 UTF-8 B |
| Подсказка | ≤512 UTF-8 B, до входа показывается как plain text |
| Вложение | ≤512 GiB; chunk count ≤524288; только streaming |
| Объекты на зону | ≤1000000, включая удалённые в корзине |
| Catalogue entries на зону | ≤16777216; страницы fanout≤128, height≤8 |
| Encrypted B+tree | fanout≤64, height≤8; per-page budget 16 KiB важнее fanout |
| RAM чтения медиа | 1 chunk + metadata page cache ≤8 MiB + постоянные буферы; не зависит от размера файла |
| KDF | Профили §3, один worker; не более двух Argon2 на один submit, последовательно; отдельные current/new submit обслуживания не складываются в одно ограничение |

Это совместимые пределы v1, а не результаты stress/benchmark. Исчерпание лимита
не разрешает writer оставить половину операции. Размеры проверяются до decrypt,
после decrypt ещё раз проверяется схема и согласованность фактической длины.
Нет сжатия payload в v1: нет decompression bomb. Общий traversal budget — не
более catalogue entry limit и object limit, visited-set с ограничением и cancellable
работа по страницам; невозможно бесконечно следовать циклическим offset/ref.
Временные traversal maps ограничены 32 MiB; при превышении использовать
зашифрованный scratch с временным memory-only ключом или вернуть ResourceLimit.
Не выделять весь declared count до чтения. Высота/ключевые границы индексных
страниц дополнительно ограничивают traversal независимо от visited-set.

## 2. Физическая структура

```text
0              superblock A (65536 B)
65536          superblock B (65536 B)
131072 …       immutable AEAD records и public COW catalogue pages
committedLength … EOF   незавершённый хвост/gaps, не часть текущего snapshot
```

Записи/pages начинаются с offset, кратного 8. Padding между ними нулевой,
но не является записью. Смещения нельзя искать сканированием magic в шифротексте:
только через проверенные refs. Значение committedLength включает все достижимые
диапазоны обоих catalogue; может включать gaps и старые недостижимые records.

### 2.1. Superblock, каждый slot

Таблица показывает базовые значения1.1; для нового1.2 меняется только minor по
offset10 на2, как определено P4. Все offsets/sizes/reserved прежние.

| Byte offset внутри slot | Size | Поле |
| --- | --- | --- |
| 0 | 8 | ASCII `CSCRL001` |
| 8 | 2 | containerMajor=1 |
| 10 | 2 | containerMinor=1 |
| 12 | 4 | requiredFeatures=0; неизвестный bit → запрет записи |
| 16 | 8 | generation, 1…2^63−1 |
| 24 | 8 | committedLength |
| 32 | 16 | containerId, неизменен при обычной работе/copy/compact/rekey |
| 48 | 16 | instanceId, новый для compact/rekey, прежний при byte-copy |
| 64 | 1 | zoneCount |
| 65 | 7 | reserved=0 |
| 72 | 4096 | descriptor main |
| 4168 | 4096 | descriptor decoy либо все нули |
| 8264 | 57240 | reserved=0 |
| 65504 | 32 | SHA-256 bytes [0,65504) |

4096-byte descriptor field начинается с u32 CBOR length, далее CBOR, далее
нулевое padding; длина≤4092. SHA обнаруживает случайную порчу/torn write,
НЕ является MAC и НЕ удостоверяет личность writer. Нет общего header key.
generation — счётчик снимков всего файла; zoneSeq — отдельный счётчик зоны.
Нулевой B после создания — не валидный slot. Равные generation допустимы только
при побайтно одинаковых headers (проверенный compact/rekey candidate); иначе CorruptHeader.
Два валидных slot с разными containerId/instanceId — CorruptHeader, не «два контейнера».

### 2.2. Descriptor зоны, CBOR array из 18 полей

| Index | Поле |
| --- | --- |
| 0 | zoneId: bytes16 |
| 1 | role: 1=main, 2=decoy |
| 2 | zoneSeq: uint64, 1…2^63−1 |
| 3 | zoneEnvelopeVersion: 1 |
| 4 | inputMethod: 1=PIN4, 2=PIN6, 3=text, 4=pattern |
| 5 | secretEncodingVersion: 1 (=S1) |
| 6 | kdfId: 1=Argon2id |
| 7 | argonVersion: 19 (=0x13) |
| 8…10 | memoryKiB, iterations, parallelism |
| 11 | salt: bytes16 |
| 12 | wrapNonce: bytes12 |
| 13 | wrappedZDK: bytes48 (ciphertext32 + tag16) |
| 14 | hint: text, допускается пустой |
| 15 | zoneRoot: LogicalRef |
| 16 | logicalMAC: bytes32 |
| 17 | catalogueRoot: PhysicalRef |

`logicalBytes` = deterministic CBOR array из полей 0…15, без MAC и physical
catalogueRoot. `Kdesc=HKDF-SHA256(ZDK, salt=containerId,
info=ASCII("CS-desc-v1\0")||zoneId, L=32)`.
`logicalMAC=HMAC-SHA256(Kdesc, ASCII("CS-logical-v1\0")||containerId||logicalBytes)`.
Сравнение MAC constant-time готовой библиотекой. MAC привязывает текущий root,
role, password envelope и hint; физические адреса намеренно исключены, чтобы
переместить закрытую зону без её ключа. Он не подтверждает глобальный generation
или наличие другой зоны. Как полный replay, так и подстановка старого корректного descriptor/catalogue
одной зоны при актуальной другой не обнаружимы по одному переносимому файлу.
Публичный generation не доказывает свежесть пары зон. Добавление его в оба MAC
потребовало бы чужого ключа при каждой обычной записи и здесь не применяется.
Доверенного внешнего монотонного счётчика нет.

`PhysicalRef = [offset:uint64, byteLength:uint32, sha256:bytes32]`.
`LogicalRef = [recordId:bytes32, rawSha256:bytes32, kind:uint,
ownerId:bytes16, ordinal:uint32]`.
Root имеет ownerId=zoneId, ordinal=0, kind=1. LogicalRef не содержит offset.

### 2.3. Public catalogue, COW B+tree по recordId

Каждая page ровно 16384 B: `CSCAT001` (8 B), level u8 (0=leaf), reserved=0 (3 B),
payloadLength u32, CBOR payload, нули до конца. Hash PhysicalRef охватывает всю page.
Payload = `[zoneId:bytes16, entries:array]`.

* Leaf entry = `[recordId:bytes32, offset:uint64, byteLength:uint32, rawSha256:bytes32]`.
* Branch entry = `[maxRecordId:bytes32, child:PhysicalRef]`; children имеют level−1,
  непересекающиеся строго возрастающие диапазоны; последний max совпадает с последним
  ключом ребёнка. Все ключи сравниваются unsigned lexicographic по 32 B.
* Не более 128 entries/page; leaf может быть пуст только если это пустой root
  (готовая зона всё равно содержит zoneRoot, поэтому её catalogue не пуст).
  Для обычного writer split пополам; после split non-root pages ≥32 entries,
  кроме bulk-built конечной страницы. Reader не требует заполненности для безопасности.

Catalogue содержит ВСЕ committed records зоны, включая устаревшие. Обычный commit
только добавляет новые keys и COW paths, не удаляет старые entries. При selective compact выбранные зоны с отдельно полученными ключами переносятся
по полной достижимости известных схем; невыбранная — всеми entries/records,
без её ключа. Выбор не равен открытой UI-зоне: при decoy-only main невыбранная.
При rekey только целевая зона полностью перешифровывается по достижимости,
другая переносится целиком. Raw records и descriptor[0..16] невыбранной зоны
побайтно прежние; physical catalogue pages/descriptor[17] перестраиваются.
Каталог не является доказательством live set или криптографической полноты
уже подменённого source. Старые snapshots остаются только в source/backup;
неперенос целевых obsolete records при rekey обязателен.

Offset/length только locator: caller сверяет recordId/zoneId/kind, hash из LogicalRef
и AEAD. Подмена публичного locator без ключа не создаёт валидного содержимого.
Любая page имеет границы внутри [131072, committedLength), hash, ожидаемый zoneId,
уровень и лимиты. Для полного copy/compact запрещены повторные keys, пересечения
диапазонов records/pages и циклы. Разрешены совпадающие immutable ranges между
разными поколениями одного snapshot-файла, но не две записи с разными ID на одном range.
Cached mapping/page нельзя считать доверенной без соответствующего root snapshot.

### 2.4. Encrypted record

| Offset | Size | Поле |
| --- | --- | --- |
| 0 | 8 | ASCII `CSREC001` |
| 8 | 2 | recordVersion=1 |
| 10 | 1 | kind: 1=root, 2=index, 3=object/settings/chunk-list, 4=note chunk, 5=media chunk |
| 11 | 1 | flags=0 |
| 12 | 16 | zoneId |
| 28 | 32 | recordId |
| 60 | 12 | nonce |
| 72 | 4 | plaintextLength |
| 76 | plaintextLength+16 | AES-GCM ciphertext и tag |

rawSha256 = SHA-256 всего record, без межзаписного padding.
Заголовок 76 B не содержит objectId или названия; kind и размер открыты.
`Krecord = HKDF-SHA256(ZDK, salt=containerId,
info=ASCII("CS-data-v1\0")||zoneId||recordId||u8(kind)||ownerId||u32(ordinal), L=32)`.
`AAD = ASCII("CS-record-v1\0")||u16(containerMajor)||containerId||header76||ownerId||u32(ordinal)`.
ownerId/ordinal берутся из уже аутентифицированного родительского LogicalRef;
для первого root — из descriptor после проверки MAC. Header bytes и внешние
integers здесь в LE. AES-GCM tag обязателен до отдачи любого plaintext вызывающему.

Для каждого нового шифрования создаются новый recordId (256 случайных бит) и
nonce (96 случайных бит); проверяется отсутствие recordId в каталоге. Ключ record
уникально выводится для этого recordId, а не один GCM-key на весь файл.
Не использовать offsets, wall clock, generation или сбрасываемый счётчик как nonce.
После rollback/kill снова брать случайные ID и nonce, включая ранее неcommitted
операции. Ошибка RNG — отказ записи. Вероятность случайной коллизии не равна нулю;
предел числа записей и 256-битный recordId делают её пренебрежимой в модели v1.
Перемещение неизменённого ciphertext при compact не является новым шифрованием
и сохраняет recordId/nonce/hash. Изменение хотя бы одного plaintext/AAD требует
нового recordId и nonce. Один ID не используется для двух версий блока.

## 3. Пароли, нормализация и envelope

### 3.1. Канонический секрет S1 (secretEncodingVersion=1 в container1.1)

S1 возвращает bytes без типа ввода. inputMethod — UI-подсказка, а не отдельное
пространство паролей; вход кодирует введённый тип и пробует оба current envelope.
Фиксированы **Unicode 16.0.0, NFC по UAX #15 revision56** и таблицы UCD16.0.0:
UnicodeData, DerivedNormalizationProps и CompositionExclusions (включая алгоритм
Hangul). Нельзя использовать меняющуюся системную NFC/Character.isDefined.
Исполнитель должен поставлять эти данные либо доказать точное соответствие
закреплённой версии; при отсутствии normalizer — UnsupportedSecretEncoding,
без KDF, записи и fallback на другую нормализацию.

Для text перед NFC и после неё допускаются только Unicode scalar values,
General_Category которых по UCD16.0.0 не Cn и не Cs; это исключает unassigned,
noncharacters и суррогаты. Private-use Co разрешены. NUL/CR/LF запрещены.
Новые символы, назначенные после16.0.0, не принимаются S1 даже на новой ОС.
Ни trim, ни case-fold, ни NFKC не выполняются. Сначала ограничить raw ввод
1024 code points/4096 UTF-8 B, затем проверить repertoire, NFC и окончательные
1…256 code points/1024 B. Границы относятся к code points, не UTF-16 units.
Malformed UTF-16/UTF-8 отвергается, не заменяется U+FFFD. Проверка repertoire
входа не даёт нормализации превратить недопустимый ввод в допустимый.

| Способ | Каноническое значение S1 |
| --- | --- |
| PIN4/PIN6 | Ровно4/6 ASCII цифр U+0030…0039; UTF-8, ведущие нули сохранены; не число и не локальные цифры |
| Text | Правило UCD16.0.0/repertoire/NFC выше, затем строгий UTF-8 |
| Pattern | Сетка3×3; точки1…9 слева направо сверху вниз;4…9 уникальных принятых точек; ASCII номеров без разделителей |

Golden S1 vectors и отказы: [s1-vectors.json](reconciliation-materials/s1-vectors.json).
В частности U+11382 U+113C9 и U+11383 обе дают UTF-8 `f0918e83`;
U+0378 отвергается. Это намеренное отличие от произвольной NFC старой ОС.
Две независимые реализации с данными16.0.0 должны исполнить эти vectors и полный
[NormalizationTest16.0.0](https://www.unicode.org/Public/16.0.0/ucd/NormalizationTest.txt).
Выбор Android-библиотеки/её conformance — gate P1, правило S1 уже определено.
Нормализация заметок к S1 отношения не имеет.

Для pattern переход через невыбранную среднюю точку автоматически добавляет её
перед конечной (1→3 даёт 123; 1→9 даёт 159). Если конечная точка уже выбрана, весь hit игнорируется до midpoint.
Если средняя уже выбрана — не добавлять; повторное касание выбранной точки игнорируется; конечная уже выбранная не добавляется.
Обрабатывается порядковый список попаданий в точки, а не координаты/скорость/плотность
экрана. Правило midpoint: средняя существует, если обе координаты среднего целые
и лежат на сетке. Конечная последовательность сохраняется только как временный
секрет в памяти, никогда в файле; при проверках UX показывать принятый путь.
Например, [1,2,3,4] → ASCII `1234`, равен PIN4/text `1234`; PIN `0012` равен text
`0012`, отличается от `12`; NFC `e`+U+0301 равен U+00E9. Подтверждение нового
секрета сравнивает S1 bytes constant-time, включая длину, через библиотечный API.

KDF profiles reader v1: memoryKiB ∈ {16384,32768,65536}, t ∈ {2,3,4}, p=1,
Argon2id version19, salt16, out32; m, t, p записаны в descriptor. Не принимать
чужие m/t/p вне whitelist даже при наличии памяти. Не более двух Argon2 на submit,
не параллельно, не перебирать типы/старые headers автоматически. Исходная рекомендация
writer: 65536/3/1 до измерений. Расширение whitelist — новая minor/feature policy,
не попытка выполнить произвольный запрос файла. Профиль не понижать при чтении.

`Kraw=Argon2id(S1, salt, m,t,p,32)`.
`Kwrap=HKDF-SHA256(Kraw, salt=containerId,
info=ASCII("CS-wrap-key-v1\0")||zoneId, L=32)`.
wrapAAD = deterministic CBOR array `["CS-wrap-v1", containerId, zoneId, role,
zoneEnvelopeVersion, inputMethod, secretEncodingVersion, kdfId, argonVersion,
memoryKiB, iterations, parallelism, salt]`.
wrappedZDK = AES-256-GCM(Kwrap, wrapNonce, plaintext=ZDK32, AAD=wrapAAD).
При create/add/rekey envelope получает свежие salt16 и nonce12. При rekey
сам ZDK также новый. Не сохранять Kraw/Kwrap между сессиями. Envelope собственной зоны хранит ТОЛЬКО её ZDK.

При входе пробовать все поддерживаемые envelope последовательно, даже если первый
совпал, чтобы обнаружить запрещённое совпадение обоих. Если открылись два —
AmbiguousCredential, не выбирать main молча. При одном успешном: проверить MAC,
root hash/AEAD, zoneSeq/schema/invariants до Open. Неверный tag envelope неотличим
от неверного секрета или порчи envelope: общее сообщение «Не удалось открыть:
проверьте пароль или копию файла». Не заявлять достоверно «неверный пароль».
Уже подтверждённый ZDK, но неверный MAC/record tag — CorruptData; не показывать
частично расшифрованные данные как исправные. UnsupportedKdf и ResourceLimit —
отдельные ошибки без запуска KDF. Повреждённый/неподдерживаемый второй envelope
не блокирует read-only доступ к исправной зоне, но запрещает изменение паролей.

### 3.2. Смена секрета и предпочтительного ввода

`ChangeSecret(targetZoneId)` доступна только из main через общий протокол
обслуживания design§8. Старый секрет повторно открывает именно current envelope
целевой зоны; ZDK constant-time сравнивается с открытым handle, если он уже есть.
Новый секрет вводится дважды, S1 сравниваются constant-time. Равенство новому S1
прежнего S1 возвращает UnchangedSecret без файлов; это не «быстрая смена».
Новый S1 проверяется против envelope другой зоны: достоверное несовпадение при
понятном поддерживаемом descriptor обязательно. Порча/unsupported/ambiguous
результат блокирует операцию. Main не восстанавливает забытый decoy. Без чужого секрета нельзя доказать
криптографическую исправность его envelope; inequality относится к current
поддерживаемому представлению без обнаруженной порчи, не восстанавливает
повреждённый пароль. Известная порча блокирует операцию.

До резервирования/создания файлов подготовить в RAM независимый случайный ZDK
цели, свежие salt/wrapNonce, выбранные KDF параметры и envelope нового секрета;
ошибка этой подготовки оставляет ноль artifacts. Kraw/Kwrap/S1 после проверки
новой обёртки очистить. Затем verified whole-file backup и один новый контейнер
с новым URI. Перешифровать **весь достижимый граф** цели:
trash/settings/root/индексы/objects/note и media chunks. Для каждого шифрования
новые recordId/nonce, Krecord из нового ZDK; IDs не совпадают ни с source, ни
с уже созданными candidate IDs. Снизу вверх перестроить все LogicalRef/hash,
индексы, root и MAC. Не достаточно заменить ZDK/envelope в descriptor.

Сохранить zoneId, normal/trash root IDs, object IDs, parent edges, содержимое,
порядок styles, свойства, даты, objectRevision, cursor, settings, pin counter и
recentOperations. Изменить только служебный zoneSeq цели на source+1; новый root
содержит тот же seq: это идентификатор новой версии защиты для expectedZoneSeq,
а не изменение содержимого/дат объектов. InputMethod/hint/envelope меняются по подтверждённому запросу.
Другая зона сохраняет ZDK, envelope, logical descriptor[0..16] и все raw records
своего каталога без своего пароля, с новыми physical offsets при необходимости.
containerId остаётся, instanceId новый, global generation source+1 без wrap.

В целевом каталоге кандидата ровно новые reachable records; целевые старые
records/envelopes не копировать. Строить из пустого уникального документа,
не копировать source headers/диапазоны целиком. Обе полностью новые одинаковые
superblock содержат только новый target envelope. EOF=committedLength;
build manifest покрывает все records/pages/нулевые alignment gaps. При ошибке
build не продолжать append с orphan records: отказ всей операции. Проверить
AEAD/ссылки/логическую эквивалентность цели и полную побайтную сохранность другой
зоны, отсутствие target old record IDs и old envelope в обоих slots.

Пароль считается изменённым только после проверки всего кандидата и
подтверждённого долговечного переключения URI. До принятия switch ошибка/cancel
оставляет source и старый пароль; SwitchUnknown разрешается явно по design§8.
Старый ZDK не открывает ciphertext цели в новом файле и её будущих записях
при неизвестном новом секрете. Старые plaintext и копии не отзываются: source
и backup по-прежнему доступны старым паролем; удаления/стирания флеш не обещать.
Rewrap прежнего ZDK как пользовательская смена секрета запрещён.
Неполная/неизвестная reachability schema цели — UnsupportedVersion до backup/candidate.

`SetPreferredInputMethod` меняет только локальное noBackup-предпочтение панели
ввода, не descriptor/envelope/ZDK и не пароль. Оно не конвертирует секрет:
другим методом можно войти лишь при том же S1. Descriptor.inputMethod фиксирует
способ при последнем create/add/ChangeSecret, локальный выбор его перекрывает.
Если изменились S1 bytes, даже вместе со способом, требуется ChangeSecret с
новым ZDK. Отдельного пользовательского «rewrap-only» режима нет.

## 4. Выбор снимка и безопасное открытие

1. Ограниченно прочитать два фиксированных slot; проверить magic/major1/minor1/flags,
   длины, SHA, reserved, zoneCount и descriptor encodings. Неподдерживаемый
   hash-valid более новый major не обходить открытием старого writable snapshot.
2. Выбрать наибольший generation среди hash-valid поддерживаемых slot. Если один
   повреждён/нулевой, другой допустим как crash recovery с уведомлением о header.
   Для неизвестной новой версии только отказ/read-only поддерживаемого экспорта,
   без записи в исходник. Password mismatch НИКОГДА не причина отката header.
3. Проверить committedLength≤actual size, минимальный размер, арифметику refs,
   оба catalogue root и record locator текущих zoneRoot (bounded reads), hashes.
   Hash-valid новейший header с отсутствующим/повреждённым referenced root —
   CorruptData, не тихий fallback. Можно предложить явно открыть прежний snapshot
   read-only и сохранить отдельную recovery-копию; это может вернуть старый пароль
   и данные, поэтому требуется явный выбор, не автоматическое «исправление».
4. KDF/unwrap согласно §3; после MAC открыть root, проверить encrypted schema;
   страницы читать лениво с проверкой перед использованием. Вход не означает
   полную проверку всех гигабайт media: повреждение непрочитанного chunk выявится
   при чтении или отдельной VerifyAll, до публикации plaintext этого chunk.
5. До writable — gate provider/lock/conflict из design. Проверить индексы/дерево
   открытой зоны; на обнаруженной порче не делать «пустой root» вместо неисправного.

### 4.1. Явный RecoverZoneReadOnly

Обычный reader проверяет обе зоны, а при чужой порче предлагает отдельную команду
recovery. Она не запускается автоматически вслед за password mismatch.

1. Пользователь выбирает slot/generation и role зоны. Минимальное общее framing:
   два фиксированных slot доступны, у выбранного SHA верна, magic/major1/minor1,
   flags/reserved поддержаны, generation/committedLength в пределах actual size,
   containerId/instanceId ненулевые, zoneCount1/2 включает выбранную role.
   Для одинаковых generation с разными headers или конфликтующих IDs — отказ,
   не угадывание. Если нет hash-valid поддерживаемого framing — внешняя копия,
   не сканирование произвольных bytes. Старый slot — только отдельный явный выбор
   с предупреждением о старом пароле/состоянии, не тихий fallback.
2. По фиксированному offset выбранной role разобрать **только её** bounded CBOR
   descriptor/padding, проверить zoneId/role/лимиты/KDF и собственные refs.
   Чужой descriptor/CBOR/catalogue не является обязательной зависимостью recovery.
   Нельзя игнорировать порчу общего framing, относящиеся к своей зоне ошибки
   или выход своих refs за committedLength.
3. Изолированно проверить свой catalogue/root locator; unwrap current envelope,
   MAC/root AEAD/seq/schema, затем bounded граф выбранной зоны. Выдавать plaintext
   только после проверки соответствующей записи. Непрочитанный media не называть
   проверенным; отдельный VerifyAll выявляет его порчу. Для неполного дерева
   не создавать пустые objects/root; возвращать CorruptData/UnsupportedVersion.
4. Результат только RecoverySession(readOnly). Никаких T, compact, rekey,
   password change, миграции или записи в source. Разрешена точная отдельная копия
   исходника с маркировкой порчи; не называть её восстановленным здоровым файлом.
   Для записи восстановленных данных нужен отдельный будущий explicit export,
   который эта редакция не объявляет реализованным.

Публичные hashes не удостоверяют полноту уже подменённого чужого каталога.
Удаление зоны, полное или зональное воспроизведение прежнего состояния и порча
недоступных records могут разрушить доступность/свежесть без раскрытия другого ZDK.
Неиспользованные bytes/gaps на «похожие» slots не сканировать.

## 5. Зашифрованный root и индексы зоны

Root plaintext (kind1) — CBOR array:
`[zoneSchemaVersion=1, minReaderZoneVersion=1, zoneSeq,
normalRootId:bytes16, trashRootId:bytes16,
objectsIndex:LogicalRef, childrenIndex:LogicalRef,
settings:LogicalRef, nextPinSequence:uint64, recentOperations:array]`.
normalRoot/trashRoot — разные неизменные IDs виртуальных узлов, не обычные папки.
Обе зоны имеют собственные root, trash, settings и counter. zoneSeq равен descriptor.
При create/add zone zoneSeq=1, nextPinSequence=1, recentOperations=[];
normalRootId/trashRootId генерируются отдельно и различны.
recentOperations ≤256 пар `[opId:bytes16, resultObjectId:bytes16-or-null]`.
Обычная мутация существующей зоны T увеличивает её zoneSeq на1 и добавляет
opId, вытесняя старейший при256. Create/Add инициализируют новую зону значениями
выше; Add не меняет seq/root/main recentOperations.
Coordinator выдаёт opId один раз на immutable request, связывая его в памяти с
sessionEpoch/zoneId/expectedZoneSeq и точным command payload. Caller не меняет
payload под старым opId. Тот же живой token/тот же payload может получить прежний
cached ACK или reconciliation, но не новый побочный эффект. Иной payload —
InvalidOperationToken. После CommitUnknown writer остановлен до reread.

После restart/потери token либо вне окна256 не исполнять прежний request повторно:
UnknownOperation/Conflict и чтение состояния/objectId/revision; для destructive
действия нужна новая проверенная команда с новым opId и новым подтверждением.
Membership recentOperations даёт AppliedHint(opId,resultObjectId), не доказывает
состав старой команды и не восстанавливает полный ACK. Нельзя на его основании
возвращать ложный Committed для нового payload. Дата/счётчики и ID первого draft
не создаются повторно при разрешённом retry. Бессрочного exactly-once нет.
Maintenance opId и исход switching хранятся в локальном journal design§8,
а не в расширении этих пар; rekey сохраняет прежние пары и object IDs.

Индексы — encrypted COW B+tree pages kind2 (ownerId=zoneId, ordinal=0),
payload `[indexType:uint, level:uint, entries:array]`:

* type1 objects: ключ objectId bytes16; leaf value = object LogicalRef.
* type2 children: ключ `[parentId:bytes16, objectId:bytes16]`; leaf value = null.
* В branch entry `[maxKey, child:LogicalRef]`; leaf entry `[key, value]`.
* Keys строго возрастают; уровни уменьшаются; maxKey, split и пределы как у catalogue,
  но fanout 64 и минимальное целевое заполнение writer 16. References kind2 с
  ownerId=zoneId. Отдельные parent/type/name индексы не являются источником истины.

Каталог recordId — физический адресатор, objects/children — логическое состояние.
При открытии writable проверить соответствие children каждому parentId, отсутствие
потерянных objects, циклов и глубину. Для больших деревьев paginated traversal с
ограниченным кэшем; допустима временная зашифрованная рабочая структура, но не
plaintext DB/CSV. При обычной мутации переписывать только изменённые COW paths;
короткая заметка не затрагивает binary media records.

## 6. Объекты, дерево, корзина и настройки

Object plaintext kind3, ownerId=objectId, ordinal=0:
`[objectSchema=1, objectId, type, parentId, name:text, colorArgb:uint32,
iconId:text, pinned:bool, pinSequence:uint64-or-null,
createdAt:int64, contentModifiedAt:int64, metadataModifiedAt:int64,
objectRevision:uint64, payload]`.
Даты — signed epoch millis UTC, не доверенные security timestamps; display по
timezone пользователя. Смена часов может дать более раннюю дату; ordering pin
по counter, не wall clock. type 1=folder, 2=note, 3=image, 4=audio, 5=video.
icon не задаёт type. **Folder payload = null**, всегда CBOR `f6`, не пустой array.
Object defaults: colorArgb=0xFFFFFFFF, pinned=false, pinSequence=null,
objectRevision=1; ID/parent/type/name/dates задаются writer явно, не отсутствуют.
При pinned=true pinSequence обязательно ненулевой uint64 <nextPinSequence;
при pinned=false обязательно null. У folder pin запрещён (false/null).
Цвет uint32 ARGB сохраняет текущие16 ObjectColors без потери знакового Int.

Стабильные iconId case-sensitive ASCII `[a-z][a-z0-9_-]{0,31}`:

| type | Нормативный iconId/default | UI fallback |
| --- | --- | --- |
| folder=1 | `folder`, пользователь не меняет | folder |
| note=2 | `note`; единственный выбираемый icon prototype1.1 | note для неизвестного допустимого ID |
| image=3 / audio=4 / video=5 | `image` / `audio` / `video`, пользователь не меняет | соответствующий type |

Набор рисунков заметок расширяется UI без изменения type; новые стабильные
идентификаторы добавлять в таблицу перед предложением выбора. Для note неизвестный
синтаксически допустимый ID читать и сохранять **как есть** при copy/rekey и
не связанном редактировании; fallback не разрешает переписать его в `note`.
Явное пользовательское действие выбора известного icon может его заменить.
Для fixed folder/media несоответствующий ID — CorruptData в objectSchema1;
неизвестный type/objectSchema — read-only/Unsupported, не угаданная note.

Имя папки не blank по White_Space из Unicode16.0.0; сохранять введённые символы без trim,
после проверки, не нормализовать имена ради уникальности. Совпадающие имена разрешены.
Для note `name` — заголовок, один логический абзац без CR/LF, допускается пустой;
для медиа будущий display name. Нет path-based идентичности, `..` — UI, не object.

parentId либо обычная folder текущей зоны, либо normalRootId/trashRootId.
Normal root depth=0, folders=1…63; note/media могут находиться в folder depth63.
Trash — отдельное дерево вне обычного root, исключено из browse/search/count.
Глубина trash subtree измеряется от виртуального trashRoot depth0 и тоже ≤63.
Перемещение в другую зону запрещено; links/hardlinks v1 отсутствуют.

Массовая операция: дедуплицировать ID; отбросить выбранных потомков выбранного
предка; весь набор валидировать на исходном revision; reject stale/нет destination/
цикл/deleted destination/depth overflow целиком. Parent=self/descendant запрещён;
destination=current parent даёт no-op этому элементу. Для folder проверяется
`destinationDepth + 1 + максимальная относительная глубина подпапок ≤63`.
После успешной проверки поменять все parent edges и children index одним T;
содержимое и ID потомков неизменны. Delete делает то же с parent=trashRoot только
для нормализованных корней, сохраняя вложенную структуру и content dates.

RestoreAll создаёт обычную root-папку `Восстановленное`, при совпадении среди root
folders — минимальное свободное `Восстановленное 2`, `… 3`. Для каждого trash-root
subtree отдельно проверяется глубина: если добавление recovery folder превысит 63,
этот subtree переносится непосредственно в normal root. Остальные — в recovery
folder. Если все overflow, пустую recovery folder не создавать. Имена/ID конфликтовать
не могут (ID прежние, имена дублируются). Пользователю возвращается список overflow
и предупреждение. Всё RestoreAll вместе с опустошением trash — один T.

ClearTrash логически удаляет все объекты под trash и их ссылки; не оставляет
осиротевших children. Достижимые normal objects и вторая зона не меняются.
Ciphertext недостижимых версий остаётся до compact. Перед destructive командами
UI следующего этапа запрашивает подтверждения из концепции, движок не подменяет
этими подтверждениями проверку инвариантов.

Pin: для нового pin взять nextPinSequence, увеличить на1 внутри того же T;
unpin задаёт null, повторный pin получает новую позицию. Сортировка folders прежде
notes/media, затем pinned в порядке sequence asc, затем выбранная сортировка;
objectId — стабильный tie-break. Переполнение counter/revision → Limit, без wrap.
ContentModified меняется только от title/body/todo/style. Color/icon/pin/move/
trash/settings/cursor меняют metadataModified и objectRevision, не contentModified.
createdAt неизменна. Для папки, у которой нет body, name/color/move — metadata.

Settings plaintext kind3, ownerId=zoneId, ordinal=0:
`[settingsVersion=1, rememberFolder:bool=false, lastFolderId:bytes16-or-null,
screenshotsBlocked:bool=true, copyBlocked:bool=true,
sortMode:uint=1, sortAscending:bool=true, viewMode:uint=1]`.
lastFolderId при отключённом rememberFolder=null, при missing/trash ID вход в root.
Никакие настройки второй зоны не копируются как настройки текущей.

Все8 полей обязательны в wire array; defaults применяются при создании, не при
чтении укороченного массива. sortMode/viewMode — uint32, значения:

| Поле | Код | Значение |
| --- | --- | --- |
| sortMode | 1 (default) | По display title/name; note без title использует body, FullyEmpty — вычисляемое пустое название |
| sortMode | 2 | По contentModifiedAt файлов, metadataModifiedAt папок |
| sortMode | 3 | По icon/type, затем display title: note icon IDs в ASCII порядке, далее image, audio, video; folders отдельной первой группой |
| sortMode | 4 | По RGB (`colorArgb & 0x00FFFFFF`), затем display title |
| viewMode | 1 (default), 2, 3 | Список, иконки, миниатюры |

Внутри display-title comparator — lexicographic Unicode scalar values без
нормализации/case-fold; objectId unsigned bytes — последний tie-break.
sortAscending меняет направление выбранного primary/composite ключа, но не порядок
групп folders→pinned files→прочие и не pinSequence asc. В headless fixtures это
воспроизводимый порядок; улучшение локализованной сортировки относится к будущему
UI и должно быть явно согласовано, не менять сохранённый enum задним числом.
Неизвестный uint32 sortMode/viewMode не интерпретировать как известный: отображать
с fallback1, держать raw значение и сохранять при не связанных изменениях.
Только явный выбор пользователем заменяет его известным кодом. Неверный тип/длина,
версия settings≠1 — Unsupported/CorruptData, не default. Полные golden arrays:
[wire-fixtures.json](reconciliation-materials/wire-fixtures.json).


## 7. Rich text v1 и совместимость с редактором

Note object payload = `[noteFormatVersion=1, noteBodyBytes:uint32,
bodyChunks:LogicalRef-array, cursorFocusUtf16:uint32]`.
bodyChunks kind4, ownerId=noteId, ordinal=0…n−1; chunk bytes конкатенируются
строго в этом порядке в CBOR body. Список ≤16 для максимума 16 MiB; фактическая сумма
должна равняться noteBodyBytes. Note body:
`[version=1, paragraphs:array, styles:array]`.
Paragraph = `[text:utf8, checked:null|false|true]`.
StyledRange = `[startUtf16:uint32, endUtf16:uint32,
bold:bool, italic:bool, underline:bool, foregroundArgb:uint32-or-null]`.

Один-в-один отражает `Paragraph`, `TextStyle`, `StyledRange`, `NoteDocument` текущего
проекта. Paragraphs≥1; в text нет LF, paragraphs.joinToString("\n") даёт ровно
NoteDocument.text. Пустые конечные абзацы и пробелы сохраняются. Checked=null
обычный paragraph, false/true todo; checkbox не вставляется Unicode-символом.
Styles сохраняются в исходном порядке, включая перекрытия: текущий renderer
применяет их по порядку, сортировка/слияние может изменить вид. 0≤start<end≤text.length
в единицах UTF-16, включая разделители LF; длина не UTF-8 и не code points.
UTF-16 offsets могут лежать на любой границе code unit, как в нынешней модели;
не пересчитывать их по bytes при загрузке. Текст хранить без NFC-нормализации:
S1 относится к паролям, не к заметкам. Malformed surrogate input перед сохранением
отклонять как ValidationError, не заменять молча на U+FFFD.

Перемещение/selection/scroll не меняют contentModified. На диске только focus
(текущий selectionEnd, включая обратное выделение), после открытия selection
collapsed в этой позиции, с clamp [0,text.length]; anchor/selection/scroll — лишь
memory session для rotation. Недоверенный out-of-range persisted cursor даёт clamp,
не исключение. Смена одного cursor — metadata-only transaction без content date.

Новая заметка существует как draft до первого commit. FullyEmpty = title.length=0,
все paragraph.text пусты, paragraphs.size=1 и checked=null у единственного paragraph.
Иными словами, пробел, LF между пустыми paragraphs или пустой todo делает body
непустым. Только color/icon/pin/style без текста/todo не создаёт новую заметку.
Existing object, очищенный до FullyEmpty, сохраняется. Display title пустого
existing = `(Пустая заметка)`; иначе title или начало body. Эта строка вычисляемая,
не записывается вместо пустого title. Move/delete draft до первого Committed запрещены.

Нынешний NoteData не имеет title/icon/ID/date/cursor в самом DTO. Адаптер создаёт
StorageNote с default title="", iconId="note", устойчивым ID и датами из commit;
NoteDocument не заменяется строкой Markdown/HTML/Parcelable. Этап 1 не добавляет
поле title в работающий UI. Golden round-trip fixtures следующего этапа обязаны
включать emoji, комбинируемые знаки, todo и перекрытия styles.

## 8. Будущие медиа и свободное место

Media object payload = `[mediaSchema=1, mimeType:text≤128B, byteLength:uint64,
chunkCount:uint32, chunkListRoot:LogicalRef]`. Chunk-list pages kind3,
ownerId=objectId, ordinal=0 (recordId различает страницы), содержат
`[level:uint, entries]`; leaf entry `[chunkOrdinal:uint32, LogicalRef]`, branch
`[maxOrdinal:uint32, LogicalRef]`, fanout≤64, height≤8. Список непрерывный с 0,
chunks kind5 ownerId=objectId ordinal соответствующий. Все кроме последнего имеют
ровно 1 MiB, последний 1…1048576 B; пустое media: byteLength=0, count=0, пустая leaf.
Sum lengths совпадает с byteLength. MIME не запускает внешние обработчики до входа.
Base64 нет. Seek offset → ordinal → page → AEAD одного chunk. Не публиковать
частичный plaintext chunk до проверки tag. Media UI/кодеки не входят в прототип.

Свободное место1.1 — не переиспользуемый внутри файла free list. Reclaim selected
зон вычисляется только известным полным traversal с их ключами; unselected
reclaimable неизвестен, её records нельзя перезаписать. Compact byte-copy живых
records и rekey новым шифрованием используют новый файл и checked backup.
Если source S, новая полная backup S, candidate C, итоговые logical file lengths
равны2S+C, прирост S+C, candidate reduction S−C. Удаление одного source оставляет
S+C, что ещё не экономия относительно S. Показывать retained source/backup,
scratch/прежние copies/partial artifacts и различать logical length/allocated bytes;
невозможность узнать физическое allocation не даёт права назвать S−C freed bytes.
C≥S допустим, source/backup не удаляются автоматически.

## 9. Версии и наблюдаемость

containerMajor/minor1.1 определяют эту нормативную упаковку; zoneSchemaVersion
и noteFormatVersion независимы. Version приложения их не меняет. Известны только
requiredFeatures=0, envelope1/S1, record1, zone1/object1/settings1/note1/media1.
Базовый reader1.1 поддерживал строго container1.1; P4 явно добавляет minor2 и
objectSchema2/type6/card1 по [расширению](container-p4-extension.md), без миграции1.1.
Неизвестные остальные minor/major
или required bits запрещают writer **всего файла**, без автоматического отката
к старому writable slot; допустимы отказ или явная точная opaque copy.

Неизвестная zone/object/note/settings/record schema внутри своей зоны делает
её read-only; не подставлять пустое содержимое. Компактируемая/перешифровываемая
зона обязана иметь известную полную ref-closure. Preflight до backup/candidate
обходит её известный граф; если полнота недоказуема — UnsupportedVersion всей
операции. Не переходить автоматически к другой зоне, opaque partial compact или
миграции. Новый выбор пользователь подтверждает отдельно.

Невыбранную зону можно переносить без ключа при понятных descriptor framing,
public catalogue/record framing/границах; opaque plaintext её неизвестной схемы
не интерпретируется. Скопировать весь catalogue record set, raw bytes и logical
descriptor, rebuild только physical pages. Для обслуживания этой редакции
внешние zoneEnvelopeVersion1, secretEncodingVersion1, KDF profile и recordVersion1
должны быть понятны и поддержаны у обеих зон; unknown external framing/MAC policy
никогда не предполагает право менять catalogueRoot. Unknown plaintext zone/note
schema невыбранной при этих условиях переносится целиком. Unsupported другой
envelope также не позволяет честно проверить inequality при ChangeSecret;
допустима отдельная точная raw copy всего файла без статуса healthy VerifiedCopy.
Повреждение каталога/диапазона блокирует обслуживание, не закрепляется новым header.
Полнота относительно source не доказывает исправность уже атакованной закрытой
зоны; об этом предупреждать, подозрительная копия не вытесняет здоровую.

Миграция — отдельная подтверждённая операция с verified backup и известным
переходом схем, не неявная часть compact/rekey. Не изобретать migration fixture
из production1.0, которого нет. Будущая схема может добавить явный стабильный
reference envelope только через отдельную спецификацию и проверку совместимости.

Открыто: header, container/instance/zone/record ID, role/count зон, inputMethod,
KDF/salt/envelope/hint, generation/zoneSeq, catalogue offsets/hashes, kind/длина/
nonce records, размер/рост файла. Закрыто: названия, дерево parent edges, rich text,
todo, цвета/иконки, даты, курсор, settings, действительный live set и содержание
медиа. Наблюдение нескольких snapshot раскрывает изменения физического расположения
и объём работы; трафик и правдоподобное отрицание не защищаются.
Пароли, секреты исходного телефона и открытые заметки в формате отсутствуют.
