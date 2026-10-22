# Copper Scroll: архитектура защищённого контейнера

Статус: базовая спецификация1.1 и явное [расширение P4/1.2](container-p4-extension.md).
P1…P4 имеют пользовательские Android runtime receipts. Headless P5a copy/compact
реализован; [новые runtime gates](validation/p5a-report.md) ещё открыты. Правила карточек/совместимости/лимитов
из расширения имеют приоритет над ограничениями базового reader1.1 ниже.
Дата: 18 октября 2026. Нормативный формат: [container-format-v1.md](container-format-v1.md).
Проверки: [container-test-plan.md](container-test-plan.md). Задание разработчику:
[container-prototype-plan.md](container-prototype-plan.md).

Все правила selective compact и смены секрета с новым ключом включены ниже.
[Дополнение](compaction-requirements-addendum.md) согласовано с этой редакцией;
[решения R/C](container-review-resolution.md) фиксируют изменения.
[Критический отчёт](container-critical-review.md) остаётся историей SHA54e40f3.

## 1. Основание и границы

Изучен проект на коммите `ab403242282f0361ac47118430fe00fe43c004de` и прочитана
актуальная концепция Copper Scroll через Google Drive:
`modified_time = 2026-10-18T09:21:18.728Z`. Приватные URL/ID документа не публикуются.
Её полный текст не добавляется в репозиторий. В приложенном задании есть две
редакционные поправки к концепции. В этой архитектуре приняты формулировки:
пользователь выбирает папку общей памяти; изменение фальшивого пароля не
затрагивает основное хранилище и не изменяет его пароль. Сам Google Doc не редактировался.

Текущие факты, а не возможности будущего движка:

| Источник | Что обнаружено | Следствие |
| --- | --- | --- |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | minSdk 31; compile/target SDK 37; AGP 9.4.1; Kotlin/Compose plugin 2.2.10; Java 11; Compose BOM 2026.02.01 | Настройки не менять |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.6.0 с SHA-256 distribution | Сохранить wrapper |
| `editor/NoteDocument.kt` | Paragraph(text, checked: Boolean?); список StyledRange; UTF-16 offsets | Сериализовать модель, не Android spans и не HTML |
| `WorkspaceState.kt` | NoteData содержит только document/color/pinned; ID, title, даты и icon отсутствуют | Ввести отдельные DTO движка и адаптер следующего этапа |
| `NoteSession.checkpoint` | Синхронный Unit-hook, сразу сбрасывает dirty; по умолчанию IO отсутствует | Это нельзя считать подтверждённым сохранением |
| `WorkspaceUi.kt`, `MainActivity.kt` | Запрос каждые 7 с, при закрытии и onStop, исключая rotation | Сохранить период, заменить контракт подтверждения позже |
| `RichNoteEditText.kt`, `docs/note-editor.md` | isSaveEnabled=false; ViewModel без SavedStateHandle; курсор/selection/scroll в памяти | Сохранить запрет дискового UI-state |
| Manifest и XML backup rules | allowBackup=true и шаблонные, фактически не настроенные исключения | До подключения реальных данных настроить исключения и allowBackup=false |

В репозитории и его родителях `AGENTS.md` не обнаружен. На этом этапе согласования меняются
архитектурные документы и малые проверочные материалы. Нет правок UI, Gradle, manifest, зависимостей
или демонстрационных данных. Упомянутые ниже API — задание следующему этапу.

## 2. Решение и сравнение вариантов

Выбран append-only контейнер объектов: неизменяемые зашифрованные записи,
copy-on-write индексы и два чередующихся superblock. Всё для открытия находится
в одном `.cscroll`. Пароль открывает ровно один случайный ключ зоны ZDK.
Физический каталог позволяет перемещать шифротекст закрытой зоны без её ключа.

| Критерий | Независимые объекты, выбранный вариант | SQLCipher / готовый SQLite-движок |
| --- | --- | --- |
| Один переносимый файл | Два superblock, обе зоны, индексы и данные внутри | Один закрытый checkpointed DB переносим; WAL/journal во время работы могут требовать соседних файлов |
| Независимые зоны | Разные ZDK, salt и envelope; нет общего ключа | Один DB-key открывает все страницы. Два DB потребуют внешней упаковки или двойного шифрования объектов внутри DB |
| Частичные операции | Новые блоки изменённого объекта и пути индексов | Сильная сторона: страницы, индексы и зрелые транзакции |
| Большие вложения | Двоичные chunks до 1 MiB, выборочное чтение | BLOB/incremental IO возможны; две зоны и переносимая упаковка усложняют доступ |
| Сбои | Нужен собственный проверенный протокол фиксации | Зрелые механизмы SQLite при выполнении требований VFS/fsync/locking |
| Уплотнение | Выбранные зоны по достижимости; невыбранная целиком | VACUUM требует ключа DB; две упакованные DB требуют перепаковки |
| SAF | rw/seek/fsync на проверенном локальном provider; без обязательного rename | Обычный Android API использует путь; произвольный SAF URI требует staging или собственного SQLite VFS |
| Сложность проверки | Высокая: формат, COW, parser, crash injection; нельзя сразу объявлять production-ready | Меньше своей логики транзакций, но независимость зон и SAF возвращают внешнюю сложность |
| Лицензии/поддержка | JCA Android + Bouncy Castle; поддержка собственного формата нашей командой | Community SQLCipher: BSD-style с notices; коммерческие сборки/поддержка по отдельной лицензии; native ABI и обновления |

