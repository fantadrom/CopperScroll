# Copper Scroll: поэтапные задания на прототип контейнера1.1

Статус: P1…P3 приняты; P4 расширяет нормы [карточками/1.2](container-p4-extension.md).
Ниже базовая последовательность этапов; P4 extension определяет актуальный scope. Основа:
[design](container-design.md), [формат1.1](container-format-v1.md),
[матрица проверок](container-test-plan.md), [решения R/C](container-review-resolution.md).
Сохранить minSdk31/SDK37/AGP9.4.1/Gradle9.6.0/Java11 и текущую модель редактора.
Исторический docs-only этап менял документацию/малые fixtures; P4 включает app core,
но не рабочий UI/manifest/dependencies.

## 1. Организация работы

Каждый P1…P6 — отдельное поручение, небольшая ветка/PR с измеримым выходом.
Следующий этап начинается после приёмки нужного предыдущего, не всё приложение
одним запуском Codex. Headless engine не подключать незаметно к Unit NoteSaveHook.
Synthetic binary streaming входит; media UI/кодеки нет. Пользовательские файлы
до device/security gates запрещены. Исторический отчёт не норма старого draft1.0.

Core: `com.fantadrom.copperscroll.storage.{format,crypto,vfs,io}`, Android отдельно
`storage.android`; без Compose/View/Activity внутри core. Можно оставить app module.
Зависимости выбирать по совместимому artifact/license и фиксировать version catalog
только в реализации; не менять SDK ради обхода проблем. Готовая CBOR-библиотека за
strict schema adapter: адаптировать encoding к1.1, не wire к библиотеке.

## 2. Нормативные контракты API

| Тип | Обязательство |
| --- | --- |
| RandomAccessStore | Checked Long/full positional IO/appendAligned/force/truncateOptional; short IO различим; один ResourceOwner закрывает FD после in-flight quiescence |
| ContainerReader | Framing/selectSnapshot/openZone/VerifyAll1.1; explicit read-only recovery отдельно от password failure |
| ZoneSession | Один ZDK handle/epoch; mutate с expectedZoneSeq/revision/opId; без raw-key/toString; revoke запрещает новые пользовательские команды |
| ContainerWriter | T append/COW→force→inactive header→force→readback→ACK; другая logical зона прежняя; Unknown запрещает writes |
| MutationCoordinator | Выдаёт immutable token/opId, RAM binding к payload/expected seq/epoch; changed payload reject, lost token/outside window reconciliation без replay |
| CredentialManager | Fixed S1/UCD16, whitelist, expected-target old verification/new twice/inequality; new secret всегда новый ZDK, no user rewrap-only |
| ZoneRepository | Folder/note/batch/trash/restore/clear/settings, depth/parent/ID; editor semantics, raw unknown display enum/icon preservation |
| SnapshotCopier | Snapshot-bound committedLength copy/hash/readback/namespace proof; keyless зона не называется AEAD verified |
| MaintenanceContext | Borrowed/owned main/decoy и old/new target handles, lease, bounded pages/encrypted scratch; не UI repository; Stop→Quiescent→Released |
| MaintenanceCoordinator | Main gate первым; destinations/credentials/confirm/drain/freeze/preflight/один backup+candidate/full verify/AcceptSwitch+durable binding |
| Compactor | Selected main/decoy/both, default both; selected closure raw copy, unselected full catalogue; ZDK/zoneSeq прежние |
| ZoneRekeyer | Новый target ZDK и bottom-up всех reachable records/refs/MAC, target seq+1; другая raw/logical зона прежняя без её key |
| LocalStateStore | Одна atomic durable noBackup запись binding+journal, source/candidate fingerprints/stages; не SharedPreferences.apply ACK |
| SafStoreFactory | OpenOriginal/Create/ImportCopy/RestoreCopy раздельно; profile root/volume/backend, ExistingWriteDurable отдельно от DurableCreateIdentity |
| SessionCoordinator | UI epoch отдельно от IO owner; узкий заранее принятый ClosingSave; foreground таблица и Locked picker tokens |
| EditorStorageAdapter | NoteData/NoteDocument↔StorageNoteDTO, stable ID/title/icon/dates/cursor, immutable revision snapshot+async ACK; не HTML/spans/Base64 |

