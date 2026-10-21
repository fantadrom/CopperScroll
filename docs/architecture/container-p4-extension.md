# P4: VFS, карточки и формат 1.2

Нормативное расширение от 20 октября 2026. Прочитана концепция редакции
20.10.2026 14:29:08 UTC; внешний документ не изменён. Требования P4 имеют
приоритет над ранней концепцией. Исторические R/C и critical review не переписываются.

## 1. Совместимость

Поддержаны containerMajor=1, minor=1 или2, requiredFeatures=0. Формат1.1 и его
fixtures прежние. Новый синтетический Create может явно запросить minor2;
default P3 Create остаётся1.1. Add/обычные T сохраняют исходный minor.
Миграции1.1→1.2 нет: create/save card в1.1 возвращает UnsupportedVersion до
append. Закрытая зона не мигрирует; перенос/переход формата — отдельная задача P5.
Прежний reader1.1 отклоняет hash-valid minor2 без отката к старому slot.
Новый reader не допускает разные minor у двух checksum-valid slots.
Unknown major/minor/features блокирует writer всего файла; unknown inner
schema/type/field type — Unsupported/read-only своей зоны, не пустой объект.
RecoverZoneReadOnly изолирует другую зону по прежнему контракту.

Layout65536×2, descriptor18, record header76, refs/catalogue, envelope1/S1/UCD16,
recordVersion1 и AEAD/MAC domains прежние. Меняется u16 minor по offset10.
Zone/minReader/settings/index/note/media версии1 сохраняются. ObjectSchema1
по-прежнему types1…5; **objectSchema2 только type6=card**, те же14 common fields.
Unknown future type не определяется по icon.

## 2. Card object и тело

Единственный persisted источник title/icon/color/dates/objectRevision — common
ObjectData, не body. Title не blank по Unicode16 White_Space,≤100 code points/
400UTF-8B, без CR/LF. Default icon `card`; допустимый unknown ASCII icon сохраняется
при unrelated edit, fallback не заменяет raw. Folder/media icons fixed.

Payload = `[cardFormatVersion=1, bodyBytes:uint32, chunks:LogicalRef-array,
useAsUserTemplate:bool=false]`. Body = `[cardFormatVersion=1, fields:array]`.
Body≤4MiB,1…4 chunks; все, кроме последнего, ровно1MiB, последний1…1MiB. Kind4 в1.2 —
document chunk (note либо card), ownerId=objectId, ordinal0…n−1; это расширение
семантики защищено minor2. Note1.1 не меняется. Новых record kinds нет.
Сумма lengths равна bodyBytes. Metadata≤16KiB; тело не ограничено одной page.
Reader/VerifyAll/recovery учитывают все card refs и их schema/AEAD. Unknown не пустота.
P4 пишет useAsUserTemplate=false; true — Unsupported, не команда очистки значений.
Будущие пользовательские шаблоны могут переносить значения полностью/частично.

## 3. Поля, defaults и лимиты

Field = `[fieldId:bytes16, fieldType:uint, name:text, parameters, value]`.
FieldId ненулевой/random, уникален внутри card, не имя; rename/reorder сохраняют ID.
Fields≤256; name≤128 code points/512UTF-8B, пустые/дубли разрешены. Порядок значим.
Тип неизменяем, включая integer/decimal и text/hidden; новый тип требует нового ID.
Parameters меняются только без потери совместимого value; convertFieldType нет.

|Код|Тип|Parameters|Value/default|
|---|---|---|---|
|1|single-line text|`[mask-or-null,digitsOnly,uppercaseOnly,maxLength]`|String/пусто|
|2|multiline note|null|String/пусто|
|3|hidden text|как text|String/пусто|
|4|URL|null|String/пусто, syntax warning|
|5|Email|null|String/пусто, syntax warning|
|6|date|`[displayMask]`|null либо `[year,month,day]`|
|7|graphical pattern|`[gridSide]`|array точек/пустой array|
|8|integer|null|canonical decimal String/default `0`|
|9|exact decimal|null|`[unscaledIntegerString,scale]`/`["0",0]`|
|10|boolean|null|только false/true, default false|