Также рассмотрена ZIP-подобная полная зашифрованная перепаковка: удобна для экспорта,
но перезаписывает большие вложения на каждой заметке и не решает независимость зон
без отдельного формата. Для рабочего контейнера не выбирается.

SQLCipher не отвергается как ненадёжный: он хорош для одной БД с одним ключом.
Здесь внешняя упаковка двух DB, staging и двойное шифрование создают больше слоёв,
чем дают преимуществ. Не добавлять SQLCipher или Room в этом этапе.

## 3. Криптографическая граница и доступ

Для каждой зоны независимый случайный ZDK32, отдельные salt и Argon2id envelope.
Основной пароль не выводит и не восстанавливает decoy ZDK. Обязательного секрета
Keystore/телефона нет: файл переносим с соответствующим паролем. Обычная сессия
даёт доступ только одной зоне. Main-only MaintenanceContext может временно
владеть independently unwrapped decoy и main handles; общего wrapper/verifier нет.
Он не открывает decoy editor/list/search/history/media и не меняет UI mode.

AES-256-GCM/tag128 через Android JCA; Argon2id/HKDF-SHA256/HMAC-SHA256 готовой
библиотекой. BC lightweight API не регистрирует системный provider. Кандидат
`org.bouncycastle:bcprov-jdk15to18:1.86` ещё должен пройти Maven/license/advisories,
known-answer vectors, API31 и release/R8 перед добавлением зависимости.
Свежие IDs/nonce на каждое шифрование, RNG failure прекращает операцию.

S1 однозначно определён форматом§3: Unicode16.0.0, NFC/UAX15rev56 с поставляемыми
фиксированными таблицами; text не trim/case-fold/NFKC. Unassigned/Cn, Cs,
noncharacters и NUL/CR/LF запрещены; private-use разрешены. Raw text≤1024 code
points/4096B; после NFC1…256/1024B. PIN4/6 — ASCII с ведущими нулями; pattern3×3
с однозначным midpoint,4…9 принятых точек. Способ не входит в bytes секрета.
U+11382 U+113C9 → U+11383 → UTF-8 f0918e83 на всех устройствах. Системная NFC
без подтверждённых данных16.0.0 запрещена. Note text не нормализуется.

Запрет равных секретов проверяется новым S1 против current envelope другой зоны,
без общего быстрого verifier. При её неподдержанном/повреждённом descriptor
нельзя утверждать несовпадение: add/change secret отвергаются. Вход проверяет
оба поддерживаемых envelope последовательно, два совпадения — AmbiguousCredential.
Неверный tag не различает wrong secret и порчу envelope; после успешного unwrap
неверный MAC/root/AEAD — CorruptData. UnsupportedKdf/SecretEncoding/ResourceLimit
отдельны и не приводят к fallback/понижению профиля. Recovery — format§4.1.

Whitelist KDF: Argon2id19, m16/32/64MiB, t2/3/4, p1, salt16/out32, один worker,
не более двух Argon2 на пользовательский submit. Current-target и new-secret
submit обслуживания отдельны; новый submit использует максимум чужую проверку
и создание envelope. Никакого автоматического перебора типов/старых headers.
Creation hypothesis64MiB/3/1 не является измерением. Benchmark на слабом/среднем
телефоне cold/warm/thermal/OOM должен выбрать профиль. UI задержка не защищает
украденную копию от offline PIN/pattern перебора; сильный text предпочтительнее.

**ChangeSecret всегда заменяет ZDK и перешифровывает целевую зону в новый файл.**
Старый пароль повторно проверяется; новый дважды и не равен секрету другой зоны.
При прежнем S1 — UnchangedSecret, не «успешная смена». Режим быстрого rewrap не
предлагается. Старый ZDK не открывает новую целевую зону/будущие данные, если
новый секрет/ключ не скомпрометирован. Уже известный plaintext и старые копии
остаются известными. Старые source/backup открываются прежним паролем; UI об
этом предупреждает. Пароли обоих хранилищ меняются только из main; забытый decoy
не reset/удаляется и не восстанавливается main. Полный алгоритм — §8.3.

Предпочтительная панель ввода — отдельная локальная noBackup настройка, без
изменения descriptor/envelope/ZDK. Она не конвертирует секрет. Descriptor.inputMethod
меняется при create/add/ChangeSecret. Если ввод другим методом даёт другой S1,
это ChangeSecret, даже при одновременной смене панели. Подсказка открыта,
показывается plain text до входа как недоверенная; менять её только после
аутентификации своей зоны с новым logical MAC через T (секрет/ключ не меняются).

