# P4 — VFS, карточки и потоковые вложения

Работа начата20 октября2026; отчёт завершён21 октября2026 (Asia/Bishkek).
Ветка `codex/copper-scroll-p4-vfs-cards`.
База — актуальный при начале master `b38de25c438e05b76fda9b668d7f802cc0466716`,
tree `b8cdcfe563acc05df20b36f868be0a0933bdea78`, пользовательский merge PR №8,
содержащий P3 `37f7b7994341bef7b9c98aec9b1f84d5d038bf56`. Merge/parent/tree
проверены GitHub API; ветка создана от этого master, не старой P2 базы.
Прочитаны пять нормативных документов, P1/P2/P3 reports, production codecs/crypto/
transaction/recovery/runtime tools и доступная концепция редакции20.10.2026
14:29:08 UTC. Google Doc не изменён; AGENTS.md в репозитории/родителях не найден.

Коммит реализации: [`287edb035f9a84759c831a7efb996dbdc7317743`](https://github.com/fantadrom/CopperScroll/commit/287edb035f9a84759c831a7efb996dbdc7317743),
tree `cd540cce941887d5898868e0395f8ec81e5f4ba2`.
Публикация: [Draft PR №9](https://github.com/fantadrom/CopperScroll/pull/9),
base master; PR не слит. Последующий коммит добавляет только эту ссылку/SHA в
отчёт; проверенные code/build/test inputs коммита реализации не меняет.

**Статус: реализация и проверки агента описаны ниже; Android P4 runtime=NOT_RUN.
Этап частично выполнен, Draft до пользовательских Honor15/эмулятор17 PASS.**
Это synthetic headless backend, не production readiness приложения.
Пользовательский receipt P3 принят отдельно в [p3-report.md](p3-report.md):
runId `619c964b-c0fb-4a8a-80d6-82f8a546bc8c`, bundle
`f0084b2b3de360594ec7073b6644cded298524fa88162682ea163bdd589ccabe`, по8 PASS,
failures/errors/skipped=0. Это предоставленный итоговый receipt, не запуск агента,
не повторная проверка XML; версия Gradle того прогона не подтверждена.

## Реализация и совместимость

Норма сначала расширена в [container-p4-extension.md](../container-p4-extension.md),
затем реализован код. Исходные design/format/resolution/prototype/test plan
ссылаются на расширение; исторический critical review не переписан.
Новые CARD-01…06/MASK-01…02/TEMPLATE-01…02 отделены от закрытых R/C.

Container1.1 читается/пишется по прежним правилам. Explicit synthetic Create
minor2 создаёт1.2; default P3 Create остаётся1.1. В1.1 card write отклоняется
до append; миграции закрытой зоны нет. Add/T сохраняют minor. Hash-valid slots
разных minor не смешиваются. Старый reader отклоняет minor2. ObjectSchema1
types1…5 неизменна; schema2 допускает только новый type6 card.
Header65536×2/offsets/descriptor18/record76/ref/AEAD/MAC/envelope/S1 не изменены.
Меняется u16 minor offset10; zone/settings/index/note/media/card версии1.
Тело card `[1,fields]` хранится в1…4 kind4 document chunks,≤4MiB; owner=cardID,
ordinal0…n−1, все кроме последнего1MiB. Metadata по-прежнему≤16KiB, common
title/icon/color/dates/revision — единственный источник истины. VerifyAll/recovery
обходят card refs; unknown object/card/field/schema/template=true даёт безопасный
Unsupported/read-only, а не пустой объект. Старая1.1 fixtures проверка сохранена.

| Компонент | Реализовано и граница |
| --- | --- |
| ZoneRepository / VfsMutation | Zone-scoped create/read/list/update, folders, notes/cards/media, metadata, pin, settings; immutable requests через существующий P3 publication/owner/recovery |
| CowIndex | Encrypted objects/children B+tree, put/remove/split/collapse COW только изменённых paths; старый ciphertext не правится |
| Batch / trash | Полная source-snapshot проверка, duplicate/ancestor normalization, stale/foreign/cycle/depth refusal; одна T; RestoreAll с numbered «Восстановленное»/overflow-to-root; ClearTrash обеих зон удаляет refs, не старый ciphertext |
| NoteStorageAdapter | Фактические paragraphs/nullable checked, ordered overlaps/UTF-16/ARGB/styles, Unicode/empty/todo/title/body/cursor, без spans/HTML/S1 |
| MediaStreams / BinarySource | Length+digest-bound single-use stream,1MiB chunks/64-entry chunk lists, bounded range/copy, short IO/cancel/ownership; никаких Base64/media codecs/UI/пользовательских файлов |
| CardModel / Codec | Все10 типов, stable random field IDs/order, immutable snapshots и запрет смены типа, точный decimal, warning URL/Email, независимые fixtures |
| CardDraftSession | Current/last-wholly-valid/ACK раздельно, atomic whole-card T, preaccepted ClosingSave, dirty N+1, Unknown/revoke/reconcile без позднего открытия |
| Masks / templates / messages | Pure mask/date/edit/copy policy, empty/bank templates с независимыми IDs, один global dismissible warning/reset через fake prefs |

Новая полностью пустая note не создаётся; очищенная существующая сохраняется.
Card требует непустой title,0 полей допустимо. Реальные изменения увеличивают
object revision один раз; title/body/fields — content date, icon/color/pin/move —
metadata date, note cursor — metadata. NoOp/retry не меняет disk seq/dates/counter.
Pin использует монотонный uint64, не часы. Unknown settings/icon raw сохраняются,
сортировка имеет deterministic scalar/id tie-break. Notes/cards/media разрешены
в folder63; folder64 отвергается. Move между зонами отсутствует.

Invalid current не смешивается с valid fields ради save. Last-valid допускается
лишь новее ACK; validation/queue/write не Saved. CommitUnknown автоматически
закрывает draft/reveal, service reconciliation не открывает session; IO failure
не Saved. Reveal RAM-only, initial hidden, revoke clears. Copy obeys zone policy.
После закрытия RAM draft теряется; неправильных encrypted drafts нет.
Unsaved mailbox RAM-only, scoped к containerId+zoneId,≤256 entries/oldest eviction;
consume требует live authenticated ZoneRepository. После process death/eviction
notice не гарантирован. Plaintext prefs содержимого/zone IDs нет.
Будущий persistent notice требует отдельного защищённого пути и честного IO failure.

Built-in templates копируют определения/defaults и создают новые IDs; flag
useAsUserTemplate=false. Обязательное будущее clearing пользовательского шаблона
не закреплено. Единственный install-global preference — suppress card-editor
warning, без IDs/истории. Unsaved/confirmation не подавляются. Reset относится к
внутренним сообщениям, не Android notifications. DataStore/dialog/lifecycle/UI — позже.

## Численные budgets

| Область | Предел |
| --- | --- |
| Card body / fields |4MiB encoded /256 fields,1…4 chunks |
| Card title / field name |100cp/400UTF-8B /128cp/512UTF-8B; strict scalar strings |
| Field value / maxLength |32768cp/131072UTF-8B; text maxLength1…32768cp, не UTF-16 |
| Physical draft / prevalidation |16MiB UTF-16 estimate; title4096/icon128 UTF-16 units, bounded intermediate fields; logical bounds строже |
| Masks / date | Digit mask256 scalars/128slots; date mask64chars; Gregorian1…9999; no timezone/epoch |
| Pattern / numbers | Grid2…6,≤36 unique points; integer precision38; decimal precision38/scale0…18, input60chars, без Float/rounding |
| Graph / traversal |32MiB conservative metadata projection (1024B/object,≤32768 objects), depth63, batch4096, CBOR nesting16 |
| Reader cards / list cache |32MiB cumulative eager card-body bytes per open /8MiB display title cache |
| Pending save / core cache |16 prepared card saves /256 tokens и32MiB payload; terminal/evicted bodies wiped/null |
| Media format / implementation |512GiB/524288 chunks,1MiB chunk, input blocks≤8192; maps/catalogue budgets могут отказать раньше format maximum |

Превышение — ResourceLimit/UnsupportedLimit/Busy по контракту, не неограниченная
RAM и не plaintext scratch. Encrypted scratch в P4 не реализован. Эти числа —
лимиты, не измеренная производительность/гарантия RSS/поддержка512GiB на телефоне.
Маски валидируют ASCII digits, uppercase — frozen Unicode16 property, без
преобразования секрета. Paste filtering/truncation — предусмотренное исключение;
смена ограничений не теряет raw молча. URL/Email syntax, включая CR/LF, warning.
Date null отличается от технической picker2000-01-01 и не заполняется preview.

## JVM, independent oracle и faults

P4 shared suite содержит10 сценариев, boundary suite5, model1, fault matrix1.
Independent model — imperative ожидаемые эффекты, seeds328705/328706 ×128commands
(256 total), сравнение graph/types/names/parents/dates/revisions/pins/values/seq,
не результат production-команды в качестве expected. Golden generator — Python
stdlib CBOR,3 positive/12 negative, одинаковые bytes JVM/Android и provenance SHA.
Не ограничиваемся self encode/decode. Неизвестные схемы дополнительно инжектируются
независимым автором valid AEAD record/header для проверки reader/recovery.

Fault matrix seed328707:25,767 случаев девяти команд Folder/Note/Card/MoveBatch/
DeleteBatch/Restore/Clear/Pin/Settings. S1 — pure update source model, не successful
engine run; успешный run нужен только для trace schedule. Before/after каждого
read/write/force × drop/all/3 seeded retained ranges, EIO/ENOSPC; partial write и
force. Для write≤512B — каждый byte prefix, иначе фиксированные8B/4KiB/16KiB/64KiB
boundaries и8 seeded prefixes. Полное старое/новое состояние, false ACK refusal,
foreign descriptor/catalogue/raw records, body/styles/cursor/dates/recentOps/settings.
Это ограниченная модель ошибок, не exhaustive filesystem/power-loss доказательство.

Отдельно actual folder63/file63, folder64 refusal, Restore overflow/mixed trees,
70+ objects для COW split/delete collapse, card3,935,044B/4chunks и4MiB refusal,
очередь16/Busy и terminal payload cleanup. FileChannel smoke генерирует8,388,625B
stream без whole-media buffer. Uninterruptible source удерживает store/key/lock
после timeout/interrupt/revoke до фактического возврата syscall; second writer
отклонён. Это host FileChannel, не Android SAF/physical durability.

Синтетическое media2,097,165B осталось побайтово прежним после коротких saves
(refs/hash и raw ciphertext сравниваются). Trace: short-note read468225/write148792B,
short-card read471162/write148992B. Это один небольшой graph, не benchmark0/1/10GiB.

## Команды и результаты агента

Среда: Linux, Gradle9.6.0, JVM25.0.4.1 (компиляция Java target11), SDK37/min31,
AGP9.4.1/Kotlin2.2.10; зависимости/wrapperSHA/версии/manifest/UI не обновлены.
Equivalent Gradle source-set DSL `.kotlin.directories.add` заменил недоступный
deprecated srcDir на текущем API, сохраняя те же paths и scope.

```bash
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:lint :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-parallel --max-workers=2 --offline --console=plain
bash ./gradlew :app:assembleRelease --offline --console=plain
bash ./gradlew -Pp4ReleaseRuntime=true :app:verifyP4BuildIdentity --offline --console=plain
bash ./gradlew -Pp4ReleaseRuntime=true :app:lintP1Release --no-daemon --no-parallel --max-workers=2 --offline --console=plain
P1_AUDIT_ARGUMENTS=app/build/reports/p4-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
python3 tools/p1/fixtures.py --check
python3 tools/p2/fixtures.py --check
python3 tools/p4/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
git diff --check
```

Полный JVM regression: **78 tests,0 failures/errors/skipped** (61 прежний P1/P2/P3
и17 P4); BUILD SUCCESSFUL5m23s вместе с lint/debug/androidTest builds.
Host result/source gates: **94 checks PASS** (P1=33/P2=15/P3=13/P4=18/source=15).
Lint debug:0 errors,15 warnings/1 hint. Warnings `sun.misc.Unsafe`/protobuf
не вызвали отказа; зависимости/JDK ради их устранения не менялись.
Отдельный `lintP1Release` PASS1m40s:0 errors, те же15 warnings/1 hint.

Обычный release после R8: BUILD SUCCESSFUL2m37s. P4 p1Release target+test после
R8 и `verifyP4BuildIdentity`: BUILD SUCCESSFUL2m17s. APK audit PASS:6262 method
refs/1787 field refs/27197 code edges, hierarchy/package access/linkage;
backend definitions target-only,0 duplicate definitions, ICU3950 resources/
pinned hashes, independent P1/P2/P4 fixtures, все10 P4 public test methods.
Negative APK audit:8 checks PASS (debug substitution/backend duplicate/data
tamper/package access/missing runner class/method/access/wrong mapping), исходные
подписанные APK не изменены. Manifest: non-debuggable target/правильный runner
targetPackage, Startup/ProcessLifecycleInitializer сохранены; подписи совпадают.
Оба mappings/IDs/SHA проверены по ZIP и immutable artifact bundle.

Локальная пара собрана **до коммита**, с sourceDirty/sourceCodeDirty=true,
wrapperModified=false, sourceRevision=base master выше. Это честная working-tree
идентичность, не утверждение сборки clean будущего commit. Source fingerprint:
`c9fe2ed80065858011f0786381922f850e39d6d287d1144273b8da8faf8a4fcb`.
Actual Gradle JVM: `25.0.4.1+1-LTS-5`, vendor Oracle Corporation.

```properties
artifactBundleId=5225a7557a1b60bd30cba685977a5c71a39bd4c1afd853b2db3daace0e00e0a2
targetSha256=c2a70e7e1ad77cf0326bfbb2ba9c7bbb231a304a4e2618878a7a8951790261cd
testSha256=b3303dbe6925d8eaeebf24a360a01a5bbecf96e58a40aa343c53fa6c982c432d
targetMappingSha256=b0e252383a1054d496cda7e34e3f91023760b7a75bda7666d3ff5ab13ed49515
testMappingSha256=6f4ff44e492767c4d2ca6c3fde4b7b8cfe21944bb8895d835ed834d8ae49dc36
targetMapId=11a095cb1b70a9583c0229dd8e0e97b1631c416213bc9cb8ca77d3dcd0cd80d7
testMapId=93800a52c4888ec49e15013a7bfb713a2d9b7586d220bcdab5e311bc5eceea51
artifactAudit=PASS
runtime=NOT_RUN
```

Артефакты/оба mappings/configurations/generated rules/manifest сохраняются в
`app/build/reports/p4-runtime/p1-diagnostics.zip` и `artifacts/<bundleId>/`.
Windows создаст собственную APK пару и fresh runId; побайтовое совпадение с этой
Linux парой не требуется. Успех сборок/audit не является runtime receipt.
P1/P2 fixtures28+composite31fields, P4 fixtures3positive/12negative и статическая
docs validation(38links/28tables/116test IDs/13resolution rows), diff checks PASS.

Отдельно проверены **только task graphs**, не Android execution:

```bash
bash ./gradlew -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --dry-run --offline --console=plain
bash ./gradlew -Pp2KdfBenchmark=true :app:p2KdfBenchmarkCheck --dry-run --offline --console=plain
```

Все5 graphs PASS, P4 capture→build→audit/source verify→prepare→connected→gate
порядок проверен; тяжёлые JVM tests не входят. P4 Run configuration совпадает
с property/task команды. Dry-run не даёт runtime PASS и не запускает benchmark.

## Android gate и оставшиеся условия

P4 suite `P4VfsCardRuntimeTest` — ровно10 meaningful methods на каждом устройстве;
список и [точная Windows-инструкция](../../../tools/p4/android-runtime-windows.md).
Backend вызывается из target APK и проверяется class-loader identity, test APK
не содержит второй реализации. DEX audit проверяет members/access/linkage и
необходимые runtime classes/resources, обе mappings/SHA связаны с APK в immutable
artifact bundle и diagnostics ZIP. Одного отсутствия duplicate classes недостаточно.
Existing P1/P2/P3 commands сохранены с7/8/8, benchmark отдельный opt-in.

Gate проверяет точное множество, каждого device, fresh runId, XML+completed
protobuf/runner exit, APK/mapping/source identities; zero/partial/skipped/stale/
crash/missing report не PASS. Host fixtures покрывают отрицательные исходы и
вложенные testsuites без двойного подсчёта. P4 receipt добавляет actual Gradle/JVM,
Git revision+source fingerprint, source/code/workingtree dirty и wrapperModified/
SHA separately. Capture before build; verify before install/after run. Source
scan bounded20k files/256MiB, ignored source/assets тоже учитываются, symlinks
отклоняются. Local9.8wrapper не выдаётся за clean source revision.

Ограничения p1Release прежние: non-debuggable release-derived target с suffix/
marker/test signing/instrumentation ABI+intrinsics rules; обычный release этих
дополнительных rules не получает. R8 включён, optimization scope прежний
androidx/kotlin/kotlinx, storage/ICU/CBOR/BC вне него. Keep rules не расширялись
вручную для PASS; generated ABI учитывает реально вызываемые P4 классы.
Startup/lifecycle включены. Ни сборка, ни этот профиль не доказывают обычный release
runtime/byte identity, Android SAF/provider/namespace/power-loss durability.

В среде агента нет emulator executable/system image или /dev/kvm. Повторная
попытка `adb devices -l` завершилась ошибкой создания `/home/agent/.android`
в read-only home (exit134); список устройств не получен. Android runner
не предоставлен; APK build/audit не runtime PASS.
Пользователь запускает на Honor Android15 и эмуляторе Android17:

```powershell
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
```

Ожидается BUILD SUCCESSFUL **итогового gate**, `runtime=PASS`, по10 tests,
failures/errors/skipped=0. Прислать `app/build/reports/p4-runtime/runtime-result.txt`
и модель/API; Gradle/JVM/source уже внутри receipt. Диагностика при отказе:
`p1-diagnostics.zip`, `build-identity.properties`, run.properties, XML/protobuf/logcat
по инструкции. P1/P2/P3 regression — отдельно без P4-флага; dry-run не исполнение.

Полные COMPACT/REKEY/SAF/physical durability ID не закрыты. P5 maintenance/migration,
P6 providers/grants/power-loss, weak/mid-device KDF/thermal/OOM, independent security
review, UI/editor/lifecycle/leak/preferences integration остаются открытыми.
Никаких реальных контейнеров/скрытой миграции/Keystore fallback/секретных previews.
До device P4 PASS этап частичный; PR Draft, merge не выполняется.