Строки — strict Unicode scalar strings без S1/NFC/trim/case-fold. Value≤32768
code points/131072UTF-8B; UTF-16 length и aggregate budget ограничить до encoding.
Single-line/hidden без CR/LF, multiline сохраняет переводы строк. URL/Email
сохраняют bounded Unicode строку, включая синтаксически неверные CR/LF: это warning.
maxLength1…32768 считает code points, не UTF-16. Digits-only — ASCII0…9;
uppercase-only — каждый scalar имеет frozen Unicode16 Uppercase property,
validation без conversion. Digits+uppercase и mask+uppercase несовместимы.
URL/Email syntax warnings не блокируют сохранение точной строки.

Integer: `0` либо `-?[1-9][0-9]*`,≤38 цифр, без plus/whitespace/exponent/leading
zeros/negative zero. Очистка numeric — ошибка, не null. Decimal precision≤38 цифр
unscaled, scale0…18, без Float/Double/rounding. Text input≤60chars: optional minus,
целая часть без лишних leading zeros, optional dot и1…18 fractional digits.
Сохраняется scale, mathematical negative zero канонизируется в unscaled `0`;
`-0.50`→`["-50",2]`. PIN/телефон/номер карты — text с leading zeros.
Date: полная Gregorian year1…9999/month/day, без timezone/epoch. Null не равен
технической picker date2000-01-01; показ календаря ничего не заполняет.
Pattern: gridSide2…6, уникальные точки1…side²,≤36, без вставки промежуточных;
пустая последовательность разрешена. Это содержимое, не S1 credential.
CBOR nesting≤16. Graph maps≤32MiB (консервативно1024B/object,≤32768 объектов),
batch≤4096 handles. Reader проверяет все card bodies при открытии, суммарно≤32MiB
card bytes за open. List title cache≤8MiB. Превышение — ResourceLimit;
encrypted scratch в P4 нет. Draft physical estimate≤16MiB UTF-16; до логической
валидации title≤4096 UTF-16 units, icon≤128, промежуточные field strings bounded.
Эти пределы — budgets, не измеренная производительность или гарантия RSS.

## 4. Маски

Text mask≤256 scalars,1…128 digit slots. Только `0` — slot. Литералы — ASCII
space/punctuation/math/quotes/brackets (диапазоны33…47,58…64,91…96,123…126),
кроме backslash, плюс `±×÷−≤≥«»“”‘’`. Иные цифры/буквы/controls/escapes/regex
отвергаются. Raw value — только ASCII digits, без литералов/placeholder spaces;
length≤slots/maxLength. Format ставит space в пустые slots; оформление не делает
пустой raw непустым. Copy возвращает display при разрешённой zone policy и reveal.
Paste последовательно фильтрует digits, позиция ждёт подходящий символ; избыток
игнорируется без предупреждения. Delete raw range сдвигает хвост влево. API использует
raw offsets; visual cursor/selection mapping — будущий UI. Смена характеристик не
фильтрует сохранённый raw, несовместимость отклоняется целиком.
`+0(000)000-0000`: `79218528201`→`+7(921)852-8201`, `123`→`+1(23 )   -    `;
из `Номер счета: 1111 2222 3333 4444`→`+1(111)222-2333`.

Date mask≤64chars: greedy `yyyy` перед `yy`, также `dd`,`mm`; компоненты не
повторяются, yy/yyyy взаимоисключаются, минимум один token. Разделители только
`.`/`-`/`/`/space; неизвестное — ошибка. Mask change не меняет полную дату.
25.11.2006+yy/mm→06/11;22.12.2019+mm/yy→12/19. Нет «достоверных компонентов»,
отдельных month-year типов или вычислений по датам. Calendar/manual/preview — UI позже.

## 5. VFS и единый T

Все команды используют production TransactionCore/P3 owner/T. Encrypted objects/
children меняются COW paths, catalogue append-only. Metadata/body/refs/root публикуются
одной T. Immutable binding включает zone/expected seq/request revision/object revisions/
точные bytes; media дополнительно length+SHA256. Single-use stream проверяется по digest
до публикации; ошибка оставляет только orphan tail. OpId/draft ID/time выдаются один раз;
cached ACK не повторяет даты/counters/effect. Никакого второго протокола хранилища.