Проект защищает конфиденциальность и подлинность проверенных records, а не
доступность/свежесть/сокрытие числа зон. Публичны role/count/ID/KDF/hint/типы/размеры
и рост. Публичный SHA не MAC. Полный rollback и selective replay старой main при
актуальной decoy возможны без внешнего доверенного состояния: оба MAC могут быть
верны. Root/вредоносная ОС, чтение памяти открытого обслуживания, несотрудничающий
writer и произвольная порча носителя вне гарантий. Не добавлять общий ключ ради
аутентификации generation другой зоны.

## 4. SAF, долговечность и единственный владелец IO

Create: ACTION_OPEN_DOCUMENT_TREE, фактический persistable read/write grant,
новый уникальный `mystorage.cscroll` (при совпадении2,3…), MIME octet-stream.
OpenOriginal: ACTION_OPEN_DOCUMENT, выбранный URI, никакого угадывания path.
Контейнер и папка backup имеют независимые grants. Режимы не смешиваются:

| Режим | Контракт |
| --- | --- |
| OpenOriginal | Работает с исходником; если запись не поддержана, явный read-only/отказ, без скрытой staging-копии |
| ImportCopy | Явно создаёт независимый новый документ; source прежний, внутренние IDs при byte-copy прежние, URI отдельный |
| RestoreCopy | Новый отдельный файл, не перезаписывает активный; выбор нового binding подтверждается |

Gate привязан к authority+provider build/version+Android/API+root/volume/backend,
а не ко всем деревьям одной authority. Проверять отдельно источник, backup и
candidate destinations. Доказанные профили пока **пусты**. Неизвестный/cloud/pipe/
virtual/deferred-upload provider — read-only/отказ до edit. Флаги WRITE/RENAME,
seek и readback сами по себе не сертификат. Probe только на отдельном scratch,
никогда на полезных bytes. До password validation обслуживания допустимы лишь
read-only проверки уже выданных grants/profiles; создающий scratch probe должен
быть выполнен заранее в отдельной настройке или после необходимых проверок
секретов. Неверный decoy secret не оставляет artifacts/reservations.

| Гарантия профиля | Обязательное доказательство / статус при отсутствии |
| --- | --- |
| ExistingWriteDurable | Seekable rw regular FD, checked pread/pwrite/full IO, отсутствие truncate при rw, честный fsync и сохранность прежних durable ranges; device faults. Без этого только read-only |
| DurableCreateIdentity | Файл, имя/documentId и доступность по URI долговечны после поддержанного power loss. file fsync+reopen не подтверждают namespace. Нужен контракт/испытания конкретного root/backend; без него нет strong VerifiedCopy/VerifiedCandidate/нового Committed |
| DurableLocalState | Binding+journal атомарно и долговечно публикуются в app-private noBackup. Испытаны write/force/atomic rename/parent sync либо эквивалентная durable transaction; без этого production maintenance/switch запрещены |

Для local metadata путь приложения известен: одна транзакционно заменяемая запись
содержит binding и maintenance journal вместе, не две независимо сохраняемые prefs.
Реализация: временный файл в том же app-private каталоге → полный write/force →
atomic rename/replace → force каталога; каждый результат проверяется, faults дают
старую/новую целую запись или явный StateUnknown. Контракт примитива надо испытать
на Android; SharedPreferences.apply не заменяет durable ACK. Новое имя metadata
тоже требует namespace durability. Повреждённую запись не «чинить» выбором mtime.

FD SAF не даёт права угадывать путь и fsync родительского чужого каталога. Если
provider не даёт проверяемого DurableCreateIdentity, операция возвращает
UnsupportedDurability **до создания**, source/binding сохраняются. После начала
неоднозначного IO результат CommitUnknown/CopyUnknown/SwitchUnknown, артефакт
Unverified, не подтверждённая копия и не основание retention/delete. Успешный
probe не доказывает power-loss. При сбое вне гарантированного диапазона модели
восстановление из сохранённых копий; физическое стирание флеш не обещается.

Один ResourceOwner держит FD, advisory lock, writer lease и счётчик in-flight IO.
Process Mutex + interprocess lock на весь writable lifetime; другие handles того
же файла нельзя закрывать так, чтобы снять POSIX lock. Close ровно владельцем,
после quiescence. Перед T сверять headers/generation/size; external change —
Conflict, stop writer. Advisory lock не CAS против несотрудничающего приложения;
две копии с одним containerId не синхронизируются автоматически. После потери
grant/reselect проверить identity и profile, пустой файл на месте пропавшего
не создавать. Persist только реально выданные permission flags.

## 5. Единая транзакция записи T

T обслуживает обычную одну открытую зону: save/folder/metadata/settings/hint,
move/trash/restore/clear, а также create/add zone. ChangeSecret и compact — §8,
не малые T в исходник. Immutable COW/records не меняют старые достижимые ranges.

1. Проверить session/capability, writer lease, expectedZoneSeq/revision, generation
   и лимиты. Массовую команду валидировать целиком; snapshot редактора immutable.
2. Append новые AEAD records, COW indexes/root, catalogue. zoneSeq текущей зоны+1;
   opId/recentOperations по format§5. Другой logical descriptor/catalogue прежний.
   Это инкремент существующей изменяемой зоны: при Create/Add новая зона начинает
   с zoneSeq=1/recentOperations=[], а Add не меняет seq/root основной зоны.
