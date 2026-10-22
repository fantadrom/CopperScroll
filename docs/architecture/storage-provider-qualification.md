# P6a.2: граница квалификации Android storage

22.10.2026. Исследована база `d9891659f8bd6169185c73b52dd9ce99b82da6ea`
(merge PR13, ancestry принятого `91ff18b` проверена). Это исследование контрактов,
не сертификат прошивки. Production registry **пуст**; импорт receipts не меняет policy.
[Ledger v1](../../tools/p6a2/evidence-ledger.properties),
[семантика ledger/gate](../../tools/p6a2/README.md), [отчёт](validation/p6a2-report.md).

## Область и версии источников

Только primary shared-storage ExternalStorageProvider на Honor ABR-LX1/API35 и
Virtual_Pixel_6/API37, плюс app-private credential-encrypted noBackup каждого.
USB/SD/cloud/другие OEM endpoints — Unknown, вне исследования. Переданные сведения
P6a.1 не содержат точного OEM storage/mount/firmware контракта. Build/version
не угадываются по модели телефона, API или authority. Root hashes прошлых probes —
метки наблюдений, не registry entries и не установленные backend/volume identities.

Первичные исходники проверены через официальные GitHub mirrors 22.10.2026:

- **A15**: AOSP frameworks/base `android-15.0.0_r1`, commit
  `4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3`.
- **M15**: AOSP MediaProvider того же tag, commit
  `8a53c6d378a9c6ff61ef751d260a1281985b93fe`.
- **L66**: upstream Linux `v6.6`, commit
  `ffc253263a1375a65fa6c9f62a893e9767fbebfa` (annotated tag
  `5260836abb7056beed3f3f0d0e4262c11f36f0d0`). Это условная reference implementation,
  не установленная версия kernel Honor/эмулятора.
- **MP513**: Linux man-pages project, release `man-pages-5.13`, `fsync(2)` и
  `rename(2)`; более ранний стабильный API reference, не описание новой прошивки.

Проверка tag `android-17.0.0_r1` в AOSP frameworks/base mirror вернула404.
Android17 source/device contract в доступных материалах **не установлен**. A15 не
доказывает ни идентичность Honor Android15, ни Android17. Не делаем выводов о
несуществовании Android17 по отсутствию tag в одном mirror. Sources скачаны в
ignored `work/p6a2/sources`, в PR только проверяемые ссылки/выводы.

## Требуемые свойства и цепочка

