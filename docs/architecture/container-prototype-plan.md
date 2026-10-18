# Copper Scroll: задание на прототип контейнера

Статус: следующий отдельный этап реализации. В текущем PR только проектирование.
Следовать [design](container-design.md), [формату](container-format-v1.md) и
[матрице проверок](container-test-plan.md). Сохранить minSdk31, SDK37, AGP9.4.1,
Gradle9.6.0, Java11 и текущую модель редактора. Не заменять рабочий UI демонстрацией.

## 1. Граница прототипа

Сделать headless storage engine, Android FD/SAF adapter и тестовый instrumented
стенд без пользовательских данных. Создание/вход/save/move/trash/password/backup/
compact/migration paths проверяются через API. Media пока только синтетический
binary streaming path; галерея/запись/воспроизведение не реализуются.
Подключение движка к обычному UI — отдельная часть после приёмки headless API,
по отдельной ветке или следующему этапу. Нельзя незаметно вставить дисковую запись
в нынешний Unit `NoteSaveHook` и считать dirty обработанным.

Обязательный минимум прототипа: один реальный `.cscroll`, две независимые зоны,
парольная переносимость, rich text round-trip, T с fault injection, binary chunks,
backup с полным readback, compact открытой зоны без ключа закрытой. Если часть
не готова, этап не объявляется принятым; не выпускать пользовательские файлы
нестабильного формата без обозначения experimental и плана совместимости.

## 2. Модули и контракты

Разместить core в пакетах `com.fantadrom.copperscroll.storage.{format,crypto,vfs,io}`
в текущем app (отдельный Gradle module пока не требуется). Android-specific SAF
изолировать в `storage.android`. Вызовы engine с IO dispatcher, без Compose/View/
Activity внутри codec/crypto/VFS. Single-writer coordinator сериализует команды.
Готовая CBOR-библиотека, например kotlinx.serialization CBOR, за schema codec;
до добавления выбрать опубликованную совместимую с текущим Kotlin версию, проверить
Apache-2.0 notices и deterministic arrays/bytes на golden fixtures. Если библиотека
не обеспечивает required encoding, написать строгий framing adapter, не менять wire
format под библиотеку. Все новые зависимости фиксировать в version catalog.

Планируемые типы (описание API, не готовый исходник):

| Тип/интерфейс | Контракт |
| --- | --- |
| `RandomAccessStore` | size, readFullyAt, writeFullyAt, appendAligned, force, truncateOptional, close; checked Long; full/short IO различимы |
| `SafStoreFactory` | OpenOriginal/Create/ImportCopy раздельно; persist grants, provider gate, FD ownership/locks; не разрешает silent staging |
| `ContainerReader` | readHeaders, selectSnapshot, supportedVersions, openZone, VerifyAll; никакой записи при попытке входа |
| `ZoneSession` | Один ZDK и epoch; скрывает raw key; suspend mutate(expectedRevision, opId); close с отменой доступа |
| `ContainerWriter` | Протокол T; closed descriptor opaque; append immutable pages/records; ACK после force/readback с удержанием lock |
| `ZoneRepository` | get/list/saveNote/createFolder/moveBatch/trashBatch/restoreAll/clearTrash/settings; транзакции через writer |
| `CredentialManager` | S1/Argon2/unwrap/old-check/equality/new-wrap, без общего двухзонного verifier |
| `SnapshotCopier` | Writer barrier; уникальная копия, streaming hash/readback; return VerifiedCopy либо ошибка |
| `Compactor` | Verified backup до работы; open reachability + closed full catalogue; returns VerifiedCandidate(newUri, instanceId), source retained |
| `SessionCoordinator` | NoContainer/Locked/Open/Saving/Closing/Error и picker tokens; UI epoch отделён от writer lifetime |
| `EditorStorageAdapter` | NoteData/NoteDocument↔StorageNoteDTO; snapshot revision, cursor focus; никаких spans/HTML/Base64 |

`MutationResult`: `Committed(generation, zoneSeq, revision, objectId)` /
`NoOp` / `ValidationError` / `Conflict` / `ResourceLimit` / `AccessLost` /
`IoFailure` / `CommitUnknown(lastConfirmed, attemptedOpId)` /
`CorruptData` / `UnsupportedVersion`. Не возвращать успешный Boolean вместо
неоднозначного IO outcome. Расшифрованные payload/key не включать в toString/errors.

`Credentials` — ограниченный memory-only буфер; explicit destroy в finally,
не data class с автоматически печатаемыми полями. ByteArray очищать, lifecycle
managed String не обещает полного стирания RAM. KDF ограничен профилями, один worker.
Cancellation до commit прекращает запись; после начала header update требуется
разрешение исхода через reread, не ответ «отменено, точно ничего не изменилось».

## 3. Порядок реализации и выходные критерии

### P1. Golden codec и parser

Реализовать byte layouts header/descriptor/record/catalogue, CBOR схемы, refs и
пределы формата. Добавить небольшие фиксированные hex/binary vectors и независимый
инспектор структуры без ключей. Checked arithmetic и ресурсные бюджеты — до crypto.
Выход: malformed corpus/fuzz не приводит к OOM/циклам; round-trip сохраняет bytes
канонического формата; offsets/lengths layout совпадают с таблицами спецификации.

### P2. Криптография и доступ

