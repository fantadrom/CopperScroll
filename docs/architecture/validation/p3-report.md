# P3 — транзакционное ядро и модель отказов

Дата: 19–20 октября 2026. Ветка `codex/copper-scroll-p3-transactions`.
База — актуальный master `cbc4d44dd7928d753374503ee1eb566d528332b0`, tree
`95d009ecf8005d2a4c35c9679c704b5c1e8b9037`: пользовательский merge PR №7,
head P2 `134dbbb0cee49d3623f40f557e6adf837c206e5e`. GitHub API подтвердил merge,
до изменений локальное дерево совпало с этим tree и было чистым.
Прочитаны design/format/test/prototype/review-resolution, отчёты P1/P2 и их код/tools.
AGENTS.md в репозитории/родителях не найден. SDK37/min31, Gradle9.6.0,
AGP9.4.1/Kotlin2.2.10/Java11, зависимости, manifest и wire1.1 сохранены.

**Статус: P3 реализован на синтетических данных; Android runtime ещё не выполнен.**
Это не SAF/UI/VFS/compact/rekey, не профиль физической долговечности и не разрешение
на реальные данные. Вход P3 явно разрешён новым заданием независимо от незавершённых
KDF benchmarks. Полученные пользовательские P1/P2 runtime receipts внесены в
[p2-report.md](p2-report.md):8/7 PASS на Honor15 и эмуляторе17, одна APK пара,
bundle `d7fec5127795aecc51acdfd4d164b34d7a03089d5d2964b6e8db2cdf5d8d1d1c`.
Это пользовательские результаты предыдущей базы, не Android PASS новой P3 сборки.

## Реализованные контракты

| Компонент | Контракт и граница |
| --- | --- |
| RandomAccessStore | Checked Long size/ranges/alignment, full positional read/write, append в actual EOF с zero padding, force, optional truncate, exclusive lock и единственный close owner |
| FileChannelStore | Positive short IO loop, zero/EOF → Eio, IO exception без чувствительного cause; cooperative FileLock. Второй FD отклоняется до открытия; fileKey объединяет hardlinks, при отсутствии fileKey fallback canonical path имеет более узкую гарантию |
| FaultMedium/FaultStore | Раздельные volatile/durable bytes, partial write/force, before/after read/write/force/identity; Error/NoSpace без false ACK. Default cap8MiB, window stress явно64MiB |
| FaultNamespace | Document identity/имя не durable от file force. Создание без synthetic DurableCreateIdentity отвергается до создания; namespace sync отдельно от data force |
| CowCatalogue | Нормативные pages16KiB, unsigned keys/fanout128, COW insert/split только изменённых paths; обычный commit сохраняет obsolete record entries; bulk build для новой зоны |
| ContainerReader | P1 framing/selection/refs/catalogue/ranges/hash + P2 descriptor MAC/AEAD + normative root/settings/index/object metadata; parent/children/cycles/depth checks. Open не равен VerifyAll |
| RecoverZoneReadOnly | Явные slot/generation/role, common checksum/framing/IDs; bounded decode только своего descriptor, свой catalogue/graph/MAC/AEAD. Чужие CBOR/refs не зависимость; result read-only, writer не принимает recovery view |
| TransactionCore | Create main-only, Add independent decoy и SetSettings одной зоны. Новый root/settings по codecs1.1, пустые objects/children индексы при create/add; полные команды VFS/notes/media — P4 |
| OperationToken | Случайный opId, epoch/identity/RAM immutable command binding, expectedZoneSeq/revision; Add также bound к header, на котором подтверждались credentials. Live cache≤256, root recentOperations≤256 — только hint |
| ResourceOwner | Отдельные AccessRevoked, StopRequested, Quiescent, Released; in-flight syscall удерживает store/lock/lease/ключи, timeout/interrupt не освобождают ресурсы. Заранее принятый ClosingSave отделён от новых команд |
| LocalStateCodec/FaultLocalStateStore | Одна bounded checksummed запись binding+journal, stages/outcomes/source-backup-candidate identities; atomic replacement model temp-write/force/rename/parent-force. StateUnknown блокирует replace до reconciliation |

`FileChannelStore` относится только к локальным синтетическим файлам и контракту
хоста. Опция syntheticCreateIdentity вызывает directory force там, где это
поддерживается; успех на Linux не переносится на Android SAF/Windows/provider.
Core получает уже предоставленный store: он не отменяет чужое создание пустого
документа. Запрет создания до durability gate реализован в model factory;
реальная SAF factory остаётся P6. Production provider allowlist по-прежнему пуст.

## T, ACK, повтор и владение