Root0/folders1…63, note/card/media в folder63 разрешены. Batch валидируется целиком
по исходному graph, дедуплицирует IDs/убирает потомков выбранного предка; foreign zone,
stale revision/cycle/depth reject целиком. Destination=current parent — NoOp без T.
Trash/RestoreAll/ClearTrash доступны обеим зонам; только compact main-only, он остаётся P5.
Restore numbering/overflow-to-root прежние, возвращается список overflow. ClearTrash
удаляет logical refs, не ciphertext; normal objects/другая зона прежние.

Card title/fields/order/names/parameters/values меняют contentModified; color/icon/pin/
move/trash — metadataModified. ObjectRevision увеличивается один раз за фактическое
изменение, createdAt прежняя. Note title/body/todo/styles — content, cursor — metadata.
NoOp не меняет dates/objectRevision/zoneSeq, ACK request revision учитывается отдельно.
Pin — монотонный uint64, не часы; folders не pin. Sort прежний scalar lexicographic/
id tie-break, card file использует title, sort type/icon добавляет card после video.
Raw unknown icon/settings сохраняются. Missing/trash remember folder→normal root.

Media≤512GiB/count524288/chunk1MiB, bounded chunk-list streaming/range read, AEAD
до выдачи каждого chunk, без whole-media RAM/Base64/plaintext temps. Пустое media —
пустая leaf. Short note/card T не перешифровывает неизменённое media. Импорт/UI/кодеки вне P4.

## 6. Draft, reveal, шаблоны и сообщения

Current/last-wholly-valid/last-ACK различаются. Snapshot defensively frozen;
validation/queue/отдельный write не Saved. Invalid draft допускает заранее принятый
ClosingSave last-valid только если он новее ACK. ACK N не очищает dirty N+1;
older valid не откатывает подтверждённый. Never-saved card RAM-only до первого ACK,
title обязателен,0 полей разрешено. Ошибочных encrypted drafts/частичного save нет.
Revoke немедленно скрывает reveal/доступ; только preaccepted checkpoint по P3.
Новое открытие — hidden всё, conceal `●●●●●` не отражает длину и не идёт в preview.
Unknown/IO не Saved; CommitUnknown автоматически закрывает draft/reveal,
удаляет current/last-valid из session; service token допускает reconciliation,
которое не открывает новую сессию. IO failure оставляет dirty, не Saved.
Pending prepared saves≤16, затем Busy; core payload cache≤32MiB/256 tokens.

Unsaved notice только после явного входа в ту же зону того же контейнера.
Consume требует live authenticated ZoneRepository, не только публичные IDs.
P4 mailbox RAM-only,≤256 пар containerId/zoneId, oldest eviction, без object
details/обычных prefs; после process death/eviction уведомление не гарантируется.
Будущий persistent port требует authenticated encrypted zone state и честного IO failure;
при полном отказе записи гарантии нет. Future UI fix/discard не задерживает revoke.

Built-in empty/bank-card constants instantiate копированием parameters/defaults и
новыми field IDs в самостоятельный draft. Bank: bank/payment-system text, number
mask `0000 0000 0000 0000`, date mm/yy, hidden CVV digits-only, multiline note.
Values пустые кроме numeric0/switchfalse. Пользователь может изменить/снять маску16digits.
User-template UI не реализован; обязательное будущее clearing значений запрещено.

AppMessagePreferences — один install-global Boolean suppress card-editor warning,
без zone/object/container IDs/содержимого/истории. Только сообщение «Ввод в некоторые
поля проверяется на корректность…» dismissible, объясняет потерю после последнего ACK
и сохранность прежней версии. Unsaved notice/прочие confirmations не suppressible.
Reset восстанавливает показ; это не Android notification permissions. P4 — pure policy/
fake prefs, реальные DataStore/button/dialog/notifications позже. Zone copy/screenshot/
remember-folder settings раздельны.