| Свойство | Первичный источник / точный символ | Вывод и предел |
| --- | --- | --- |
| Seekable FD / режим | A15 [DocumentsProvider.openDocument](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/provider/DocumentsProvider.java#L783), [ContentResolver.openFileDescriptor](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/content/ContentResolver.java#L1610), [ParcelFileDescriptor.parseMode](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/os/ParcelFileDescriptor.java#L674) | r/w могут быть pipe/socket; rw подразумевает seekable file, rw не содержит truncate, rwt содержит. Проверяем реальные fstat/F_GETFL/lseek; флаг документа не заменяет FD. Контракт provider всё равно обязателен |
| Конкретный open primary | A15 [FileSystemProvider.openDocument/openFileForRead](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/com/android/internal/content/FileSystemProvider.java#L505) | rw открывает разрешённый provider File через PFD; read может проходить MediaStore/openTypedAssetFileDescriptor. Это не доказательство единственного одинакового IO пути для всех modes/версий |
| Root/volume/backend | A15 [ExternalStorageProvider.updateVolumes](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java#L180), getDocIdForFileMaybeCreate/getFileForDocId | primary — логическая root-метка; provider знает internal/visible paths и VolumeInfo. Клиентское URI/root ID не доказывает фактическую mount/FS/flush цепочку. Публичные storage volume сведения не предоставляют требуемый атомарный pre-open alias resolver |
| Proxy/deferred semantics | A15 [StorageManager.openProxyFileDescriptor](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/os/storage/StorageManager.java#L1988) | Публичный API позволяет proxy FD с callbacks через AppFuse. Regular/seekable не исключает проксирование или remote/deferred backend. Не утверждаем, что данный primary использует proxy; это контрпример универсальному выводу из fstat |
| FUSE, если этот слой реально применим | M15 [FuseDaemon.do_sync_common/pf_fsync/pf_fsyncdir](https://github.com/aosp-mirror/platform_packages_providers_mediaprovider/blob/8a53c6d378a9c6ff61ef751d260a1281985b93fe/jni/FuseDaemon.cpp#L1834), L66 [fuse_fsync](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/fs/fuse/file.c#L558) | M15 пересылает fsync/fdatasync backing FD и errno. L66 flushes dirty pages, но ENOSYS может запомнить как no_fsync и вернуть0. Не переносим эту ветвь на неизвестную прошивку; сам syscall success не устанавливает реализацию daemon/backing storage. rw primary может идти через internal path без этого FUSE слоя |
| Долговечность fsync | MP513 [fsync(2), DESCRIPTION/ERRORS](https://github.com/mkerrisk/man-pages/blob/man-pages-5.13/man2/fsync.2#L68), L66 [ext4_sync_file](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/fs/ext4/fsync.c#L121), [F2FS fsync_mode/barrier](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/Documentation/filesystems/f2fs.rst#L247) | fsync обещает file data/metadata до device report, ошибки проверяются. Это условно на FS/mount/barriers/device honesty. Directory entry требует отдельного directory sync. F2FS posix/strict/nobarrier различны; неизвестный device/mount не заменяется названием FS |
| Create / lost reply | A15 [FileSystemProvider.createDocument](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/com/android/internal/content/FileSystemProvider.java#L248), [DocumentsContract.createDocument](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/provider/DocumentsContract.java#L1371) | Sanitized/unique suggested name + createNewFile/mkdir; фактический URI authoritative, не requested display name. На этом пути нет обязательного parent fsync/namespace-commit ACK. Null/exception после IPC может означать созданный неизвестный artifact; не искать/удалять/повторять по имени |
| ID и pre-open identity | A15 [COLUMN_DOCUMENT_ID](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/provider/DocumentsContract.java#L305), [ExternalStorageProvider.getDocIdForFileMaybeCreate](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java#L384) | ID opaque, durable для долгих grants; это не power-loss namespace ACK или вечный inode ID. A15 строит root:relative path; replacement может сохранить URI, rename изменить ID. Fstat dev/inode доступен после open, может повторно использоваться/измениться при remount/reboot; не общий pre-open identity contract |
| Locks/close | L66 [locks_remove_posix](https://github.com/torvalds/linux/blob/ffc253263a1375a65fa6c9f62a893e9767fbebfa/fs/locks.c#L2540), CopperScroll AndroidSafStore/SafLifetimeLease/ResourceOwner | POSIX locks связаны с процессом/файлом, закрытие другого FD опасно для lock. Стабильный app-private guard держится через document reopen; document FD не дублируется. Alias resolver нужен ДО open, иначе даже проверка alias может снять lock. Advisory locks не запрещают внешнего noncooperating writer; freshness headers/generation/full hashes остаются обязательны |
| Local namespace / first create | A15 [ContextImpl.getNoBackupFilesDir/ensurePrivateDirExists](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/app/ContextImpl.java#L818), [Context.getNoBackupFilesDir](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/content/Context.java), MP513 fsync/rename | noBackup означает исключение backup, не durability. Framework mkdir не делает parent force. Наш init syncs owned dir/parent/noBackup parent, marker file+dir; publication temp/full/file force → same-dir rename → parent force → decode/readback → ACK. Нужен отдельный local FS/device contract |
| Atomic replacement / ACK ambiguity | MP513 [rename(2), DESCRIPTION/BUGS](https://github.com/mkerrisk/man-pages/blob/man-pages-5.13/man2/rename.2#L68), A15 [AtomicFile](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/util/AtomicFile.java) | Atomic visibility rename не равна durable publication. Не переносим NFS lost-reply семантику на локальную FS как установленный дефект; conservatively rename-started failure → StateUnknown. AtomicFile сам по себе не заменяет lifetime lock/parent-force/model. Один binding+journal record, не две prefs |
| Delete при replacement | A15 [FileSystemProvider.deleteDocument](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/com/android/internal/content/FileSystemProvider.java#L350), DocumentsContract.deleteDocument | Нет compare-inode/generation аргумента в deleteDocument. Stat/checkIdentity перед удалением оставляет TOCTOU. Текущий SAF cleanup теперь отказывает до delete даже для известного own artifact; retained статус, никаких поисков по имени |

## Три решения по каждому пути

| Окружение / путь | ExistingWriteDurable | DurableCreateIdentity | DurableLocalState | Production admission |
| --- | --- | --- | --- | --- |
| Honor15 primary, ExternalStorageProvider | UNKNOWN: backend + pre-open identity не доказаны | UNKNOWN: namespace force/ACK не установлен | Отдельный app-private путь ниже | REFUSED; read-only regular FD при доступном grant, иначе отказ |
| Emulator17 primary, ExternalStorageProvider | UNKNOWN: implementation/backend/VM model не установлены | UNKNOWN: namespace contract не установлен | Отдельный app-private путь ниже | REFUSED |
| Honor15 app-private noBackup | N/A | N/A | UNKNOWN: протокол реализован/model+P6a.1 process evidence; mount/storage/power-loss evidence нет | Maintenance/switch запрещены |
| Emulator17 app-private noBackup | N/A | N/A | UNKNOWN: тот же пробел, отдельно VM/host caches | Maintenance/switch запрещены |

В каждом applicable поле ledger собственный evidence ID/version, conditional source
contract, prerequisites, processExperiment/powerExperiment. Отсутствующие build/backend
не wildcard. Новые реальные receipts добавляют точные observed hashes/version/API,
оставляя volume/backend Unknown. Их нельзя компилировать как универсальный профиль.

## Вывод по resolver, forceIdentity и публикации

Публичный SAF primary не предоставляет подтверждённый в этой работе атомарный
pre-open identity/alias resolver и явный parent namespace force. Повторный file
fsync, URI readback, provider UID или durable-ID wording не заполняют эти пробелы.
Нынешняя local environment метка app-private-fd:<dev> — лишь наблюдение, не установленный backend; будущий local admission требует отдельного trusted resolver/contract.
Production resolve/contract/localContract по-прежнему null, forceIdentity —
UnsupportedDurability. Отдельные gates проверяют source/backup/candidate/local;
допуск source не распространяется на destination. Нет нового resolver с boolean
из settings, cache receipt, догадкой path, `/proc/self/fd`, root, hidden APIs или
MANAGE_EXTERNAL_STORAGE. Actual provider UID и external explicit grants независимы.

Для будущего reviewed registry scope реализации должен описывать версию API/provider,
конкретный backend/failure model и namespace/identity protocol; выбранный endpoint
сопоставляет trusted resolver. Root hash пользовательского прогона — evidence key,
а не compiled selector. Инвалидация необходима при смене implementation contract,
OS/provider build/version, root/volume/backend/remount; случайный runId/PID/mtime
не должен определять переносимость контракта. Сейчас такого resolver/evidence нет,
поэтому существующий exact matcher не расширяется wildcard/caller approval.

## Local state, сбои и независимые классы evidence

AppPrivateLocalState не публикует два независимых binding/journal. Отдельный
state.lock не rename-replaced; process guard + FileLock удерживаются до close,
resources закрываются с primary/suppressed ошибками. Lock directory/lock file не
источник истины binding и не признак Fresh; потеря после reboot не разрешает
сброс записи. Fresh только новая собственная namespace без marker/state; при
повторном open без записи — Unresolved. Marker/checksum/version failure — явный
отказ, не empty state. Первая публикация syncs directories/marker до state ACK;
после marker инициализация не может тихо повториться. Незавершённые temp files не
выбираются по mtime. Неоднозначный rename/parent force/readback/ACK блокирует writer;
reconcile сам не выбирает URI и не обходит P5 fresh credentials/whole verify.

Model tests P3/P5a/P5b исследуют потерю volatile bytes/namespace на каждой фазе,
старые committed ranges, whole snapshots, old/new credentials, lost switch ACK,
main-only, чужую зону, blocking IO/revoke/late callbacks. P6a controlled suite
добавляет настоящие IPC/FD/grants, owner-lock/reopen, provider retry и persistent
unavailability; это bounded harness contract. Новые real-root process/reboot
сценарии проверяют raw synthetic scratch и реальный local publisher, не называют
raw scratch контейнером/VerifiedCopy и не запускают запрещённый workflow на root.

| Evidence class | Здесь | Что остаётся |
| --- | --- | --- |
| Model/fault injection | Полные доступные P3–P6a regressions | Не физическое device поведение |
| Worker/provider process death | P6a.1 принят пользователем; новый explicit controller требует fresh PID/session; новый runtime NOT_RUN агента | Kernel/cache остаются живы, не power loss |
| Orderly reboot | Реализован resumable prepare/resume; новый BOOT_COUNT обязателен; NOT_RUN агента | Штатный shutdown может flush, не внезапная потеря питания |
| Controlled VM/storage interruption | NOT_RUN; безопасного storage-fault стенда нет | Нужны определённые guest disk/cache/host flush semantics, checkpoint, реальный reset затронутого слоя, свежая VM boot session и recovery oracle |
| Physical interruption | NOT_RUN; безопасного отдельного device стенда нет | Нужны документированный накопитель/FS/mount/barriers и безопасное аппаратное воздействие без личных данных; обычное выключение эмулятора этого не доказывает |

Конечное число PASS — evidence выполненных случаев, не доказательство всех отказов.
Смысл readback/hash — проверка bytes/целой записи сейчас; без namespace/device
контракта нельзя повысить Unverified/CopyUnknown до VerifiedCopy/Activated.
При достаточном протокольном ограничении требуется архитектурный выбор:
[ADR-001](adr/001-saf-durability-boundary.md). Текущий PR его не принимает за пользователя.