Ordinary results: Committed(generation,zoneSeq,revision,objectId), NoOp,
ValidationError/Conflict/InvalidOperationToken/UnknownOperation/ResourceLimit/
AccessLost/UnsupportedVersion/UnsupportedSecretEncoding/UnsupportedDurability/
CorruptData/IoFailure/CommitUnknown. AppliedHint не полный ACK/Committed.
Maintenance results: MaintenanceCommitted(newUri,instanceId,kind,selection-or-target,
sizes), AbortedBeforeSwitch, CopyUnknown, SwitchUnknown, ReconciliationRequired/
Unreported. После AcceptSwitch не возвращать «точно отменено» при неизвестном IO.
Error/toString без secret/text/key. Credentials не data class, buffers memory-only,
destroy/finally после последнего использования; физическое стирание JVM/IME не обещать.

## 3. P1 — нормативные codecs, parser и fixtures

**Вход:** приняты документы1.1 и однозначный S1. Движка/production1.0 файлов в repo
нет; migration из них не придумывать.

**Задача:** header/descriptor/record/catalogue/refs, strict CBOR схемы, folder null,
enums/defaults/icons, ranges/overflow/overlap/cycles/budgets и unsupported policies.
Выбрать поставляемый pinned UCD16 normalizer/repertoire. Системная версия NFC не
норма. Artifact/license/data version и conformance — результат P1, не изменение
самого S1. Независимый structural inspector только synthetic framing.

**Выход:** codec-часть SCHEMA-01/03 и FILE-04/05, byte/repertoire-часть S1-01…04, точные
[golden fixtures](reconciliation-materials/README.md), cross-codec чтение;
полный Unicode16 NormalizationTest двумя независимыми реализациями с проверенными
таблицами16.0.0. Fuzz/malformed bounded без OOM/hang/range escape. System Unicode15
отклонён как неподходящий capability. Если normalizer не прошёл, P1 не принят.
Это отдельное первое задание, а не запуск всего engine. Create/open часть
S1-01/KEY-01 выполняется после P2/P3, unknown-selected maintenance SCHEMA-02 в P5;
P1 не объявляет эти интеграционные ветви пройденными.

## 4. P2 — готовые примитивы и независимые ключи

**Вход:** P1 codec/S1 принят. BC1.86 кандидат, ещё не Android-сертификат.

**Задача:** JCA AES/GCM, BC lightweight Argon2/HKDF/HMAC без регистрации provider;
known-answer vectors, fresh RNG/record keys, независимые envelopes, expected-zone
unwrap, ambiguity и equality. Prepare нового target ZDK/envelope в RAM без engine IO;
cleanup Kraw/Kwrap/credentials/handles/crypto states/caches в finally.

**Выход:** KEY-06/07/09/10, credential-часть KEY-05 и primitive/record-часть
CRYPTO-01…03 на pinned artifact, API31+ и release/R8; одинаковый вывод ключей
между чистыми instances, KDF bounds/один worker/RNG failure. Полный файл KEY-01/02
проверяется после P3/P4, полная смена KEY-04/05 — в P5b. Замеры p50/p95/RSS/
thermal/OOM отдельно;64MiB/3/1 не объявлять оптимальным заранее.

## 5. P3 — транзакционное ядро и модель отказов

**Вход:** P1/P2; сначала JVM/fault-store без claims о произвольном SAF.

**Задача:** T/slots/seq/opId/revision/CommitUnknown, drain pending, single IO owner/
lock/lease, отдельные AccessRevoked/Quiescent/Released. LocalStateStore моделирует
file bytes и namespace раздельно; binding+journal одна transaction. Реализовать
RecoverZoneReadOnly по своему MAC/каталогу и common framing, не old-password fallback.
Неотменяемый blocking fake, не только cancellable delay; timeout не снимает lease.

**Выход:** core-части TX/RETRY/RECOVERY/DUR-01/03 на synthetic roots и каждой фазе T, old/new snapshot
или Unknown, ни ложного ACK/replay/дубликата. После restart без live token нет
автоматического destructive retry. Delayed syscall+revoke+второй процесс не создают
двух writers, owner closes ровно раз. Полные VFS/media ветви этих ID допроверяются после P4/P5, device gates до P6.
Проекции тестов перечислить в отчёте этапа, не помечать весь ID выполненным по части.

## 6. P4 — VFS, карточки, редакторный codec и streaming attachments

**Вход:** P3, bounded parser/crypto/T. UI ещё session-only.

**Задача:** сначала спецификация1.2/card schema и fixtures без потери1.1, затем
headless card fields/masks/date/draft/templates/message policy по расширению.
Encrypted COW objects/children, stable ID/root/trash/depth63, batch
normalization/restore overflow, settings/counters/raw enums/icons. StorageNoteDTO
сохраняет nullable todo, ordered overlapping styles/UTF-16, empty/title/cursor/dates.
Synthetic binary chunks/chunk lists streaming, без Base64. Unknown schema не пустой root.