3. fsync данных. На ошибке header не публиковать; orphan tail не commit.
4. Сформировать полный inactive header: generation+1, новый committedLength и
   свой MAC; другой descriptor прежний. Полный pwrite, второй fsync.
5. Через тот же lock-owning FD перечитать header/новые pages/records, проверить
   hash/MAC/AEAD/root/revision. Только после этого Committed(generation,zoneSeq,
   revision,objectId). Для create дополнительно DurableCreateIdentity.

Dirty сбрасывается только для ACK revision N; N+1 остаётся dirty. Ошибка/timeout
при header write/force/readback — CommitUnknown, дальнейшие writes запрещены до
reread/reconciliation. Целый неACKed header может пережить crash и стать текущим;
torn/hash-invalid slot не кандидат. Hash-valid newest с плохим referenced root
даёт CorruptData, не тихий fallback. Password failure никогда не выбирает старый
slot/URI. Явное recovery старого snapshot предупреждает о старом пароле/данных.

Хвост вне committedLength игнорировать; truncate только после исправного snapshot
и fsync, иначе gap/append в фактический EOF. Нет inplace free-list reuse. ENOSPC,
quota, short read/write, EIO, grant loss — ошибка/Unknown, без ложного Saved и
с последним подтверждённым revision. Preflight free space приблизителен.

| Операция T | Атомарная область |
| --- | --- |
| Create | Один новый документ: случайные IDs/ZDK, пустые root/trash/settings/index, generation1, slot B нулевой; до ACK не активировать |
| Add decoy | Main-only, повторный main old secret, новый секрет дважды/inequality, независимый ZDK, zoneCount1→2; main logical bytes/records прежние; неизвестную существующую decoy не reset |
| Save note | Устойчивый заранее выданный objectId, DTO+metadata+index/root; новый FullyEmpty не создаётся, existing empty сохраняется |
| Move/trash/restore/clear | Вся нормализованная группа/edges/index одним T, cycles/depth/stale reject целиком; restore overflow по формату |
| Metadata/settings/hint | Только подтверждённые изменения; не менять contentModified от cursor/color/move; нет неявной замены unknown enum/icon |

OpId выдаёт coordinator для exact immutable request и привязывает в памяти к
epoch/zone/expectedZoneSeq/command. Повтор с иным payload — InvalidOperationToken.
RecentOperations256 — hint выполненного op, а не полный ACK/digest. Только живой
неизменный token может получить cache ACK/reconciliation без повторного эффекта.
После restart, потери token/окна — UnknownOperation/Conflict, чтение objectId и
revision; новая destructive команда требует новой проверки/подтверждения/opId.
Бессрочного exactly-once нет; plaintext команд для этого на диск не сохранять.
Create/Add Unknown разрешается чтением заранее выданных document/zone IDs и
zoneCount; не создавать повторный файл/вторую decoy автоматически, не reset
существующей зоны. После потери token безопасный отказ остаётся допустимым.

## 6. Сессия, завершение и foreground

UI access и IO lifetime — разные состояния:

```text
Locked -> OpenMain/OpenDecoy -> Saving
OpenMain -> Maintenance(Auth/Preflight/Backup/Build/Verify/Switch)
loss foreground/cancel -> AccessRevoked + UI Locked
ResourceOwner: Busy -> StopRequested -> Quiescent -> Released
неоднозначный outcome -> ReconciliationRequired (запись запрещена)
```

При отзыве немедленно скрыть note/list/media/диалоги/thumbnails, FLAG_SECURE=true,
инвалидировать пользовательский epoch и pending callbacks. Новых команд нет.
В той же сериализованной границе допускается заранее принять один final immutable
checkpoint последних правок как ClosingSave; он либо уже текущая T, либо максимум
один последний snapshot после неё. Это узкая внутренняя save capability, не
продолжение пользовательской сессии/обслуживания. Нельзя поздним callback создать
новый ClosingSave. Неподтверждённые правки могут потеряться при kill/IO error;
период7с плюс очередь не верхняя временная гарантия.

Отдельно запросить остановку service/KDF/traversal и кооперативных работ.
Timeout (ориентир2с только для UX ожидания) и coroutine cancel **не доказывают**
окончание pwrite/fsync/Argon2. Не закрывать FD в другом потоке для фиктивной
отмены. Владелец ждёт реального возврата syscalls, не начинает следующие шаги
без разрешённой capability, разрешает Unknown и только затем снимает lock/lease
и закрывает FD. Нельзя начать второй writer/вход с доступом к данным, пока owner
не quiescent и исход не разрешён. Возврат пользователю — Locked/«запись завершается»,
не разблокированный редактор. При зависшем IO конечного срока освобождения всех
ключей не обещать; жёсткая изоляция процесса — будущая отдельная проверенная мера.

Уничтожать ненужные buffers немедленно, используемые handles/caches/scratch keys
в finally после последнего использования/IO. Зануление ByteArray не доказывает
физического удаления всех String/JVM/IME/crypto-engine копий. Maintenance plaintext
не попадает в main repository/UI; late result лишь обновляет outcome, не открывает
контент и не принимает новый switch. Ключи не пишутся в journal/state/temps.