Порядок реализации: validate lease/token/expected seq/revision и перечитать
header+size → append immutable records/COW catalogue → data force → записать
полный inactive header → header force → перечитать/проверить выбранный header,
каталоги/root и изменённые metadata MAC/AEAD → create identity force при create
→ ACK конкретных generation/zoneSeq/request revision. Slot B после create нулевой.
Другой descriptor/catalogue/raw records не меняются. Счётчики Long.MAX_VALUE
не wrap; нарушение лимита не публикует header. Тестовый fault перед ACK находится
после подтверждённого commit, не внутри его подготовки.

После ошибки append/data force исход — IoFailure/AccessLost/применимый отказ,
хвост не виден snapshot. Следующий явный request может append в actual EOF;
автоматического truncate/reuse tail нет. После начала header publication любой
неоднозначный write/force/readback/revoke/ACK error даёт CommitUnknown. Writer
останавливается. Live reconciliation перечитывает, принудительно синхронизирует
наблюдённый whole state и проверяет снова: новый candidate → прежний request ACK,
подтверждённый S0 → NotApplied. У initial create без единого checksum-valid header
повторный force/reread подтверждает incomplete artifact: NotApplied и Locked,
документ не активируется и не переиспользуется. Доказанная порча/unsupported/
ресурсный отказ либо чужое состояние завершают сессию соответствующей ошибкой/
Conflict, без утверждения «операция отменена»; IO-неопределённость остаётся Unknown.
Никакая ветка не повторяет destructive effect. Неразрешённый owner не release.

Тот же live token/точный payload получает cached outcome; иной payload —
InvalidOperationToken. Lost/restarted/evicted token → UnknownOperation даже при
наличии opId в root: AppliedHint не восстанавливает payload или полный ACK.
257 commits проверяют вытеснение и реальное окно256. RevisionTracker оставляет
N+1 dirty после ACK N. Create/Add имеют заранее выданные zone/virtual-root IDs;
повтор не создаёт вторую decoy или новый документ. NoOp/VFS semantics не выдаются
за реализованные команды редактора: SetSettings здесь явная T с новым seq/opId.

Add доступен только core с main key и main-only snapshot: production путь
повторно проверяет current main secret, новый дважды и inequality against current
main envelope, затем создаёт независимый ZDK/envelope. Credential stages завершаются
до file writes; proposal stale после изменения header отвергается. Новый key
нужен только служебной подготовке/verify, не открывает decoy UI. На ACK/error и
при eviction он закрывается и удаляется из ownership list; Unknown удерживает
нужные handles до reconciliation. Обычная main сессия не сохраняет decoy key.

Все app команды блокирующие и должны выполняться вне UI. Owner.revoke не ждёт
core mutex/IO и сразу инвалидирует доступ. В сериализованной границе можно
указать только уже принятый checkpoint permit для current/одного final ClosingSave;
он может ещё находиться в очереди. После revoke допускается только этот не начатый
checkpoint; остальные tokens, новые команды и повторная выдача cached ACK запрещены.
Create/Add нельзя повысить до ClosingSave, поздний callback его не создаёт.
Revoke связан с KDF cancel token: используемый Argon2 завершается фактически,
следующий derivation/этап Add не начинается. После late completion accessRevoked остаётся true;
ни результат, ни reconciliation не открывают пользовательскую сессию.
Другой writer/вход не получает lease до завершения и release прежнего owner.
Закрытие ресурсов вне monitor; Released наступает после реального close,
ошибка одного resource.close не пропускает остальные cleanup.

Ключи принадлежат owner; временные serialized plaintext buffers очищаются finally.
P1 NoteCodec.decodeChunks теперь очищает свой сборочный plaintext buffer после
decode. Reader не хранит plaintext в temps/journal/logs; Java/Kotlin String/DTO,
JCA/BC/JVM/JIT copies физически стереть невозможно. Существующие ограничения P2
сохранены. В test target нет diagnostic UI, service entry или deterministic RNG.

## Чтение и пределы recovery

Обычное snapshot selection проверяет обе зоны и не откатывается при неверном
пароле или hash-valid newest с плохими refs. Поддержанный public catalogue
обходится bounded; карты имеют суммарные budgets, циклы/overlap/порядок/уровни
проверяются. Public hash остаётся checksum, не MAC и не доказательство свежести.
Open аутентифицирует root/settings/index/object metadata. Note/media chunks
проверяются перед использованием; VerifyAll дополнительно обходит reachable
AEAD и hashes **всех** catalogue records, включая obsolete. Без LogicalRef/owner
для obsolete record его raw hash не называется AEAD/semantic verification.

