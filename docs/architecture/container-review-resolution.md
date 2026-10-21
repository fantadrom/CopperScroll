# Copper Scroll: решения после критической проверки

Дата:18 октября2026. Статус: R-01…R-07 и C-01…C-06 закрыты **нормативными
решениями**, а не доказательством отсутствующего движка. Реализация/Android gates
остаются отдельными этапами. Четыре основных документа и дополнение согласованы.

Дополнение P4 от20 октября2026: [норма1.2/карточек](container-p4-extension.md)
явно расширяет1.1. Исторические решения ниже сохранены как решения R/C; новые
CARD/TEMPLATE/MASK gates не выдаются за повторное закрытие старых замечаний.
Результаты реализации P1…P3 находятся в validation reports; ранние фразы «не выполнены»
ниже относятся к историческому docs-only согласованию.

## Основа и область

Работа начата от master `cc9871a4fe6d5965ba000dc016e4b5902faeb6ca`, содержащего
слитый пользователем PR№4, report commit `d937f2a2bf82d3294e25b73f0708cacb84ec215a`.
Сам отчёт оценивает `54e40f38d8622fc683672a96b643bf243c59b3b6`; его выводы,
ссылки SHA и review-materials не изменены задним числом. PR не сливается агентом.

Прочитаны документы/отчёт/малые материалы и совместимый код редактора/lifecycle.
Концепция повторно доступна через Google Drive, modified_time
`2026-10-18T09:21:18.728Z`; частный URL/ID не публикуется. Новое ТЗ имеет приоритет:
смена секрета с новым ZDK, без rewrap-only. Google Doc не редактировался.
AGENTS.md в репозитории/родителях не найден. App/SDK/minSdk/dependencies/manifest
не меняются; NoteSaveHook по-прежнему no-IO, не async ACK.

Нормативные документы:

* [D — design](container-design.md)
* [F — format1.1](container-format-v1.md)
* [T — test plan](container-test-plan.md)
* [P — отдельные задания](container-prototype-plan.md)
* [A — согласованное дополнение](compaction-requirements-addendum.md)

## Решения по замечаниям

| ID | Принятое решение, изменённые разделы | Проверка/условие |
| --- | --- | --- |
| R-01 | F§3.1, D§3, P1: frozen UCD16.0.0 NFC/UAX15rev56, заданный repertoire до/после NFC, raw/normalized bounds; не OS NFC. U+11382 U+113C9→U+11383; unassigned reject | S1-01…04, KEY-06/07; static vectors сейчас, две реальные16.0 реализации/полный conformance в P1 |
| R-02 | D§4/6, P3/6: AccessRevoked немедленно, StopRequested→Quiescent→Released отдельно. ResourceOwner держит FD/lock/lease до фактического окончания, timeout/cancel не доказательство | LIFE-06/09, TX-02/05, MAINT-09; blocking fake и позднее device syscall, нет2с обещания всех RAM copies |
| R-03 | D§8.2/8.5, F§3.2, P5, A: единый main-only автомат compact/rekey, read-only destinations до credentials, confirm/drain/freeze, один S/backup/candidate, whole verify, serialized AcceptSwitch/Revoke и durable journal | MAINT-01…10, COMPACT-02/03/07/09, REKEY-06; нет auto partial/picker continuation, source freshness |
| R-04 | F§4.1, D§5/8.6, P3: отдельный RecoverZoneReadOnly выбранного slot/role; common hash-valid framing, только свой bounded descriptor/catalogue/MAC/AEAD, не чужие refs | RECOVERY-01…04, FILE-03, KEY-05; invalid common framing/свой root reject, никаких writes/пустой зоны/password fallback |
| R-05 | F§9, D§8.2/8.6, P5, A: selected неизвестная ref-closure запрещает всю операцию до backup; unselected full copy только known outer/envelope/MAC policy; неизвестный required/container запрещает writer | FILE-04/05, SCHEMA-02, REKEY-04, COMPACT-04; без GC unknown deps/partial fallback |
| R-06 | F§6: folder payload=null/f6, обязательные fields/defaults/pin invariant, stable icon IDs, sortMode1…4/viewMode1…3 и unknown preservation; P1 | SCHEMA-01/03, VFS-04; статические CBOR bytes и независимые tiny encoder/decoder сейчас, engine cross-codec позже |
| R-07 | D§4/8.1/8.5, P3/6: ExistingWriteDurable, DurableCreateIdentity, DurableLocalState раздельны; profile authority/build/root/volume/backend/API. File force не namespace commit | DUR-01…04, SAF-01…03; unsupported до создания/Unknown после неопределённого IO, ни одного production profile пока |
| C-01 | F§3.2, D§3/8.3, P5b: ChangeSecret с новым независимым ZDK и полным bottom-up re-encrypt цели в новый URI, old target IDs/envelope/obsolete не переносятся; оба headers новые; другой key/raw/logical прежние | KEY-04/05, TX-07, REKEY-01…08, MAINT; старый ZDK проверяется на новой цели и future records, retained old copies доступны старым secret |
| C-02 | F§2.2/4.1, D§3: явно принят selective per-zone replay/splice, не только полный rollback; публичный generation не anti-rollback | ROLLBACK-01; оба MAC могут быть верны, не обещать свежесть пары; внешнее trust state вне текущего portable guarantee |
| C-03 | F§2.3, D§8.3/8.4, A: raw records+logical descriptor[0..16] unselected побайтны; catalogue pages/field17 rebuild, не raw page identity | COPY/COMPACT-01/06/10, REKEY-03; multiset/ranges/logical bytes и поздний independent open |
| C-04 | F§5, D§5, P3: opId memory-bound к immutable request/epoch/zone/expected seq; root256 только hint; lost token/window → UnknownOperation/Conflict, не replay/ложный ACK | RETRY-01…03, NOTE-05, TX-01/02; stable draft ID, нет бессрочного exactly-once или command plaintext journal |
| C-05 | F§8, D§8.6, T/P5, A: S−C отдельно от2S+C/прироста S+C, retained source+backup+scratch/старые copies, C≥S и unknown allocated честно | COMPACT-05, MAINT-10, IO measurements; удаление одного source не объявлять экономией относительно S |
| C-06 | D§6.1, T/P6, A: explicit top-resumed+visibility+interactive policy; multi-resume/focus/own dialogs/IME/permission/shade/config handoff/picker различаются | LIFE-01…09, LEAK-02/04; Home/screen-off revoke, rotation один worker, не один onStop |

