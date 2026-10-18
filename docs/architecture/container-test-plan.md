# Copper Scroll: план проверок контейнера

Статус: задания для следующего этапа. Перечисленные проверки движка НЕ выполнены
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
| KEY-04 P0 | Смена main, decoy через main settings с проверкой соответствующего old secret | Старый пароль не открывает новейший snapshot; новый открывает; другой пароль/данные прежние |
| KEY-05 P0 | Неправильный old secret; совпадающий new secret; забытый decoy; повреждённый второй envelope | Никакого commit/reset; нет восстановления decoy основным; equality-check fail безопасен |
| KEY-06 P0 | PIN4 `0012`, PIN6 `000012`, text `0012`; text NFC composed/decomposed, пробелы/case | Ведущие нули и S1 точны; равные канонические secrets запрещены независимо от метода |
| KEY-07 P0 | Pattern переходы1→3,1→9, уже выбранная середина/конец, разные screen density | Одинаковая итоговая последовательность;4…9 уникальных точек; совпадение pattern1234/PIN1234/text1234 запрещено |
| KEY-08 P1 | Сменить только inputMethod своей зоны при том же S1; войти другим поддерживаемым методом | Доступ сохраняется, другая зона не меняется; обязательная old verification |
| KEY-09 P0 | Два envelope в fixture принимают один secret | AmbiguousCredential; нет автоматического выбора main |
| KEY-10 P0 | Случайные/подменённые m/t/p, salt length, kdfId/version | Отказ до KDF/выделения памяти; не больше двух последовательных KDF на submit |
| CRYPTO-01 P0 | Перенести ciphertext в другую зону/container/object/ordinal/kind; подменить nonce/header/ref | MAC/hash/AEAD rejection; plaintext не опубликован |
| CRYPTO-02 P0 | Повторить save после rollback/kill и RNG failure; наблюдать новые IDs/nonce/key domains | Новое шифрование не переиспользует пару key/nonce; RNG failure не пишет; ID collision отвергается |
| CRYPTO-03 P1 | Проверка hint до входа/смена после входа/порча hint | Открытое предупреждение; plain text rendering; MAC выявляет порчу после unwrap |
| FILE-01 P0 | Перенос всего файла при одной/двух зонах, без sidecars/локальных prefs | Обе зоны доступны своими паролями; переносимый файл самодостаточен |
| FILE-02 P0 | Усечение на каждом offset header и каждой границе record/page, повреждение новейшего slot | Только согласованный старый/новый snapshot по правилам recovery, либо явная ошибка порчи; никаких пустых замен |
| FILE-03 P0 | Hash-valid header с неверным referenced root; старый header ещё исправен | CorruptData; старый snapshot только отдельным read-only recovery, не тихий rollback |
| FILE-04 P0 | Новый major/unknown required bit или неизвестная note/zone версия | Никакой записи в неизвестный формат; opaque records сохраняются при допустимом byte copy |
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

Для create/add zone/save/move/delete/restore/clear/password/settings выполнить
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
| TX-07 P0 | Новый пароль committed, старый slot ещё содержит прежний envelope | Вход не пробует old slot по password failure; ручной recovery явно предупреждает об откате |

JVM модель `force()` должна различать volatile/durable ranges и потерю
несинхронизированных bytes; тестировать произвольный torn write inactive header.
Это проверяет алгоритм в заданной модели хранения, не доказывает power-loss
поведение реального телефона. Для него нужны device/provider испытания отдельно.

## 4. Backup, migration и compact

| ID / приоритет | Сценарий | Ожидание |
| --- | --- | --- |
| COPY-01 P0 | Copy под writer barrier, открыть на другом устройстве обоими secret | Согласованный snapshot ровно committedLength; обе зоны и unknown opaque records сохранены |
| COPY-02 P0 | Short copy/искажение byte/нехватка места/закрытый grant при readback | Копия не verified; source и прежние verified copies не удалены |
| COPY-03 P1 | Имена совпадают, clock назад, retention при трёх копиях/нехватке места | Уникальный suffix; новая копия не overwrites old; удаление только после новой проверки |
| COPY-04 P0 | Source уже повреждён в закрытой зоне | Byte equality не называется AEAD-верификацией закрытой зоны; подозрительная копия не вытесняет здоровую |
| COMPACT-01 P0 | Удалить много main notes, decoy закрыта, compact только main secret | Main live set точен; все decoy catalogue IDs/raw hashes и logical descriptor побайтно прежние; decoy потом открывается |
| COMPACT-02 P0 | Затем наоборот: decoy открыта, main неизвестна engine; compact | Main сохранена; команда доступна в корзине decoy, verified backup не требует main secret |
| COMPACT-03 P0 | Kill/ENOSPC на backup, copy chunks, rebuild catalogue, candidate header/readback/URI switch | Исправный исходник сохранён; candidate не выбирается по одному имени; journal допускает восстановление выбора |
| COMPACT-04 P0 | Число/хэш закрытых records не совпало, отсутствует verified backup | Отказ до switch URI; не продолжать с усечённой закрытой зоной |
| COMPACT-05 P1 | Проверить место до/после, закрытая зона содержит много obsolete records | Освобождается только мусор открытой зоны; результат указывает retained closed bytes, старый файл не удалён автоматически |
| MIGRATE-01 P0 | Migration известной старой zone/note версии при закрытой второй | Сначала verified backup; мигрирована только открытая; другая preserved opaque |
| MIGRATE-02 P0 | Неизвестная более новая версия, crash migration, нехватка места | Нет модификации неизвестного формата; прежний snapshot/copy доступен |
| RESTORE-01 P0 | Restore в отдельный файл с тем же containerId; исходник активен | Новый URI tracked отдельно; исходник не перезаписан, не перепутан по containerId |

Compact tests обязаны сравнить не только факты успешного входа, но весь набор
recordId/hash закрытой зоны и все доступные объекты после её открытия. Основной
пароль в таком тесте не передаётся engine, выполняющему compact другой зоны.
Шифротекст не перешифровывается под ключом открытой зоны.

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
| LIFE-05 P1 | RememberFolder missing/trash/deleted, повторное открытие note | Вход в root fallback; editor автоматически не открывается; cursor focus восстанавливается при открытии note |

Фиксировать API, модель устройства, provider authority/version, тип накопителя,
режимы FD и результаты sync/reopen/power interruption. «Seek прошёл» недостаточно
для разрешения production записи. Исходный список разрешённых provider пуст;
первый доказанный профиль становится отдельным артефактом следующего этапа.

## 6. Измерения и отчёт следующего этапа

KDF: по 20 cold/warm запусков на слабом и среднем телефоне, отдельно 1/2 slots;
m={16,32,64} MiB, t={2,3,4}, p=1, thermal/low-memory условия. Записать wall p50/p95,
RSS/GC, OOM/cancellation и выбранный creation profile; не выдавать ориентир 300–1000 мс
за обещание на любом устройстве. Argon2 vector корректность обязательна до замеров.

IO: save короткой note при 0/1/10 GiB synthetic attachments; количество прочитанных/
записанных bytes и неизменность media hashes, metadata growth, RAM. Compact
main-only и с закрытой второй зоной, размер живых/retained bytes, место для backup
и кандидата, скорость streaming и отмена. Отдельно измерить время полного writable
VFS validation на лимитах и установить UX progress/cancellation.

Отчёт перечисляет реально выполненные ID, конфигурацию, failures, неисполненные
device/security gates и ссылки на артефакты. Не подменять device-тест JVM-моделью
и не утверждать физическое стирание флеш-данных по результатам compact.