### 6.1. Точная политика Android

Открытый доступ допустим только когда наше окно видимо, экран interactive, наша
Activity top-resumed и не начат отзывающий переход. Focus — дополнительный сигнал,
а не единственный lifecycle callback. Состояние пересчитывает coordinator:

| Событие | Политика |
| --- | --- |
| Home, screen-off/device lock, потеря visibility, уход top-resumed к другому приложению | Немедленно revoke/hide; в multiwindow вторая RESUMED Activity не сохраняет доступ |
| Неопознанная потеря window focus, notification shade/system permission dialog | Revoke консервативно; возврат требует входа |
| Зарегистрированный собственный modal dialog/меню, обычная IME при видимой top-resumed Activity | Не revoke только от фокуса; UI policy и screen-off/top-resumed проверки продолжаются |
| Rotation/resize configuration handoff | Тот же memory ViewModel/epoch и worker, без второго запроса/сохранённого plaintext; не считать фоновым переходом. Home/screen-off во время handoff всё равно revoke |
| Own picker | Сначала принять нужный checkpoint и revoke сессию; picker получает отдельный Locked operation token, а не ключи |

onStop/isChangingConfigurations недостаточно: Android multi-resume может оставить
оба окна RESUMED. Не полагаться на единственную Activity callback. Для permission
dialog и notification shade не вводится исключение, которое продлевает доступ.

Picker token = opId/Locked epoch/expectedState/purpose. RESULT_CANCELED — без
применения. Home/screen-off/process death инвалидируют token, поздний URI:
закрыть FD, не импортировать, не брать persistable grant, не переключать binding.
Штатный valid result может сохранить выбранный tree grant в Locked настройке,
но не возобновляет прежнюю main session/maintenance; новый вход и новая команда.
Rotation token сохраняет только в memory, чужую Activity принудительно не закрывает.
Пустая внешняя заготовка picker не готовый контейнер.

## 7. Настройки, редактор и отсутствие утечек

| Место | Допустимое содержимое |
| --- | --- |
| До входа | Select/create/import, preferred input, открытые hint/версия envelope, язык/About; нет trash/password/zone settings |
| App-private noBackup | URI/tree grants, preferred input, provider profile, binding+journal identity/стадии; нет текста/title/preview/password/ZDK |
| Зашифрованная зона | Сортировка/вид, rememberFolder/lastFolderId, screenshot/copy policy, trash/counters/dates/cursor; каждая зона самостоятельна |

В decoy скрыты и запрещены координатором ChangeSecret/add zone, manual Backup,
compact и выбор другого контейнера. Core keyless copier может быть симметричен
в тестах, это не право user-session. UI settings принимают fallback unknown enum/
icon без автоматической перезаписи raw value. Вход открывает remembered folder
или root, не редактор; cursor применяется только при открытии note.

Текущие NoteDocument paragraphs/todo/styles UTF-16 сохраняются без NFC/HTML/spans.
Storage DTO добавляет ID/title/icon/dates/revision/cursor отдельно; текущее UI не
расширяется в docs-only этапе. Unit NoteSaveHook не ACK. Подключение позднее:
immutable snapshot+async result, dirty только на подтверждённый revision,
ошибка не маскируется закрытием. FullyEmpty и content/metadata dates — format§7.

До пользовательских данных: allowBackup=false и явные exclusions root/file/
database/sharedpref/external/device_* для cloud/device transfer в обоих XML,
проверить допустимые Android domains/OEM. Общий SAF файл и чужие копии backup
rules не контролируют. Journal/encrypted scratch в noBackup, keys только RAM;
готовый portable candidate в явно выбранном SAF destination. Ни SavedStateHandle,
Bundle/rememberSaveable, WorkManager Data, logs/crash attachments, plaintext temps
для контента/ключей. Native EditText/Compose: secure recents, autofill/content capture,
clipboard/share/process-text/drag/accessibility gates; один isSaveEnabled=false
не закрывает эти каналы. Перед входом и при revoke FLAG_SECURE=true; после входа
zone policy, но служебный decoy plaintext не отображается даже в main screenshots.

## 8. Копии и общий протокол обслуживания

### 8.1. Backup и гарантии копии

Backup под writer barrier копирует ровно committedLength выбранного S, не EOF,
в уникальный файл UTC timestamp+random suffix, никогда поверх здоровой копии.
Длина/full streaming SHA source/destination, parse обоих catalogues/ranges,
force/reopen и DurableCreateIdentity обязательны до VerifiedCopy. В обслуживании
VerifyAll AEAD/refs обязателен для target/selected зон с проверенными ключами;
для unselected — точность bytes/hashes относительно source, не доказательство
отсутствия уже внесённой порчи/потери catalogue entry. Наличие borrowed main key
при decoy-only не превращает main в selected и не требует её reachability traversal.
При ручном backup открытая поддерживаемая зона проверяется AEAD, closed — keyless;
неизвестная closure не объявляется crypto-verified. Границы проверки сообщаются.
Случайная порча обнаруживается hashes, намеренная подмена может их пересчитать.
Подозрительная копия не вытесняет здоровую. Retention минимум3 проверенных,
старейшая удаляется только после новой проверенной и не единственной исправной;
не удалять прежние ради места. Хранить verification coverage по zoneId в безопасной
метаинформации копий; relative-only copy не повышает health и не вытесняет последнюю
копию с более сильной известной AEAD-проверкой. Maintenance создаёт одну новую backup и сохраняет
source и эту backup; их удаление отдельно. Import/Restore не перезаписывают source.