**Выход:** VFS/TRASH/NOTE/MEDIA matrix с independent simple model oracle,
randomized commands; short-note save не меняет media ciphertext/hashes. Измерить
extreme validation/traversal/RAM/cache/scratch, не обещать million-object UX по fixture.

## 7. P5 — копирование и обслуживание (два отдельных PR)

**Вход:** P1…P4, model-tested owner/local-state. До P6 только synthetic/fault IO;
production source/destinations требуют device profiles.

**P5a:** SnapshotCopier и main-authorized selective compact через общий coordinator,
выбор/default both, expected decoy secret до файлов, один S/backup/candidate/full
verify. Unselected raw/logical bytes без key, обе selected closure/AEAD. Direct decoy
deny отдельно от symmetric helper. Source freshness/journal/AcceptSwitch vs Revoke,
Unknown/restart без скрытого выбора. Source и новая verified backup retained.

**Выход P5a:** COPY/COMPACT/MAINT model P0, faults между зонами/на switch, честные
S/C/2S+C/scratch sizes. Unknown selected refs — отказ до backup, без partial fallback.

**P5b:** ChangeSecret main/decoy через тот же автомат; current target/new twice/
inequality, независимый новый ZDK/envelope, bottom-up всей reachable цели.
IDs/props/dates/objectRevision прежние, target zoneSeq+1; другая raw/logical зона
и ZDK прежние без её пароля. Оба headers только новые, старые target records/
envelopes не попадают в candidate. Same S1 — UnchangedSecret; preferred UI отдельно.

**Выход P5b:** REKEY-01…08 и applicable MAINT. Independent offline reader со старой
копией/secret/ZDK не читает обновлённую цель и последующие данные, новым ключом
полная логическая эквивалентность. Source/backup имеют прежнюю защиту. Никакого
user rewrap-only. New password changed только после whole verify+durable switch.

Migration в P5 только safe dispatcher и synthetic test-only известный переход,
не выдуманный production1.0; не автоматическая часть compact/rekey.

## 8. P6 — Android, lifecycle, затем UI (отдельные задания)

**P6a, вход P3/P5:** SAF FD owner/grants/gate для source/backup/candidate roots,
device faults/namespace/local metadata. Доказать ExistingWriteDurable,
DurableCreateIdentity, DurableLocalState на конкретных authority/build/root/volume/
backend/API. **Выход:** SAF/DUR P0 и измеренные профили; до этого production
allowlist пуст. Seek/probe не подтверждают power-loss.

**P6b, вход P6a:** foreground таблица design§6.1: multi-resume/top-resumed/visibility/
focus, Home/screen-off/own dialogs/rotation/resize/picker. Настроить manifest/XML
и проверить OEM device transfer до real data. Нет plaintext SavedState/logs/temps,
закрыты IME/autofill/content capture/clipboard/recents leaks. **Выход:** LIFE/LEAK P0,
blocking IO+late completion/revoke-switch на устройстве; cleanup после quiescence,
без обещания2с уничтожения всех RAM copies.

**P6c, отдельное последующее поручение:** только после P1…P6b подключать engine к
рабочему UI. Async snapshot/ACK, dirty N+1 при ACK N, период7с, ошибки без ложного
Saved. Title/date/unsaved indicator по концепции, не demo-замена editor. Main-only
password/compact UI+coordinator tests, both default; service decoy не пользовательская
session. Старые Unit-hook tests отражают memory-only поведение, обновить их.
Успешное обслуживание закрывает старую сессию; новый вход в выбранный файл отдельно.

**Выход P6c:** реальные integration tests без регрессий editor, честные Unknown/new
path/sizes, никаких late picker/candidate auto-open. Real data после release gates.

## 9. Передача каждого этапа

Новая ветка/проверяемые commits/PR без merge. Для Kotlin/manifest/dependencies —
debug build, unit tests/lint, instrumented API31+ на доступном устройстве и crypto
release/R8. Не записывать успех отсутствующего runner. Safe reports выполненных
ID/seeds/faults/KDF/provider metrics в `docs/architecture/validation/`, без секретов.
Device/security gates перечислять отдельно. Итоговая матрица106 ID закрывается
по всем применимым ветвям после P6; ранний partial subcase не полный pass ID. Не выпускать unstable1.1 files без
experimental маркировки; wire изменения сначала новой версией+fixtures, не двумя
несовместимыми реализациями под одним minor.