Recovery выбирает один slot/role, не сканирует magic и не создаёт пустые замены.
Свой plaintext выдаётся только после tag. Unknown supported-inner dependency
даёт read-only/Unsupported с отсутствующими DTO, не пустой root; полный VerifyAll
не объявляет такую closure проверенной. Recovery не даёт write capability,
а attach writer перечитывает обычный snapshot заново. Existing P1 types/codecs
используются без новых закрытых object-type enums. Полные VFS операции, worst-case
traversal/cache/scratch и media streaming интеграция относятся к P4.

## Точная fault model и oracle

Успешный force сохраняет все предшествующие bytes/length. Crash теряет все либо
сохраняет заданные volatile ranges; уже forced **неперезаписанные** ranges не
разрушаются. Partial write меняет prefix перед ошибкой; partial force сохраняет
prefix текущего file image. Header write может tear по заданной границе. Это
модель честного force/positional IO и сотрудничающих writers; произвольная порча
самих previously durable active ranges не обязана давать S0/S1 и приводит к error.
Unsynced namespace и local binding теряются отдельно от file bytes.

JVM fault runner для create/add/mutate main/decoy сначала фиксирует trace IO,
затем прерывает before/after каждого события. Для writes≤512B перебраны **все**
prefix lengths0…N; для pages/header/крупных force —0/1/7/8/75/76/4095/4096/8191/
8192/16383/16384/32768/65503/65504/65535/N−1/N и16 reproducible prefixes.
Seed `0x50301`=`328449`, для события добавляется event index и command ordinal×1000.
Crash policies: lose unforced, retain all, три seeded ranges. Read errors/full-IO
failure не выдают частичный byte array; positive short IO loops проверяются отдельно.
EIO/ENOSPC чередуются по index. Произвольные операции P4 не подменяются SetSettings.

Oracle независимо задаёт ожидаемые seq/settings/count/virtual-root IDs и recent
opId для S0/S1; он не получает ожидаемый S1 запуском той же мутации. Actual reader
проверяет codecs/MAC/AEAD; дополнительно сравниваются logical bytes/MAC/catalogue
и raw records другой зоны. При create без первого ACK допустим незавершённый
документ; подтверждённый create обязан выжить. Ни одной смешанной зоны/ложного ACK
в выполненном наборе не допускается. P2 independent byte fixtures остаются отдельным oracle.

Local-state тесты перебирают все stages и temp write/force/rename/parent force/
readback/ACK, before/partial/after, old/new retained namespace. Binding+journal
всегда один whole record. Temp-write prefixes и checksum corruption перебираются
по каждому byte. Checksum/Verified flag не защищает от rollback ОС и не заменяет
проверку фактических файлов/grants/profile в будущем MaintenanceCoordinator.

## Покрытие действующей матрицы

| ID | Выполненная P3 проекция | Что остаётся |
| --- | --- | --- |
| KEY-01/02/03, FILE-01 | Синтетический create/add, повторный open обоими секретами, независимый ZDK и сохранение другой зоны при mutate | Реальное устройство/reinstall/portable user file и P4 данные |
| TX-01/02/03/06 | Все фазы T create/add/SetSettings, partial writes/force/readback/ACK, short IO/EIO/ENOSPC, orphan tail, truncate adapter | Остальные команды VFS, EDQUOT/реальные grants/provider death и device power loss |
| TX-04 | Process-crash model volatile/durable, old/new recovery | Настоящий kill/reboot/power interruption на Android |
| TX-05, LIFE-06/09 | Process owner/lease, отдельный JVM FileLock process, FD/hardlink, noninterruptible write/force дольше timeout, revoke/ClosingSave/late completion/close | Android/OEM locks, реальные syscalls/lifecycle/UI publication и hard process isolation |
| RETRY-01/02/03, NOTE-05 | Immutable binding, changed payload, cache/window256, restart/lost token, CommitUnknown reconciliation, ACK N/dirty N+1 | Полный saveNote/draft/objectId/dates/editor и maintenance retry |
| RECOVERY-01/02/03/04, FILE-02/03 | Foreign CBOR/catalogue corruption симметрично, selected MAC/AEAD/seq, common framing/version refusal, explicit old slot/no password fallback, lazy chunk/VerifyAll | Device recovery/export, большие media/tree и все byte offsets truncation вне выбранных границ |
| FILE-04/05, SCHEMA-02/03 | Existing strict codecs, bounded catalogue/index/ranges, split, unsupported own root/read-only, unknown settings enum сохраняется | Полный malformed/fuzz/stress unknown object/media integration, maintenance selected closure |
| DUR-01/02 | File bytes/namespace раздельны, unsupported model create до создания, no ACK без identity force | Все конкретные provider/root/volume power-loss profiles |
| DUR-03, MAINT-07 | Joint local-state serialization/checksum/stages/outcomes и atomic rename fault model; StateUnknown stop/reconcile | Реальный noBackup Android publisher, file/profile verification и полный workflow P5/P6 |
| CRYPTO-01/02 | P2 AEAD/MAC в настоящих root/settings/indices и новые IDs/nonce при каждой T | Полный rekey/compact/kill/device сценарий и external audit |