### 8.2. MaintenanceCoordinator: один snapshot и результат

Общий автомат compact/ChangeSecret, с одной новой backup и одним candidate.
Доступ только main, включая прямой dispatch/deep-link/старую capability.
Стадии и ответственность:

| Стадия | Обязательные действия / отказ |
| --- | --- |
| AuthorizeMain | Проверить OpenMain/epoch первым действием, до picker/KDF/резервирования. Из decoy отказ без запроса main secret/предложения обслуживания |
| Destinations | Read-only проверить заранее полученные grants/profiles source+backup+candidate и DurableLocalState. Если нужны picker/grants — закрыть main, настроить в Locked, затем новая main-команда; старые keys/выбор не продолжать |
| Credentials | Compact selected decoy: её current secret до backup/reservation/candidate. ChangeSecret: old target, new twice, inequality другой зоны, новый S1≠old. Проверять expected zoneId/role+MAC/root, не общий успешный login. Ошибка/отмена/unsupported — ноль artifacts, source/binding прежние |
| Confirm | Показать точный выбор/target/destinations, retained files, старую защиту source/backup при rekey и место; окончательное подтверждение до IO создания |
| DrainAndFreeze | Запретить новые команды обеих зон, завершить pending save, разрешить CommitUnknown либо остановиться. Захватить exclusive maintenance lease на весь оставшийся workflow, проверить исходную identity/envelopes после credential проверки. Если изменились — отказ и новая проверка/подтверждение. Зафиксировать один immutable S |
| Preflight | С ключами полностью проверить reachable schemas целевых/selected зон; неподдержанные зависимости — отказ до backup. Проверить полный публичный каталог unselected, лимиты, grants/profiles/free space; для rekey подготовить новый ZDK/envelope в RAM до artifacts. Ничего не переводить автоматически в partial |
| Backup | Одна VerifiedCopy всего S по8.1. Failure/CopyUnknown — stop, source и binding прежние |
| BuildCandidate | Из пустого уникального документа построить один новый файл по8.3/8.4; запрет всех save/add/password/migration/другого maintenance до окончания lease |
| VerifyCandidate | Проверить framing/ranges, обе зоны целиком в требуемых гарантиях, hashes/AEAD/closure, данные/IDs/даты, force/reopen+namespace durability. Candidate identity+build manifest совпадают; source всё ещё S |
| PrepareSwitch | Долговечно journal CandidateVerified/затем SwitchIntent, source/candidate fingerprints. Повторно проверить current main epoch, lease, grants, source/candidate identity/freshness; no uncoordinated replacement |
| AcceptSwitch | Короткая сериализованная граница с Revoke: если revoke раньше, не принимать switch. Если accept раньше, логическое решение выбрать verified candidate уже принято до блокировки; передать одному IO-owner durable публикацию binding+journal, без долгого UI mutex |
| ResolveAndClose | Только подтверждённый durable Activated даёт MaintenanceCommitted/new password changed. Error/Unknown — Locked и reconciliation. На всех выходах StopRequested→Quiescent→Released, owner cleanup. Даже успех закрывает старую сессию в Locked; следующий вход отдельно, late callback не открывает UI |

Main-only запуск не означает знание main secret у keyless helper: при decoy-only
низкоуровневый copier main сохраняет без main key, хотя coordinator авторизован
основной сессией. Main handle может быть borrowed; old/new target ZDK и второй
independently unwrapped handle принадлежат отдельному context. Держать минимальные
decrypted pages, encrypted scratch с временным ключом, не общий UI repository.
Выбранная decoy не открывается пользовательски; KDF/queues/caches/closures и crypto
engine copies включены в cleanup. При rekey service временно владеет старым и
новым target ZDK — это не доступ основного пароля к забытой decoy.

### 8.3. ChangeSecret: новый ZDK и новый URI

Только целевая зона перешифровывается, другая целиком opaque. После current-target
verification/new twice/inequality и freeze S создать новый случайный ZDK32,
salt16/wrapNonce12 и envelope нового секрета. Новый ZDK/envelope обязательно подготовить в RAM до резервирования/backup;
ошибка KDF/RNG этой стадии не создаёт artifacts. После проверки новой обёртки
очистить Kraw/Kwrap/S1, оставить только нужные handles. Не хранить secrets в journal.
RNG failure новых record IDs/nonce уже во время build отменяет весь candidate,
а не обещает отсутствие ранее созданной backup.