## Новое требование ChangeSecret

Повторно подтвердить current target secret; new twice/inequality/отличие от old S1.
Main-only управление, забытый decoy не восстанавливается main. Один stable S под
общим lease, одна checked whole backup, один свежий candidate. Target ZDK новый,
все reachable данные с trash/settings/metadata/note/media перешифрованы с новыми
recordId/nonce/derived keys и refs. Object IDs/props/dates/objectRevision прежние,
служебный target zoneSeq+1. Другая зона сохраняется целиком без её пароля и смены
её ZDK/logical state. ContainerId прежний, instanceId новый, generation=S+1.

Оба headers candidate только нового состояния; target obsolete/old envelopes/
source headers не копируются. Full verify и durable switch перед Changed.
До AcceptSwitch failure оставляет source со старым паролем; после принятия Unknown
разрешается по journal, без ложного успеха. Retained source и backup имеют прежнюю
защиту, их удаление отдельно. После успеха старые key/session caches закрыты,
новый вход отдельно. Preferred input — local UI настройка, не rewrap/смена секрета.

Режим compact сохраняет выбранные main/decoy/both и byte-copy без смены ключей;
обе existing default selected, unselected full catalogue. Старый COMPACT-02 больше
не разрешает decoy пользовательский доступ; symmetry helper проверяется отдельно.
Правила обслуживания содержатся непосредственно D/F/T/P, A их не переопределяет.

## Совместимость и существующие файлы

Принята **containerMajor1/minor1** вместо прежнего draft1.0. Fixed header layout
65536×2, record header76, descriptor18 fields, refs и AEAD domains не расширены;
в header явно изменено значение minor0→1. Envelope/secretEncoding/record/zone/
object/settings/note/media версии1 определены в контексте container1.1. Это
отдельное обозначение согласованной нормы S1/folder/enums, не два разных «1.0».
Само rekey/selective compact новых полей не требует: offsets вне LogicalRef,
instance/generation вне Krecord/AAD, physical catalogueRoot вне logical MAC.

`git ls-files '*.cscroll'` и поиск доступного workspace не нашли engine files;
в app нет storage engine, только session-only editor/no-IO hook. Это не утверждение
обо всех внешних файлах пользователя. Выпущенных production1.0 файлов/fixtures
не установлено, автоматическая миграция из воображаемого production не добавлена.
Reader1.1 не пишет в draft1.0 и не угадывает старую NFC/folder schema. Можно явно
сохранить raw bytes без статуса healthy/cryptographically verified. Если реальные
экспериментальные draft-файлы будут предоставлены, импорт проектируется отдельно
после их инвентаризации и определения producer rules.

## Проверочные материалы и выполненная работа

Новые малые материалы — [reconciliation-materials](reconciliation-materials/README.md).
Исторические [review-materials](review-materials/README.md) и report сохранены.
Текущие small checks проверяют static fixture bytes/decoder, согласованность
S1 expectations и abstract maintenance/rekey graph models. Они не реализация
storage, не crypto audit и не Android conformance. Проверяется diff, ссылки/якоря,
таблицы/layout, уникальность test IDs и отсутствие старых нормативных критериев.
Фактические команды/результаты фиксируются в README материалов перед публикацией.

Требования REVIEW-01…12 из отчёта перенесены:01→S1/SCHEMA;02→MAINT-01/COMPACT;
03→COMPACT-09/REKEY-04;04→COMPACT-07/MAINT-04;05→MAINT-03/07;
06→MAINT-05/06;07→LIFE-06/MAINT-09;08→LIFE/MAINT-02;
09→SCHEMA-02/COMPACT-04/10;10→COMPACT-05;11→RECOVERY/DUR/ROLLBACK/REKEY;
12→RETRY/FILE-05. Это traceability, не вторые test IDs.

## Оставшиеся условия и готовность

**Первое отдельное задание P1 готово к началу.** Оно включает выбор/проверку
Unicode16 implementation, независимые codecs/fixtures и bounded parser, без всего
приложения. Дальнейшие P2…P6 имеют входные/выходные критерии. Нормативный выбор
rekey/compact/S1/recovery/unknown/journal/foreground закрыт.

Не выполнены и остаются обязательными: полный S1 conformance двумя16.0 реализациями,
pinned crypto vectors/API31/R8/advisories, KDF/device benchmarks, parser fuzz,
real engine round-trips/rekey old-key negative, неотменяемый IO/locks на Android,
provider/root/volume namespace+local-state power-loss tests, worst-case VFS/scratch,
IME/leak/lifecycle/OEM transfer и независимая экспертиза реализации. Allowlist
production providers пуст,2с secret-destruction guarantee отсутствует.
Android build/device tests для docs-only не запускались. Нерешённых требований,
блокирующих формулировку P1, нет. Будущие artwork заметочных icons/локализованная
сортировка UI не определены концепцией до деталей; wire default IDs и comparator
для headless fixtures уже заданы, расширение отдельным продуктовым решением.
