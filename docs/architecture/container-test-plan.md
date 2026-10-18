# Copper Scroll: план проверок контейнера

Статус: согласованный план для container1.1, редакция2; задания следующих этапов. Перечисленные проверки движка НЕ выполнены
в этапе 1. Проверка Markdown/diff в отчёте не должна называться тестированием
контейнера. Нормативные ожидания заданы [форматом](container-format-v1.md)
и [протоколом T](container-design.md#5-единая-транзакция-записи-t).

## 1. Стенд и критерий приёмки

Два слоя: чистый JVM codec/engine на RandomAccessStore с fault injection и
instrumented API31+ адаптер FD/SAF. Crypto tests используют опубликованные vectors
Argon2id/AES-GCM/HKDF/HMAC, а не только encrypt→decrypt той же реализацией.
Псевдо-RNG допускается только в тестовых fixtures; production SecureRandom не
подменяется детерминированным seed. Все fixtures синтетические, не реальные пароли.

Oracles: полный обход обеих зон с известными тестовыми паролями, сравнение
логического snapshot с независимой простой моделью, reachability, отсутствие
циклов, expectedRevision и SHA-256 необработанных ciphertext закрытой зоны.
Проверки должны обнаруживать ошибки locator/AAD/индексов независимо от UI.
Записывать seed, точку сбоя, result code, поколения, размеры и hashes, не тексты
или ключи. CI-сборка с тестовыми контейнерами не должна поставлять их как demo assets.

Приёмка прототипа: все P0, P1 correctness; воспроизводимые crash/fuzz результаты;
данные KDF/device/provider замеров; ни одного ошибочного Saved; ни одного
необъяснённого изменения закрытой зоны. Production выпуск дополнительно требует
независимого security/format review. Для docs-only этапа Android build не нужен.

## 2. Функциональная матрица

| ID / приоритет | Сценарий | Наблюдаемый результат |
| --- | --- | --- |
| KEY-01 P0 | Создать main-only, закрыть, открыть на втором чистом устройстве/после reinstall | Один `.cscroll` и main secret достаточны; нет Keystore/device secret зависимости |
| KEY-02 P0 | Добавить decoy, отдельно открыть каждым паролем; искать ссылки на другой ZDK в доступном plaintext | Открывается только соответствующая зона; main не восстанавливает забытый decoy |
| KEY-03 P0 | Последовательно mutate main/decoy с полным закрытием между ними | Состояние второй зоны и её logical descriptor не меняется; normal работа не требует второго пароля |
| KEY-04 P0 | ChangeSecret main и decoy из main, current target verification, новый секрет дважды | Новый независимый ZDK и новый URI; старый ZDK не открывает обновлённую цель/будущие данные, другая зона прежняя; полный oracle REKEY |
| KEY-05 P0 | Неправильный old secret; совпадающий new secret; забытый decoy; повреждённый второй envelope | Никакого commit/reset; нет восстановления decoy основным; equality-check fail безопасен |
| KEY-06 P0 | PIN4 `0012`, PIN6 `000012`, text `0012`; text NFC composed/decomposed, пробелы/case | Ведущие нули и S1 точны; равные канонические secrets запрещены независимо от метода |
| KEY-07 P0 | Pattern переходы1→3,1→9, уже выбранная середина/конец, разные screen density | Одинаковая итоговая последовательность;4…9 уникальных точек; совпадение pattern1234/PIN1234/text1234 запрещено |
| KEY-08 P1 | SetPreferredInputMethod и ввод другим способом с прежним S1 | Меняется только local noBackup preference, descriptor/ZDK/ciphertext прежние; new S1 требует ChangeSecret, rewrap-only не предлагается |
| KEY-09 P0 | Два envelope в fixture принимают один secret | AmbiguousCredential; нет автоматического выбора main |
| KEY-10 P0 | Случайные/подменённые m/t/p, salt length, kdfId/version | Отказ до KDF/выделения памяти; не больше двух последовательных KDF на submit |
| CRYPTO-01 P0 | Перенести ciphertext в другую зону/container/object/ordinal/kind; подменить nonce/header/ref | MAC/hash/AEAD rejection; plaintext не опубликован |
| CRYPTO-02 P0 | Повторить save после rollback/kill и RNG failure; наблюдать новые IDs/nonce/key domains | Новое шифрование не переиспользует пару key/nonce; RNG failure не публикует commit/switch; ID collision отвергается |
| CRYPTO-03 P1 | Проверка hint до входа/смена после входа/порча hint | Открытое предупреждение; plain text rendering; MAC выявляет порчу после unwrap |
| FILE-01 P0 | Перенос всего файла при одной/двух зонах, без sidecars/локальных prefs | Обе зоны доступны своими паролями; переносимый файл самодостаточен |
| FILE-02 P0 | Усечение на каждом offset header и каждой границе record/page, повреждение новейшего slot | Только согласованный старый/новый snapshot по правилам recovery, либо явная ошибка порчи; никаких пустых замен |
| FILE-03 P0 | Hash-valid header с неверным referenced root; старый header ещё исправен | CorruptData; старый snapshot только отдельным read-only recovery, не тихий rollback |
| FILE-04 P0 | Minor0 draft, неизвестный minor/major/required bit, unknown inner schema | Writer всего неизвестного container запрещён; unknown своей зоны read-only, selected compact/rekey отказ до backup; unselected opaque только при известном внешнем framing/MAC relocation contract |
| FILE-05 P0 | Malicious offsets/overflow/overlap, cyclic pages, огромное fanout/depth/nesting, invalid CBOR | Бounded отказ без OOM, бесконечного traversal или выхода за FD range |
| FILE-06 P1 | Последний chunk испорчен в большом media | Вход не заявляет полной VerifyAll; ошибка при чтении chunk, его plaintext не отдаётся |
| VFS-01 P0 | Root0, цепочки folder1…63, создание/перенос на64, subtree с descendants |63 принимается,64 целиком отвергается; note/media в folder63 разрешены |
| VFS-02 P0 | Self/descendant move, parent+child selection, duplicates, destination=parent | Нет циклов; parent+child переносится один раз; no-op не меняет даты |
| VFS-03 P0 | Batch из валидных и невалидных элементов; stale revision | Вся группа применена одним T или ни одного изменения |
| VFS-04 P1 | Дублирующиеся имена, Unicode whitespace folder, изменение цвета/icon/type | ID стабилен; имена дублируются; blank folder rejected; icon не меняет type |
| TRASH-01 P0 | Delete нескольких subtrees, ClearTrash, restart | Структура сохраняется в отдельном trash; browse/search не включают его; main и decoy раздельны |
| TRASH-02 P0 | RestoreAll: обычное дерево, дерево высотой63, mixed, existing recovery names | Корректное имя recovery; overflow subtree в root с уведомлением; один T и пустая trash |
| NOTE-01 P0 | Golden codec fixtures текущего NoteDocument | Paragraphs/todo/styles и их порядок восстановлены без потерь; не spans/HTML |
| NOTE-02 P0 | Emoji, combining marks, supplementary characters, trailing LF, пустые todo, overlapping styles | UTF-16 ranges точны; text не NFC-нормализуется; characterCount совпадает с текущей моделью |
| NOTE-03 P0 | Новый полностью пустой; пробел; LF; todo пустой; существующий очищенный | Только новый FullyEmpty не создаётся; existing ID сохраняется; display empty title вычисляемый |
| NOTE-04 P1 | Title100/101 code points, CR/LF, oversized body, malformed surrogate | Валидировать до записи; отказ не сбрасывает dirty; ни тихой обрезки, ни replacement chars |
| NOTE-05 P0 | Revision N сохраняется, во время IO появляются N+1 и повторный запрос N | Dirty N+1 остаётся; ACK только N; после ошибки/retry нет дубля первой заметки |
| NOTE-06 P1 | Cursor, reversed selection, scroll, color/pin/move; title/todo/style edit | На диске focus=selectionEnd; content date только content edits; cursor clamp безопасен |
| MEDIA-01 P1 | Stream synthetic >RAM media, read beginning/middle/end; edit маленькую note | Bounded buffers; binary без Base64; старые media record IDs/hashes не меняются |
| MEDIA-02 P1 | Chunk list gaps/duplicates/wrong count/length/ordinal, byteLength512GiB+1 | CorruptData/Limit до огромной аллокации; нет ложного EOF или перепутанных chunks |

## 3. Прерывание транзакций и IO

Для create/add zone/save/move/delete/restore/clear/settings/hint выполнить
общий fault-runner. Для каждой операции исходный snapshot S0, ожидаемый S1;
сравнить после перезапуска с S0 или S1, никогда со смесью. Create до первого commit
может дать только незавершённый документ, main-файл при add всегда сохранён.

Fault points: перед/после каждой append, частичный write по каждому byte для
малых fixtures, на границах8B/4KiB/16KiB/64KiB для больших; short write без ошибки,
EIO/ENOSPC/EDQUOT, перед/после fsync данных, при pwrite inactive header, fsync header,
readback, reopen, доставке ACK. Отдельно faults после реально сохранённого commit,
но до ACK: результат CommitUnknown и idempotent retry, не новая заметка-дубль.

| ID / приоритет | Проверка | Ожидание |
| --- | --- | --- |
| TX-01 P0 | Каждый fault point T, включая torn header и tail | Старый/новый согласованный snapshot; записанные orphan blocks не видны |
| TX-02 P0 | Short read/write, interrupted syscall, failure fsync, ошибка readback | Loop/ошибка по контракту; Saved не выдаётся при неизвестной долговечности |
| TX-03 P0 | ENOSPC при chunks/COW/root/header, потеря URI grant, provider death | Последний Committed доступен при исправном носителе; безопасный Error/CommitUnknown, dirty не скрывается |
| TX-04 P0 | Kill процесса и перезагрузка устройства на границах протокола | После reopen старый/новый snapshot; нет зависимости от plaintext temp/sidecar |
| TX-05 P0 | Одновременные writers в двух процессах, другая копия приложения, внешнее изменение header/size | Сотрудничающий lock исключает второй writer; обнаруженное external change → Conflict; границы внешнего writer задокументированы |
| TX-06 P1 | Хвост от неcommitted T, truncate поддержан/не поддержан | Игнорируется по committedLength; новый write не оживляет чужой tail |
| TX-07 P0 | После rekey source ещё имеет old envelope, candidate оба slot новые; password mismatch в candidate | Не искать old slot/исходный URI по неверному паролю; оба headers candidate содержат только новый envelope. Явный выбор source/recovery предупреждает о прежней защите |

JVM модель `force()` должна различать volatile/durable ranges и потерю
несинхронизированных bytes; тестировать произвольный torn write inactive header.
Это проверяет алгоритм в заданной модели хранения, не доказывает power-loss
поведение реального телефона. Для него нужны device/provider испытания отдельно.

## 4. Backup, migration и compact

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| COPY-01 P0 | Copy под barrier известного outer framing, independent reader обеих fixture zones | Snapshot committedLength, known zones открываются обоими secret; unknown plaintext unselected сохранён raw и проверяется oracle, знающим эту synthetic schema, не старым reader |
| COPY-02 P0 | Short copy/искажение byte/нехватка места/закрытый grant при readback | Копия не verified; source и прежние verified copies не удалены |
| COPY-03 P1 | Имена совпадают, clock назад, retention при трёх копиях/нехватке места | Уникальный suffix; новая копия не overwrites old; удаление только после новой проверки; relative-only copy не вытесняет последнюю более полно AEAD-проверенную |
| COPY-04 P0 | Source уже повреждён в закрытой зоне | Byte equality не называется AEAD-верификацией закрытой зоны; подозрительная копия не вытесняет здоровую |
| COMPACT-01 P0 | Удалить main notes; main-authorized выбор только main, decoy явно снята | Main live set с trash/settings; decoy key не спрашивается, все её recordId/hash/logical bytes прежние, позже открывается своим паролем |
| COMPACT-02 P0 | Из decoy UI и direct dispatch/deep-link/stale capability вызвать compact | Denied до picker/KDF/reservation/backup/candidate; нет запроса main secret или перехода в обслуживание |
| COMPACT-03 P0 | Kill/ENOSPC/short IO при backup/build обеих зон/между ними/verify/reopen/switch | Один S/backup/candidate, ни промежуточного URI, ни partial success; source сохранён; Unknown/recovery по MAINT |
| COMPACT-04 P0 | Mismatch unselected multiset/logical bytes, selected AEAD/ref failure, нет verified backup | Отказ всего результата до AcceptSwitch, source binding прежний, нет усечённой зоны |
| COMPACT-05 P1 | S/C при main/decoy/both и C<S,C=S,C>S; сохранены source+backup | S−C отдельно от2S+C и прироста S+C; удалить один source недостаточно; unknown allocated bytes не называть freed |
| COMPACT-06 P0 | Main-authorized decoy-only с текущим decoy secret до файлов | Main unselected копируется целиком; helper не получает её ZDK, хотя coordinator авторизован main. Decoy UI не открывается |
| COMPACT-07 P0 | Обе зоны существуют и выбраны по default, обе содержат мусор | Один verified backup, один candidate, две полные reachability/AEAD проверки и один switch; keys независимы, zoneSeq прежние |
| COMPACT-08 P0 | Main-only файл, пустой выбор, main/decoy/both selection | Decoy отсутствует в выборе, empty запрещён; невозможный выбор не создаёт файлы |
| COMPACT-09 P0 | Wrong/cancelled/forgotten decoy secret; в prompt decoy введён main secret; unsupported/KDF OOM | Нет reservations/backup/candidate; нет auto main-only. Возврат к выбору и снятие decoy только явно с новым подтверждением |
| COMPACT-10 P0 | Симметричный low-level copier fixture обеих зон, без key невыбранной | Полный raw multiset/logical descriptor сохранены; test не разрешает пользовательский запуск из decoy |
| MIGRATE-01 P0 | Migration известной старой zone/note версии при закрытой второй | Сначала verified backup; мигрирована только открытая; другая preserved opaque |
| MIGRATE-02 P0 | Неизвестная более новая версия, crash migration, нехватка места | Нет модификации неизвестного формата; прежний snapshot/copy доступен |
| RESTORE-01 P0 | Restore в отдельный файл с тем же containerId; исходник активен | Новый URI tracked отдельно; исходник не перезаписан, не перепутан по containerId |

Compact tests сравнивают обе logical snapshots, весь raw recordId/hash multiset
невыбранной и позднее independent open. Main secret не передаётся keyless helper
COMPACT-06/10; main-authorized coordinator и отдельный maintenance context могут
владеть своим main handle. Это не один dual-zone UI login. Ciphertext compact
не перешифровывается; REKEY, наоборот, меняет все target ciphertext.

### 4.1. Замена секрета с новым ZDK

Для каждого теста target=main/decoy, main-only/two-zone, note/trash/settings/media
и retained source/backup. Synthetic old/new secrets известны независимому oracle.

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| REKEY-01 P0 | Сохранить старую копию, извлечь old ZDK, ChangeSecret | Target новый независимый ZDK, новый URI; snapshot content/IDs/props/dates/objectRevision/cursor/settings/counters прежние, только zoneSeq+1 |
| REKEY-02 P0 | Offline reader с old copy/secret/ZDK проверяет все новые target records; затем добавить новую note/media | Ни один target tag не проходит old ZDK, и будущие records тоже; новым ключом полный round-trip. Старую копию по-прежнему можно открыть старым секретом |
| REKEY-03 P0 | Inspect оба candidate superblocks и полный build manifest/каталог/EOF | Оба одинаковы, только новый target envelope, нет старых target record IDs/obsolete records/старых source headers; EOF=committedLength, gaps нулевые; other logical descriptor/raw records прежние |
| REKEY-04 P0 | Unknown target schema/ref, wrong old target, main secret вместо decoy, new same-own S1/равен другой зоне, неверный repeat, RNG failure подготовки нового ZDK/envelope | Полный отказ до backup/candidate, без reset/rewrap/частичного исполнения; UnchangedSecret отдельно от успеха |
| REKEY-05 P0 | Большой binary/trash/settings граф, old source имеет много obsolete | Все reachable зависимости заново зашифрованы bottom-up с новыми refs/IDs/nonce; нет truncation/старых envelope; bounded buffers/scratch |
| REKEY-06 P0 | Fault на каждом шаге current/new KDF, backup, chunks (включая поздний RNG failure), refs/index/root, header/force/verify/URI switch/ACK | До AcceptSwitch source со старым секретом; после — доказанный old/new binding либо SwitchUnknown, без ложного Changed. Source/backup не удалены |
| REKEY-07 P0 | Забытый decoy, неподдержанный/повреждённый другой envelope, прямой вызов из decoy; new-secret inequality | Нет main recovery забытого decoy, операция запрещена; other key неизвестен helper и не меняется |
| REKEY-08 P1 | Смена только preferred input, same S1 разными методами; затем иной S1 вместе с методом | Первая без изменения container, вторая обязательно полный ChangeSecret; ни отдельного rewrap-only, ни смены защиты без durable ACK |

### 4.2. Общий maintenance и journal

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| MAINT-01 P0 | Direct dispatch из decoy/Locked, stale epoch | Denied первым действием до picker/KDF/artifacts; нет запроса основного пароля |
| MAINT-02 P0 | Destinations не настроены, picker valid/cancel/late result, rotation | Сначала revoke; valid grant только настройка Locked, новая main-команда/credentials/confirm; старое обслуживание не продолжается, rotation не создаёт второй worker |
| MAINT-03 P0 | Pending save/CommitUnknown перед freeze, конкурентные save/ChangeSecret/add/migration после S | Drain/reconcile либо отказ; exclusive lease весь backup/build/verify/switch; ни одной поздней подтверждённой правки в старом source |
| MAINT-04 P0 | Ошибка второй selected зоны, final verify, reopen, source/candidate изменён после проверки | Нет частичного результата; source/candidate fingerprint+lock checked; stale source → Conflict до switch |
| MAINT-05 P0 | Revoke до AcceptSwitch, в том числе check→Home→switch | Сериализатор запрещает принятие switch, old binding; никакой поздний callback не принимает его заново |
| MAINT-06 P0 | AcceptSwitch раньше revoke, blocking durable-state IO, kill/ошибка ACK | Уже принятое решение может стать durable old/new; честный Unknown/Unreported при Locked, нет скрытого открытия UI/ложной отмены; explicit reconciliation |
| MAINT-07 P0 | Crash на каждой durable stage Prepared…SwitchIntent…Activated, corrupt journal/checksum | Полная старая/новая metadata запись; проверка файлов/grants/profile/freshness и явное решение, не выбор mtime/имени; journal flag не credential |
| MAINT-08 P0 | Restart после уничтожения service keys при candidate rekey | Не возобновлять по сохранённым ключам; для проверки candidate нужен новый secret, для source — старый; показать разные URI/защиту, не повторить операцию по одному opId |
| MAINT-09 P0 | Cancel/error/OOM/KDF/traversal/verify, две зоны или old/new target keys, поздние closures | Stop→Quiescent→Released, owners/borrowed handles/cache/scratch очищены, ни plaintext/keys в logs/state/temps/UI |
| MAINT-10 P1 | Имена/папки/тома source-backup-candidate отличаются, недостаточно места и retention | Уникальные документы, отдельные grant/profile, один новый backup/candidate на op; прежние здоровые не удалять ради продолжения |

### 4.3. Recovery, схемы и retry

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| RECOVERY-01 P0 | Current main исправна, decoy catalogue/root повреждены | Явный RecoverZoneReadOnly current slot main: свой MAC/root/AEAD/seq верны, чужие refs не нужны; source immutable |
| RECOVERY-02 P0 | Чужой descriptor invalid CBOR при hash-valid общем framing; симметричный fixture | Изолированный разбор только selected fixed field; свой bounded descriptor и common prefix обязательны |
| RECOVERY-03 P0 | Общий SHA/magic/IDs/length неверны либо свой root/AEAD плох | Отказ/внешняя копия, не scan/пустой root. Password mismatch не old-envelope fallback; old snapshot только явно с предупреждением |
| RECOVERY-04 P1 | Healthy login, последний непрочитанный media испорчен; попытка mutate/compact из recovery | Никакого writer; chunk plaintext не отдаётся до tag, VerifyAll не смешивается с lazy login |
| SCHEMA-01 P0 | Статические folder/settings/icon fixtures двумя независимыми codec/inspectors | Folder payload f6, defaults/enum mapping точны; null≠array, wrong types/array lengths rejected |
| SCHEMA-02 P0 | Unknown selected note/object/zone/record с непонятными outgoing refs; та же unselected | Selected/rekey UnsafeTraversal→Unsupported до backup; unselected raw preservation только при known outer framing, без partial fallback |
| SCHEMA-03 P1 | Unknown допустимые note icon/sort/view enum, не связанное edit/copy/rekey, явный новый выбор | UI fallback не заменяет raw value; explicit choice может заменить, fixed folder/media icon/type валидируется |
| RETRY-01 P0 | Один живой immutable token повторён с прежним/иным payload | Без повторного эффекта; прежний cached ACK/reconciliation, изменённый payload InvalidOperationToken |
| RETRY-02 P0 | Window256 вытеснен, process restart/token потерян, destructive/password/settings повтор | UnknownOperation/Conflict; AppliedHint не полный ACK, новая команда после просмотра состояния с новым opId/expectedZoneSeq |
| RETRY-03 P0 | Commit сохранился до ACK, повтор первой note/folder | Стабильный objectId/даты/counter не создаются заново, N+1 dirty сохраняется; maintenance op отдельно в local journal |
| ROLLBACK-01 P1 | Старый descriptor main+catalogue при актуальной decoy, публичный SHA пересчитан | MAC/AEAD могут быть валидны; reader не заявляет свежесть/anti-rollback, независимость ZDK сохранена |

## 5. Android, lifecycle и отсутствие утечек

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| SAF-01 P0 | Local tree create, OpenOriginal, ImportCopy, отдельный backup tree | Разные явные режимы/URI; grants ровно выданные; импорт не меняет source |
| SAF-02 P0 | Pipe, virtual/cloud provider, rw без seek/fsync, неизвестная authority, только read grant | Read-only или отказ до edit; нет скрытой staging-копии/обещания atomic replace |
| SAF-03 P0 | Move/remove file, revoke grant, reinstall, provider version change | Повторный выбор и повторный gate; никакого автосоздания пустого вместо пропавшего |
| LIFE-01 P0 | Home/screen-off/lock в Open и Saving, возврат до завершения Closing | UI немедленно скрыт; новый ввод блокирован; возврат Locked, IO outcome честный |
| LIFE-02 P0 | Rotation portrait/landscape, keyboard/dialog/selection open | Тот же memory session, пароль повторно не нужен; нет SavedState plaintext |
| LIFE-03 P0 | Cancel picker, Home/screen-off/kill до позднего RESULT_OK | Token invalid; поздний URI не импортируется/не выбирается, grant не сохраняется |
| LIFE-04 P1 | Picker из Locked и явная команда выбора из main | Предварительное закрытие main; штатный результат только активного operation token |
| LEAK-01 P0 | Inspect logs/preferences/Bundle/ViewModel saved state/noBackup/work temps/crash payload | Нет note/title/secret/keys; только допустимые URI/opId/коды ошибок |
| LEAK-02 P0 | Screenshots/recents/content capture/autofill/clipboard до входа, после main/decoy, при Closing | FLAG_SECURE до входа/на блокировке; zone policy после входа; clipboard restrictions проверены и в native EditText |
| LEAK-03 P0 | Android backup/restore и OEM device transfer после настройки manifest/XML | App managed content/settings/temps не извлекаются; общий SAF файл отдельно и это явно описано |
| LIFE-06 P0 | Неотменяемый pwrite/fsync/readback/Argon2 дольше grace, timeout/close другого потока | UI Locked быстро; syscall не считается завершённым; owner/lock/lease живы до quiescence, второго writer нет, ресурсы закрыты ровно раз |
| LIFE-07 P0 | Два RESUMED окна, потеря top-resumed/focus, notification shade, permission dialog | Revoke по таблице design§6.1, один onStop не oracle |
| LIFE-08 P1 | Own registered dialog/menu/IME, rotation/resize handoff; Home/screen-off во время него | Свои окна не создают ложный revoke, configuration не новая operation; Home/screen-off всегда revoke |
| LIFE-09 P0 | Последний ClosingSave при revoke, late callback, повторный вход | Только заранее принятый current/один final snapshot; нет нового UI request/maintenance switch, вход ждёт quiescence/reconciliation |
| LEAK-04 P0 | Service decoy/new-old target keys, main screenshots policy разрешает снимки | Decoy данные не попадают в main UI/search/history/thumbs/media/скриншот; buffers/crypto contexts/closures проверены на всех выходах |
| LIFE-05 P1 | RememberFolder missing/trash/deleted, повторное открытие note | Вход в root fallback; editor автоматически не открывается; cursor focus восстанавливается при открытии note |

Фиксировать API, модель устройства, provider authority/version, тип накопителя,
режимы FD и результаты sync/reopen/power interruption. «Seek прошёл» недостаточно
для разрешения production записи. Исходный список разрешённых provider пуст;
первый доказанный профиль становится отдельным артефактом следующего этапа.

### 5.1. Три разных долговечности

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| DUR-01 P0 | Fault-store раздельно теряет volatile file bytes и unsynced namespace | file force/readback не выдаёт DurableCreateIdentity; обычный T ACK только по ExistingWriteDurable |
| DUR-02 P0 | Power loss после create/backup/candidate file fsync+reopen до provider identity commit | Без доказанного create-profile нет strong ACK; Unknown artifact не verified и не основание retention/switch |
| DUR-03 P0 | Fault на local temp write/force/rename/parent force, binding+journal publication | Старая/новая полная metadata запись либо StateUnknown, не смешанная; до подтверждения нет MaintenanceCommitted |
| DUR-04 P0 | Смена provider/root/volume/backend/Android version, общий authority; неизвестный/cloud provider | Повторный gate; пустая allowlist не становится production поддержкой по seek. UnsupportedDurability до edit/создания |

### 5.2. S1 portability

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| S1-01 P0 | Golden vectors pinned UCD16 на двух независимых реализациях и двух устройствах | Точные bytes/отказы, включая U+11382 U+113C9→f0918e83 и U+0378 reject; create/open без OS NFC зависимости |
| S1-02 P0 | Полный официальный Unicode16 NormalizationTest, repertoire до/после, raw/normalized limits, malformed surrogate | Conformance/data-version подтверждены до допуска normalizer, нет replacement/trim/case-fold/NFKC |
| S1-03 P0 | Python/system Unicode15 как normalizer без поставленных данных16 | Capability gate отклоняет, даже если большинство старых vectors прошли; не считать это поддержкой S1 |
| S1-04 P1 | PIN/text/pattern cross-method, ведущие нули, whitespace/case, private-use, unassigned/newer-than16 | Равные S1 едины, допустимый Co принимается, вне frozen repertoire отказ одинаков на старой/новой ОС |

## 6. Измерения и отчёт следующего этапа

KDF: по 20 cold/warm запусков на слабом и среднем телефоне, отдельно 1/2 slots;
m={16,32,64} MiB, t={2,3,4}, p=1, thermal/low-memory условия. Записать wall p50/p95,
RSS/GC, OOM/cancellation и выбранный creation profile; не выдавать ориентир 300–1000 мс
за обещание на любом устройстве. Argon2 vector корректность обязательна до замеров.

IO: save короткой note при 0/1/10 GiB synthetic attachments; количество прочитанных/
записанных bytes и неизменность media hashes, metadata growth, RAM. Compact
main/decoy/both и rekey каждой цели; reachable/retained bytes, S/C/2S+C,
место для backup/candidate/scratch, скорость streaming/VerifyAll и отмена. Отдельно измерить время полного writable
VFS validation на лимитах и установить UX progress/cancellation.

Отчёт перечисляет реально выполненные ID, конфигурацию, failures, неисполненные
device/security gates и ссылки на артефакты. Не подменять device-тест JVM-моделью
и не утверждать физическое стирание флеш-данных по результатам compact.

## 7. Граница фактически выполненного

Эта матрица — будущие испытания; ни один ID движка/device здесь не объявлен
пройденным в docs-only согласовании. Сейчас выполнены только малые static
fixtures/encoding/abstract-model checks, ссылки/таблицы/layout/test-ID/diff checks:
[reconciliation-materials](reconciliation-materials/README.md).
Исторические REVIEW-01…12 из отчёта распределены по S1/SCHEMA, COMPACT/MAINT,
REKEY/RECOVERY, LIFE/LEAK, DUR/RETRY/ROLLBACK; они не дублирующие test rows.
Android build/device/power-loss/fuzz/crypto vectors будущей библиотеки не запускались.

Один ID может иметь codec/core/integration/device ветви: P1…P5 принимают только
явно перечисленные доступные подслучаи, не полный pass интеграционного ID до
появления всех зависимостей. P6 итогово сводит матрицу и невыполненные ветви.