Перешифровать bottom-up все reachable media/note chunks, chunk-list pages,
objects/settings, children/objects indexes и root. На каждом новом ciphertext
свежие recordId/nonce и Krecord нового ZDK, новые hashes/LogicalRefs. Сохранить
object/zone/virtual-root IDs, содержание, styles/todo, props/dates/objectRevision,
cursor/counters/recentOperations. Target zoneSeq+1 — единственная обязательная
служебная revision; другая seq прежняя. ContainerId прежний, instanceId новый,
generation=S+1. Полный новый catalogue цели содержит только новые reachable IDs,
без прежних obsolete/old envelopes. Build manifest и нулевые gaps покрывают весь
candidate до EOF=committedLength, не copy source prefix/header. Оба slot одинаковы
и содержат только новый target envelope; records другой зоны побайтно прежние,
descriptor[0..16] прежний, physical catalogue rebuild. Старые target records/headers
остаются в отдельно retained source/backup, не в рабочем candidate.

Verify: полная логическая эквивалентность цели (кроме seq/envelope/refs), все target
AEAD новым ZDK; отказ старого ZDK на каждом новом target ciphertext; отсутствие
old target IDs/old envelope и orphan old ranges. Сам old-key negative не заменяет
проверку новых данных. Открыть оба пароля после переноса на чистый instance в тесте;
в работе чужой пароль для copy не требуется. Добавленные позднее данные также
используют только новый ZDK. Объявлять «пароль изменён» лишь MaintenanceCommitted.
При ошибке до AcceptSwitch прежний URI/пароль; после принятия неопределённый
durable исход — SwitchUnknown с явным разрешением. Нельзя автоматически открыть
source старым паролем как будто смены не было.

### 8.4. Selective compact

Main пользователь выбирает main/decoy/both, обе существующие выбраны по умолчанию;
main-only файл не предлагает decoy, empty selection запрещён. Decoy-only всё равно
из main. Проверить selected decoy secret именно против decoy до artifacts; main
secret в этом prompt не успех. Неверный/забытый/cancelled secret не запускает
частичный main-only: можно вернуться, явно снять decoy и подтвердить новую команду.

Selected zones копировать по полной известной Reachable closure с их ключами,
включая trash/settings. Unselected — все записи текущего public catalogue, в том
числе obsolete, без её ключа. Ciphertext selected/unselected не перешифровывать:
IDs/nonce/hash/LogicalRefs/logical descriptors прежние. Physical catalogues для
новых offsets rebuild, не побайтный copy старых pages. ContainerId прежний,
instanceId новый, generation=S+1, zoneSeq прежние, оба header одинаковы.
Проверить selected AEAD/ссылки/logical snapshot и unselected recordId/raw-hash
multiset+logical descriptor; один final switch только после проверки **всего**
файла. Ошибка между зонами/VerifyAll — отказ всей операции, source binding прежний.
Новое разрешение обслуживания не требует новых layout полей: offsets/instanceId
исключены из record AAD/Krecord и catalogueRoot из logical MAC (format§2).

### 8.5. Journal, switch и отмена

Одна durable noBackup запись совместно содержит binding и journal:
version/opId/kind/target-or-selection; source URI/containerId/instanceId/generation/
committedLength+SHA обоих headers; backup URI/hash/length; candidate URI/новый
instanceId/generation/length/header fingerprint/full hash; stage/outcome и
предыдущее binding. Поля до появления файла null. URI/ID/hash/выбор допустимы,
password/S1/ZDK/plaintext/криптографические engine states запрещены. Upper bounds
на URI/record length и checksum metadata проверяются до allocation. Checksum не
защищает от rollback ОС, journal не подменяет проверку файлов.

Стадии: Prepared → BackupVerified → Building → CandidateVerified → SwitchIntent
→ Activated; либо Aborted/Unverified/ReconciliationRequired. Prepared записывается
только после credentials/confirm/freeze/preflight, не до проверки паролей.
Каждая durable запись
атомарна по§4. До AcceptSwitch ошибка/cancel/revoke сохраняют прежнее binding.
Нельзя снять lease после backup/первой зоны. После AcceptSwitch отмена не обещает
«ничего не изменилось»: durable запись может быть старой/новой/неизвестной.
Home скрывает UI/отзывает дальнейшие действия сразу, но не отменяет уже принятое
до него решение. Позднее завершение сохраняет только outcome; не принимает новый
switch, не показывает успех/decoy и не разблокирует сессию. Если ACK нельзя
достоверно доставить, показать при следующем явном входе Pending/Unreported и
разрешить его, а не молча продолжить работу в candidate.

После restart/reconciliation проверить целостность local state, identity source/
candidate, grants/profile, source freshness и повторно candidate в нужных гарантиях.
Verified flag не доверен без reread. Если нужны уничтоженные ключи — новый явный
вход/ввод нужного секрета (после rekey для candidate нужен **новый** секрет).
Old source и candidate предлагаются как разные пути/варианты защиты. Не угадывать
по имени/mtime/containerId, не reexecute ChangeSecret или повторно создавать backup
по одному opId. Изменённый source/stale candidate — Conflict и явное решение;
потерянный grant — AccessLost. Неизвестный durable binding — SwitchUnknown,
writer запрещён. Подтверждённый source либо Activated candidate разрешается
явно; отменённый/unverified candidate не становится активным сам. Source и
verified backup сохраняются при всех отказах; cleanup только собственных
незавершённых artifacts по journal, не единственной здоровой копии.