Зафиксировать и проверить BC artifact (кандидат1.86 из design) на API31/release R8,
не регистрировать provider; JCA AES/GCM. Выполнить known-answer vectors Argon2id,
HKDF, HMAC, AES-GCM. Реализовать S1 и pattern midpoint, оба независимых envelope,
MAC и record AAD, RNG failure, key cleanup и ambiguous match.
Выход: KEY/CRYPTO P0; один файл переносим на другой instance/device без дополнительных
секретов. Действительные KDF benchmarks отдельным отчётом, не хардкод «оптимально».

### P3. Транзакционное ядро

Сначала JVM FileChannel/fault store, потом Android FD. Реализовать два slots,
snapshot selection, append+COW+force+header+force+readback, опId/revisions,
CommitUnknown, immutable каталог, bounded page cache. Метаданные root/каталога
проверять прежде публикации сессии. Удаление записей внутри старого файла запрещено.
Выход: TX/FILE P0 на каждой фазе T, без ошибочного Committed. Rewrap меняет envelope
и descriptor, а также создаёт маленький новый root с увеличенным zoneSeq;
note/media records и ZDK остаются прежними. Это не перешифрование пользовательских данных.

### P4. VFS и редакторный codec

Objects/children encrypted B+trees; ID, root/trash, глубина, batch normalization,
контроль cycles и parent invariants; папки/settings/pin counter. Сериализация
NoteDocument из существующего кода, отдельный StorageNoteDTO с title/icon/dates,
body chunks, focus UTF-16, empty rules. Не изменять семантику checked/null/styles.
Выход: NOTE/VFS/TRASH P0/P1, randomized commands с независимой model oracle;
save короткой note не меняет ciphertext существующих вложений.

### P5. Copy, compact, versions

VerifiedCopy со stable snapshot и уникальным именем, retention policy; compact
в новый документ без перезаписи исходного. Не требовать ключа закрытой зоны,
сравнивать всё её catalogue records/hash set. Journal/noBackup содержит только
URI/opId/стадию. Добавить migration dispatcher для известных версий и тестовую
legacy fixture; не изобретать миграцию из несуществовавшего production формата.
Выход: COPY/COMPACT/RESTORE/MIGRATE P0, candidate reopen; безопасное восстановление
после crash вокруг локального переключения URI. Нет автоматического удаления source.

### P6. Android adapter и security lifecycle

SAF read/write modes, probe отдельного scratch, grant loss/reselect, local provider
profile. Настроить manifest backup и оба XML до работы с реальными данными;
проверить cloud backup и device transfer. Сессия memory-only, background/screen-off
hide-first, writer closing/timeout, rotation и picker token cancellation.
Пока provider profile не доказан, запись через него недоступна; test-only adapter
не выдаётся за production поддержку произвольного SAF provider.
Выход: SAF/LIFE/LEAK P0 и измеренный список поддержанных authority/version, даже
если он пока содержит только один локальный provider.

## 4. Контракт будущего подключения к UI

Нынешний `NoteSaveHook.onSaveRequested(NoteData, SaveReason): Unit` не способен
передать async commit result. При отдельном подключении ввести request snapshot
с noteId/revision/title/icon/cursor и async acknowledgement; период 7 с оставить.
Снять dirty только для ACK revision, ошибка не закрывает редактор с сообщением
об успехе. Для явного закрытия дождаться outcome или показать ошибку, для ухода
в фон немедленно скрыть UI и закрыть сессию после ограниченного writer completion.

Не восстанавливать plaintext через SavedStateHandle/Bundle/rememberSaveable;
rotation использует ViewModel. Сохранённый cursor находится внутри note, на
системный диск selection не сериализуется. Input title/date/unsaved indicator
добавлять по концепции при отдельной интеграции, не в docs-only PR.
Нужно будет обновить нынешние тесты, которые ожидают немедленного сброса dirty
после hook: они подтверждают старое session-only поведение, не надёжность IO.

В main settings изменение decoy требует его старого секрета, хотя command пришёл
из основной сессии. Забыт decoy — отказ без reset. Из decoy UI команды смены
паролей и ручного backup скрыты согласно концепции; compact в разделе корзины
доступен и decoy, с обязательной проверенной копией без основного пароля.
Core остаётся симметричным для тестирования независимости. До входа безопасные
настройки и hint доступны, trash/zone settings — нет. Для compact показывать
новый путь и retained source.

## 5. Перед передачей и PR

Работать в новой ветке от принятой архитектуры. Делить реализацию по P1…P6 на
проверяемые коммиты, не менять SDK ради обхода несовместимой зависимости. Если
нужна поправка формата, сначала обновить документы и fixtures с описанием
совместимости, не создавать два разных «v1».

Для Kotlin/manifest/dependencies изменений: собрать debug, unit tests, lint и
instrumented tests на доступном API31+; проверить release/R8 crypto path. Команды
адаптировать к реальному runner, не записывать «успешно», если устройства нет.
Прогонять релевантную часть матрицы и сохранять safe reports/KDF/provider metrics
в `docs/architecture/validation/`, без секретов. Долгие device/security gates
явно перечислять как открытые до release, не скрывать за успешными unit tests.

Проверить итоговый diff, коммитить/отправить отдельную ветку, создать PR без merge.
PR описывает фактическое поведение и проверки; переносимость, две зоны и crash
handling подтверждаются результатами матрицы, а не текстом этого плана.