Ни один полный TX/RETRY/RECOVERY/DUR/MAINT ID не объявляется закрытым по этой
проекции. ClearTrash/move/restore/saveNote/full rekey/compact и реальные пользовательские
сессии отсутствуют. Противоречия wire/protocol под заявленной моделью не выявлены;
существенное условие — честный force и сохранение прежде durable ranges. Нарушающий
его накопитель может потерять уже ACKed данные; FileChannel/readback это не исключают.

## Проверки, Android и передача

Финальный прогон агента —20 октября2026, Linux, JDK25.0.4.1, нормативный
wrapper9.6.0/SDK37. Зависимости предварительно загружены; проверки ниже выполнены
с `--offline`. Android execution не входит в эти результаты.

| Проверка | Фактический результат |
| --- | --- |
| JVM unit tests |61 tests,0 failures/errors/skipped;18 P3 и43 прежних P1/P2 |
| T fault matrix |6820 cases, seed328449; traces Create24/Add36/Main51/Decoy51 IO events, old/new oracle без смешения и false ACK |
| Local state |779 temp-write prefixes,778 checksum corruptions; все9 stages ×6 points ×3 moments ×2 namespace retention policies; StateUnknown stop/reconcile |
| Ownership/FileChannel/retry |Cooperating JVM child lock BUSY→ACQUIRED, hardlink branch выполнена;257 commits/window256, key eviction, blocked write/force/revoke/late completion, queued/current ClosingSave, KDF cancel, delayed close, incomplete-create/corrupt-readback reconciliation — PASS |
| Host result gates |33 P1 +15 P2 +13 P3 checks PASS; P3 exact-set/nested XML/two devices/zero/partial/failure/error/skipped/missing/stale/crash/missing protobuf proof |
| Lint |debug и p1Release:0 errors,15 прежних warnings,1 hint; checks/detectors не отключались |
| Сборки |debug, обычный release с R8, debugAndroidTest, обработанные P3 p1Release target/test APK — BUILD SUCCESSFUL |
| APK audit |5948 method refs,1732 field refs,25132 code edges; package access/hierarchy/linkage PASS,0 duplicate definitions, backend только в target,3950 ICU resources и pinned hashes PASS |
| Негативный APK audit |8 намеренно испорченных вариантов отвергнуты: debug substitution/backend copy/NFC data/package access/runner class/runner method/runner access/wrong test mapping; подписанные оригиналы не изменены |
| Manifests/signatures |Target non-debuggable, test targetPackage `.p1runtime`, Startup/ProcessLifecycleInitializer сохранены; обе подписи валидны и используют один test certificate |
| Fixtures и документы |P1:28 independent fixtures; P2:31 fixed fields PASS. Static validator:32 links,26 tables,106 future IDs,13 resolution rows. Дополнительно4 P2/P3 Markdown files:8 local links и fences PASS |
| Графы команд |P1/P2/P3 correctness и отдельный KDF benchmark — dry-run BUILD SUCCESSFUL; это не execution устройств или benchmark |

Новый queued ClosingSave test сначала воспроизвёл отказ до исправления входного
gate core; после исправления прошёл в общем наборе61. Отдельный предыдущий lint
упал внутри FIR/ExperimentalDetector (`Classifier was found in KtFile but was not
found in FirFile`). Финальные проверки на фиксированных исходниках в свежем
Gradle-процессе, отдельно от R8, прошли. Причина внутреннего сбоя не установлена;
версии, detectors и keep rules ради обхода не менялись. Warnings Unsafe/protobuf
и read-only Android metrics home не привели к отказу финальных сборок.

Выполненные команды из корня проекта:

```bash
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:lint :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-parallel --max-workers=2 --offline --console=plain
bash ./gradlew :app:assembleRelease --offline --console=plain
bash ./gradlew -Pp3ReleaseRuntime=true :app:verifyP1ReleaseApks --offline --console=plain
bash ./gradlew -Pp3ReleaseRuntime=true :app:lintP1Release --no-daemon --no-parallel --max-workers=2 --offline --console=plain
P1_AUDIT_ARGUMENTS=app/build/reports/p3-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
bash ./gradlew -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp2KdfBenchmark=true :app:p2KdfBenchmarkCheck --dry-run --offline --console=plain
python3 tools/p1/fixtures.py --check
python3 tools/p2/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
```

Идентичности локальной финальной пары APK до коммита:

```properties
artifactBundleId=e7a8831eccb16ed41afde896683aa94146473255e8601502d1a2efcabb1d5ce2
targetSha256=9cf05ab04cb6c4f5ea2e3e96abc75afa7832cff2559536b940482c10f47664ae
testSha256=2f463f8ffabb27f964fac3b6fddcc8189be35f5f9aaf4a6db4b8b88fed452cf7
targetMappingSha256=3b266786b0910064c7af7fea56ae65fcf6dc09c082c5f5515d8c6fcc30b820fc
testMappingSha256=9995e256da3dfec4266ab1a93c91e9411c8305b34ed6b671314b6d5e7dcefd53
targetMapId=458cd4b0ca0ca456c968dce50cba35ff1887ffc1be8297e2170fde70354056c7
testMapId=5edf064ee13605634c6d803c6f400c7283bc63e75dafd8154efdb5985aa026e2
artifactAudit=PASS
runtime=NOT_RUN
```

Оба mapping SHA/ID и APK SHA проверены по `artifact-manifest.properties` и
`p1-diagnostics.zip`; immutable bundle сохранён в
`app/build/reports/p3-runtime/artifacts/<artifactBundleId>/`. ZIP содержит оба
mappings/configurations и generated instrumentation API rules. Map ID сам по себе
не заменяет SHA256 APK. Windows-сборка формирует собственную пару и связанный с
ней свежий runId; побайтовое совпадение с локальной парой не требуется.

Отдельный runtime profile P3 задаёт8 точных методов `P3TransactionRuntimeTest`.
P1/P2 команды сохраняют7/8. Общий gate проверяет per-device XML + completed
protobuf/exit code + fresh runId + APK/mapping identities. Zero/partial/skipped/
crash/stale/missing results — ошибка. APK audit запрещает вторую io/transaction/
format/crypto реализацию в test APK, проверяет реальные DEX references/members/
access/package hierarchy и обе mappings. Heavy JVM matrix/benchmark не запускаются
на устройстве обычной P3 командой.

В среде агента нет emulator executable/system image или /dev/kvm. ADB попытка
завершилась ошибкой создания `/home/agent/.android` в read-only home; список
подключённых устройств не получен. **Android runtime P3 не выполнен.**
P1/P2 пользовательские receipts не заменяют исполнение новых P3 путей.

P3 повторяет прежние ограничения release-derived target: non-debuggable `p1Release`,
`.p1runtime`, test signing и дополнительные instrumentation ABI/intrinsics rules.
Обычный release не получает эти rules. Production optimization scope прежний
androidx/kotlin/kotlinx; storage/ICU/CBOR/BC вне него. Ни runtime этого test target,
ни успешная сборка не доказывают byte identity или физическую durability обычного release.

[Windows-инструкция](../../../tools/p3/android-runtime-windows.md), одна команда:

```powershell
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
```

Пользователь запускает на Honor Android15 и эмуляторе Android17. Успех —8 PASS
на каждом устройстве,0 failures/errors/skipped, BUILD SUCCESSFUL итогового gate.
Достаточно прислать `app/build/reports/p3-runtime/runtime-result.txt`, модель/API
и версию Gradle. Полная диагностика нужна при сбое; оба mappings/SHAs автоматически
сохраняются в diagnostic ZIP/immutable bundle. P1/P2 regression — прежними командами
по [README](../../../tools/p3/README.md).

## Готовность к P4 и открытые условия

JVM core и fault model дают базу для synthetic P4. Android P3 runtime ожидает
подтверждения пользователем; полная приёмка и production readiness не объявлены.
KDF cold/warm/thermal/OOM на слабом/среднем телефоне, универсальный creation profile,
Android provider/namespace/local-state durability, OEM lifecycle/leak/backup и
независимый security/format review остаются открытыми. Allowlist providers пуст.

**Перед постановкой P4 отдельно напомнить согласованное расширение: карточки
с типизированными полями и шаблоны.** Сейчас оно не реализовано; новые закрытые
перечисления типов объектов или препятствующие расширению wire assumptions не введены.
Дальнейшая схема/совместимость расширения требует отдельного задания до реализации.
PR подготовлен без merge.