### 8.6. Место и версии

Отчёт compact: selected/processed zones, S/C, S−C, retained source+backup и новый
путь. При source S и новой backup S суммарно2S+C, прирост S+C; после удаления
одного source остаётся S+C. C=S/C>S — допустимый честный результат. Учитывать
старые copies, encrypted scratch, partial artifacts, reserve IO и различие
logical length/physical allocated. Reclaim неизвестной unselected не угадывать.
Rekey может требовать больше места/IO; прежние копии ради него не удаляются.

Поддержаны1.1 и явный1.2 для карточек по расширению P4; draft1.0 не поддержан для записи.
На исходном docs-only этапе готовых файлов движка не было; теперь используются
синтетические fixtures P1…P4, внешние файлы пользователя не исследованы.
Новых полей для compact/rekey не нужно, но minor0→1 явно отделяет
согласованные S1/folder/enums от прежнего draft. Unknown required/container
version — отказ записи всего файла. Unknown selected reachability — отказ всей
операции до backup, без fallback; unselected opaque copy разрешён только при
понятных внешних descriptor/catalogue/record framing и поддержанных envelope1/S1/KDF/record1 обеих зон; unknown plaintext невыбранной не интерпретировать. Не предполагать relocation policy неизвестного envelope. Порчу не закреплять writer.
Recovery исправной зоны — отдельный read-only алгоритм format§4.1. Migration
отдельна, только известный переход+verified backup, не автоматически при compact.

Уточнения реализации P5a (21 октября2026): metadata port LocalStateStore сохраняет
одну binding+journal запись; local codec version2 добавляет реальные document
identities и pending artifact (legacy1 читается), portable container не меняется.
Binding выбирает документ; его fingerprint — последний наблюдённый checkpoint.
Обычная T не пишет sidecar: generation того же URI/identity/container/instance
может вырасти, текущая зона проверяется новым входом. При равной generation
fingerprint обязан совпасть. journal.source — точный frozen S операции, а
previousBinding может содержать более ранний checkpoint. Отдельного источника
истины/ACK private metadata для обычной T нет. Maintenance hold держит lease
через abort/cleanup, admission закрывается под core serializer до его освобождения.
Новый main request ticket инвалидирует прежние confirmations/context, в том числе
в другом coordinator на том же core.
Новый app-level вход в journal-managed файл проходит TransactionCore.openBound;
pending/Unreported/повреждённое состояние не выдаёт writer до explicit resolution.
P5a maps ограничены32768 публичными records суммарно обеих зон (32MiB conservative
1024B/record), streaming64KiB, без encrypted scratch; превышение до backup.
Подробности/границы модели — [отчёт P5a](validation/p5a-report.md).

## 9. Готовность и экспериментальные условия

P1 можно реализовывать по фиксированному формату1.1 и golden fixtures отдельным
заданием. Это не production approval. Далее небольшие P1…P6 из prototype plan
с условиями входа; не выполнять всё приложение одним запуском. Правки Gradle,
SDK/minSdk/Java/зависимостей не входят в этот docs-only PR.

Открыты именно измеряемые gates: Unicode16 implementation/conformance, crypto
artifact/API31/R8/advisories, KDF p50/p95/RSS/thermal/OOM; device root/volume IO,
namespace и local-state durability; неотменяемый IO/lock lifetime; worst-case
VFS/traversal/scratch; lifecycle/IME/leak/OEM backup. Производственный provider
allowlist пуст. Нет нерешённого выбора модели rekey/compact/S1: эти правила выше
нормативны. Artwork icon palette и локализованная UI сортировка будущие продуктовые
расширения, не повод менять fixed wire IDs/enum в реализации по умолчанию.

## 10. Первичные источники

* [Unicode16/UAX15rev56](https://www.unicode.org/reports/tr15/tr15-56.html),
  [UCD16.0.0](https://www.unicode.org/Public/16.0.0/ucd/) — закреплённые NFC/repertoire.
* [RFC9106](https://www.rfc-editor.org/rfc/rfc9106.html),
  [RFC5869](https://www.rfc-editor.org/rfc/rfc5869.html),
  [NIST GCM](https://csrc.nist.gov/pubs/sp/800/38/d/final) — готовые примитивы/vectors.
* [BC downloads](https://www.bouncycastle.org/download/bouncy-castle-java/),
  [license](https://www.bouncycastle.org/licence.html) — candidate artifact/notices,
  не Android-сертификат.
* [close(2)](https://man7.org/linux/man-pages/man2/close.2.html),
  [fsync(2)](https://man7.org/linux/man-pages/man2/fsync.2.html) — окончание IO/namespace.
* [ContentResolver](https://developer.android.com/reference/android/content/ContentResolver),
  [DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider),
  [multi-resume](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-resume),
  [Auto Backup](https://developer.android.com/identity/data/autobackup) — API/lifecycle,
  не доказательство свойств конкретного provider/OEM.
