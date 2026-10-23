# P5b — ChangeSecret с новым ключом зоны

**Актуальный статус: принят пользователем; PR12 слит. Регрессии98 PASS на двух
устройствах относятся к501ed72 и bundle474ec4ee, см. [provenance](p6a2-report.md#пользовательское-подтверждение-p6a2--23-октября-2026).**

Работа 22 октября 2026. Ветка `codex/copper-scroll-p5b-change-secret` от актуального
master `5f91b77ca47ff38352060a64daa118ecb9733656`, tree
`b34a1b2fcc9b37372c873f1d9d6ac9e2fed12463`. Проверено наличие merge PR №11 и
предка `0f1c43fea9047be197fb58e9cb712f07c906aeb9`; исходное дерево чистое.
Опубликован [Draft PR №12](https://github.com/fantadrom/CopperScroll/pull/12).
Реализация: commit `127619afa37b0bea43f2e790d2454831c7cec183`,
Git tree `91362a64afd5bba516d951792d5099e566f1bf6f`; последующий commit
добавляет только эту публикационную запись. Master не изменён.

AGENTS.md в репозитории/родителях не найден. Прочитаны design/format/resolution/
test/prototype/P4 extension, P1–P5a reports и соответствующий engine/runtime code.

Статус: headless реализация, synthetic/model проверки и пользовательские Android-прогоны подтверждены (см. дополнение от 22 октября ниже); **Android агента NOT_RUN**.
Новые пользовательские runtime P5b и регрессии P1–P5a обязательны до приёмки.
Пользовательское подтверждение прежнего P5a из results.zip добавлено отдельной
датированной записью в [p5a-report.md](p5a-report.md), не выдано за новый прогон.

## Общий протокол и credentials

`MaintenanceCoordinator.beginChangeSecret(capability, destinations, targetZoneId,
inputMethod, hint?, parameters, otherKnownCorrupt)` выдаёт типизированный
`ChangeSecretRequest`. Он наследует общий immutable MaintenanceRequest, хранит
один target, source fingerprint/S, operation ticket, параметры и назначения,
но не секреты. `CompactionRequest` и прежние compact/backup API сохранены.
Main role/owner/epoch проверяются до callbacks, grants, KDF и artifacts.

`credentials(request, currentSecret, newSecret, repeated)` независимо открывает
именно expected target/role/container, проверяет descriptor MAC/root и сравнивает
ZDK constant-time с main session handle, если цель main. Затем существующий
`prepareChange` проверяет repeat/same-own S1/inequality и создаёт независимый ZDK,
salt/nonce/envelope с self-test. Старый S1 не уничтожается раньше prepareChange;
после него остаются только currentTarget и prepared handles внутри proposal.
Все входные CanonicalSecret остаются caller-owned; их copies очищает P2.

UnchangedSecret/SecretMismatch/Cancelled/ResourceLimit/Unsupported/Busy отделены
от CredentialsRejected и IO/RNG failures. Ошибка кодирования — отдельный
SecretEncodingException до передачи CanonicalSecret. Параметры KDF валидируются
без downgrade. Default P2 policy 65536KiB/3, разрешённые варианты P2 прежние;
synthetic runtime явно использует 16384KiB/2, без заявления о новых device замерах.
Никакого запроса чужого пароля при inequality нет. AEAD mismatch доказывает только
несовпадение с текущим поддерживаемым envelope, не здоровье закрытой зоны.
Известная порча передаётся запретительным флагом; unknown outer/KDF блокирует reader.

Confirm одноразовый; один current ticket/context на core действует и между разными
coordinator. Новый запрос/repeated credentials закрывают прежние old/new handles.
Stale source при confirm тоже закрывает подготовленный контекст. Revoke до dispatch
освобождает quiescent context; если IO ещё жив, release возвращает false.
Поздний старый callback не отзывает новую main-сессию.

Обе команды используют **тот же execute/publish/switch/abort/cleanup автомат**:
AuthorizeMain → Destinations → Credentials → Confirm → DrainAndFreeze → Preflight →
Backup → BuildCandidate → VerifyCandidate → PrepareSwitch → AcceptSwitch →
ResolveAndClose. Pending save/CommitUnknown дают Busy/reconcile-or-stop.
Один frozen S, один SnapshotCopier backup, один candidate, один final switch.
Lease/maintenance hold остаётся через abort IO/cleanup и live Unknown. До файлов
полностью проверяется reachable target, outer structures другой зоны, лимиты/space.

## Перешифрование и проверка

`RekeyBuilder` работает из пустого CREATE_NEW документа. Использует production
RecordCrypto, нормативные HKDF/AAD/AEAD, реальную RecordIdReservation по всему
source catalogue обеих зон и уже созданным candidate IDs. Internal entropy injection
применяется только JVM tests; public key/authorization injection API не добавлен.

Все target records заменяются bottom-up: leaf note/card/media chunks, chunk lists,
objects/settings, object/children indexes и root. `RekeyEdges` меняет только известные
schema-directed reference positions и root sequence; body/media bytes непрозрачны.
Не используется UI display fallback. Canonical metadata raw values сохраняются,
включая unknown допустимые icon/sort/view, cursor, styles/todo, filled templates,
masked/date/hidden fields, IDs/parents/даты/revisions/counters/recentOperations.
Target zoneSeq/root seq +1; overflow generation/sequence отклоняется до backup.
Новый envelope/inputMethod и явно запрошенная hint; иначе hint сохраняется.

Другая зона не проходит graph rewrite или compact: её полный catalogue record set,
включая obsolete, копируется побайтно без её ключа. Logical descriptor/MAC прежние,
меняются только physical catalogue/offsets. containerId прежний; instance новый,
generation +1; minor1.1/1.2 прежний. Оба полностью новых superblock одинаковы.
Container layout, envelope/record domains, SDK/dependencies не менялись.

Verifier повторно читает candidate, заново проверяет полный graph новым ключом.
Парная traversal выводит соответствие из authenticated refs source/candidate,
не принимает builder ID map/manifest. Canonical plaintext сравнивается с заменой
только ссылок и разрешённого root seq. Каталог цели равен новой closure; ни один
новый ID не входит ни в одну source зону. Другой descriptor/raw multiset побайтны.
Общее с compact разбиение всего файла учитывает records/catalogue pages/нулевой
alignment, оба headers, EOF=committedLength, не оставляет orphan/old ranges.
Force/namespace/reopen/identity/source freshness идут по P5a до accept.
Проверяется existingWriteDurable и durableCreateIdentity каждого backup/candidate
FD, в том числе после reopen; namespace guarantee сама по себе не data durability.
Слабый adapter не получает VerifiedCopy/Activated (отдельный fault boundary test).


Каждый новый target ciphertext сначала положительно открывается новым ключом,
затем проверяется old ZDK с **тем же текущим** header/ref/owner/ordinal/AAD.
Допускается только AEAD CorruptData; stale-ref/hash/framing отказ не oracle.
Отдельный P5bOracle в tests сам обходит typed codecs, сравнивает actual content
и повторяет old-key negative; не использует RekeyEdges/builder mapping. На обоих
ролях чистый instance делает обычную T с note/card/multiblock media, закрывается,
повторно открывается; новые и последующие records не читаются old ZDK.
Source и backup продолжают открываться старым секретом.

## Journal, recovery, ownership

Local metadata **v2 достаточно**: kind="rekey", singleton zones содержит target;
role/envelope извлекаются из заново аутентифицированного документа. Нет нового
поля или изменения прежних bytes. Независимые frozen v1 compact/v2 rekey fixtures
проверяют canonical round-trip и повреждение framing/checksum. Legacy допустимые
v1/v2 читаются в прежних границах. Unknown kind/неверный target не становятся compact;
openBound блокирует неизвестный journal даже в terminal stage. Checksum не anti-rollback.

`RekeyRecoveryCredentials(source, candidate)` явно разделяет два Map<zoneId,S1>.
Для Source нужны старый main authority и old target; новый секрет не нужен.
Для Candidate дополнительно нужны новый target и main authority **candidate**:
при main rekey это новый основной секрет, при decoy rekey — прежний main.
Одинаковый zoneId не объединяет credentials. Missing context → NeedsCredentials,
wrong secret → CredentialsRejected, без fallback/нового backup/build/replay opId.

Общий MaintenanceRecovery проверяет journal/grants/profile/identity/freshness,
source/backup старым target ключом, candidate новым; повторяет full paired verify
и old-key negative. CandidateVerified не credential. Source/Candidate выбираются
явно. Live Unknown удерживает адресуемые owners; wrong new secret не освобождает
lease и не переключает файл. Confirmed resolution заканчивается Locked.

Revoke/AcceptSwitch сериализованы общим коротким owner monitor. До accept — старый
binding; durable Activated — MaintenanceCommitted. Потеря ACK даёт SwitchUnknown;
accept-before-revoke сохраняет Unreported и требует explicit resolution. Ни один
из этих исходов автоматически не открывает редактор. old/new target handles,
borrowed main, KDF copies и tokens очищаются по прежним ownership границам.
Закрытие FD только после quiescence; cleanup failures suppressed к primary.

## RAM, размеры и preferred input

Предел P5a сохранён: 32768 public records суммарно, conservative public-map budget
32MiB; reader graph/object budget32MiB, note16MiB, card4MiB и aggregate card32MiB,
chunk1MiB. Превышение до backup. Rekey добавляет bounded source/new ID sets и DFS
metadata stack (индексы/списки высотой не более7 каждый), без plaintext disk scratch.
Одна leaf plaintext не более1MiB. Анализ одновременно удерживаемых application
byte buffers: builder до2×CHUNK плюс PAGE на metadata level; paired verifier
до4×CHUNK плюс3×PAGE на metadata level. При максимальном пути root/index7/object/
chunk-list7 это18 metadata levels: соответственно до3.125MiB/7.375MiB этих буферов,
помимо ciphertext/maps/CBOR trees/crypto engines. Это расчёт по коду, не замер RSS.
Большие media/обе minor проходят mainOnlyAndCompatibility; превышение map/graph/
page/chunk budgets проверяют сохранённые P1/P4/P5a boundary regressions. Существующий VerifyAll может собрать note
до16MiB и использовать bounded CBOR decoder (allocation cap128MiB); это не
обещание общего RSS≤32MiB. Весь файл/большие media в RAM не загружаются production
builder; synthetic test images сами RAM-backed. Native/BC/GC memory limits и
физическое уничтожение всех copies остаются ограничением P2/P6.

MaintenanceSizes прежний: S−C отдельно от2S+C/S+C, retained source tail и extras,
scratch/partial на успешном пути0, old backups/allocated неизвестны. C≥S допустимо.
Structured result добавляет kind/target и retainedFilesKeepOldProtection.
Source/backup не удаляются: старый plaintext/защита не отзываются, физическое
стирание не обещается. Retention policy P5a не заменена production deletion.

PreferredInputMethods и NoBackupInputPreferences — отдельный headless local port,
без file/crypto/maintenance зависимости. Меняется только предпочтение панели,
не descriptor/S1/ключ. PIN/text same S1 даёт UnchangedSecret; другой S1 — полный
ChangeSecret. Production noBackup persistence/UI остаются P6, не новый экран.

## Нормативная матрица

Все ниже — synthetic/model/JVM проверки, не Android/provider durability certificate.

| ID | Test/oracle/fault | Проверка и ограничения |
|---|---|---|
| REKEY-01, KEY-04 | mainOnlyAndCompatibility, mainRekeyPreservesDecoy, decoyRekeyPreservesMain; P5bOracle.logical | Новый ZDK/URI, content/revisions/counters неизменны, seq+1, обе minor; device NOT_RUN |
| REKEY-02 | oldKeyCannotReadRekeyOrFutureTransactions; независимый oldKeyRejection | Positive new-key и old-ZDK AEAD negative на всех новых/будущих records обеих ролей, обычные T/reopen |
| REKEY-03, TX-07 | twoZones, paired verifier, verifyPhysicalPartition | Оба headers новые/одинаковы; старые IDs/obsolete/envelope/ranges отсутствуют, другая зона raw/logical прежняя, нет password fallback |
| REKEY-04, KEY-05 | credentialsAndNoArtifacts, preparationFailuresHaveNoArtifactsOrReservations, unsupportedSelectedGraphOpaqueUnselectedAndOverflow | Wrong/cancel/repeat/same-own/other-secret, KDF/RNG/OOM, selected unknown:0 artifacts; нет forgotten-decoy reset |
| REKEY-05 | mainOnlyAndCompatibility large media, P5bImages.rich, independent typed graph oracle | Trash/settings/card/templates/hidden/masks/styles/raw enums, streaming; не device RSS measurement |
| REKEY-06 | allIoBoundariesAndDurableRestarts, everyJournalWriteForceRenameSyncReadbackAck, sourceAndCandidateIdReservationsAndLateRngFailures | IO/local journal/RNG leaf/index/root failures, before/after/partial, source/binding/ACK honesty |
| REKEY-07 | credentialsAndNoArtifacts, preparationFailuresHaveNoArtifactsOrReservations, unsupportedKdfPolicyNeverSilentlyDowngrades | Main-only dispatch, no other key/helper, known corruption blocks; P2 independentEnvelopesAndExpectedZone отвергает unsupported other KDF; failed other tag не health proof |
| REKEY-08, KEY-08 | preferredInputAndRepeatedChange, sameOwnS1AcrossPinAndTextCannotRewrap | noBackup panel only; same-S1 Unicode/PIN/text не rewrap; different S1 полный rekey |
| MAINT-01 | credentialsAndNoArtifacts, staleRequestAcrossCoordinatorsAndSourceChange | Decoy/Locked/stale/reused capability denied первым действием,0 grants/KDF/artifacts |
| MAINT-02 | P5a SetupRequired regression, source tickets | Shared headless grants→Locked; picker/rotation P6 |
| MAINT-03 | pendingSaveUnknownAndOrdinarySaveBeforeRekey, commitUnknownCannotEnterMaintenanceAndVerifiedCandidateReplacementCannotSwitch | Drain-or-stop, ordinary T→openBound→rekey, no writer/partial ACK |
| MAINT-04 | sourceChangedAfterConfirmationHasNoArtifacts, verified candidate replacement; P5a regression | Fresh source/candidate fingerprint+identity; cooperative locks не защита от произвольного adversarial writer |
| MAINT-05 | revokeSwitchAndLease, revokeBeforeDispatchClearsOldAndNewHandlesWithoutFiles | Revoke-before-accept deny; quiescent old/new key cleanup; no late switch |
| MAINT-06 | acceptedSwitchThenRevokeIsUnreportedAndRequiresExplicitResolution, liveUnknownUsesPinnedOwnersAndNeedsNewCandidateSecret | Accept-first/Unreported/lost ACK, pinned lease, Locked, no false cancel |
| MAINT-07 | capturedDurableStagesRestartWithoutReplay, crashAtCopyBuildAndNamespaceTransitions, frozenLocalMetadataV1V2AndTruncatedChecksum | Every durable stage и pre-cleanup crash images; unknown/legacy/corrupt metadata; no replay/mtime heuristics |
| MAINT-08 | restartWithDistinctCredentialContexts, recoveryRejectsMissingChangedFilesAndWrongContextsWithoutReplay | Отдельные old-source/new-candidate secrets/main authority; rekey-подпункт реализован в модели, device NOT_RUN |
| MAINT-09, LIFE-06/09 часть | revokeDuringNewKdfWaitsForQuiescenceAndWipesBuffers, closingProposalRetiresIndependentOldNewHandles, blockedAbortStillHoldsLeaseAndPreservesPrimaryWithSuppressedCleanup | Stop→Quiescent→Released, one close, primary/suppressed, буферы; Android lifecycle/leaks/OEM P6 |
| MAINT-10, COPY-01…04 | общий SnapshotCopier/retention/sizes, полные P5a regressions | Одна verified copy и один candidate, aliases/budget/retained copies; production SAF deletion отсутствует |
| DUR-01…03 часть | JVM FileChannel model, IO/journal/namespace fault images | Declared data/identity/local state model; реальный power loss/provider allowlist не закрыты |

## Проверки и фактическая среда

Linux, Oracle JVM25.0.4.1+1-LTS-5, Gradle9.6.0, AGP9.4.1, Kotlin2.2.10,
compileSdk37/min31/Java target11, ICU4J76.1/CBOR0.9/BC1.86. Wrapper/dependencies/
R8 scope/Startup/lifecycle/штатная release signature сохранены. Unsafe/protobuf
warnings не являются отказом и не послужили поводом обновлять среду.

Полный JVM run: **145 tests,0 failures/errors/skipped**, вместе с debug build/lint
BUILD SUCCESSFUL5m11s. После финального data-durability guard и его нового test:
**68 P5a/P5b tests,0 failures/errors/skipped**, debug build/lint PASS2m51s.
Это146 различных JVM методов в совокупности прогонов, не один146-test запуск.
XML обоих прогонов сохранены отдельно в task-local work/p5b. Debug lint:
0 errors/13 warnings/1 hint. Host gates: **177 checks PASS**:
P1=33, P2=15, P3=13, P4=18, source=62, P5a=18, P5b=18.

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:assembleDebug :app:lintDebug --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew :app:testDebugUnitTest --tests '*P5a*Test' --tests '*P5b*Test' :app:assembleDebug :app:lintDebug --offline --no-daemon --no-parallel --max-workers=2 --console=plain
python3 tools/p1/fixtures.py --check
python3 tools/p2/fixtures.py --check
python3 tools/p4/fixtures.py --check
python3 tools/p5b/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
git diff --check
```

Frozen fixtures PASS: P1=28, P2=31 fields, P4=3 positive/12 negative,
P5b=2 independently authored local v1/v2 records. Static docs checks проходят;
они не semantic proof. Host FileChannel rekey/reopen/namespace/cleanup исполнен
на Linux. Windows-specific filesystem enforcement агента не проверен; прежние
P4/P5a CRLF/read-only/Git/process fixes и строгий byte fingerprint сохранены.

Release/R8 и актуальные APK audits — **PASS**:

```sh
bash ./gradlew :app:assembleRelease --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew -Pp5bReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew -Pp5bReleaseRuntime=true :app:lintP1Release --offline --no-daemon --no-parallel --max-workers=2 --console=plain
P1_AUDIT_ARGUMENTS=app/build/reports/p5b-runtime/audit-arguments.txt python3 tools/p1/check-apk-audit.py
python3 tools/icons-demo/audit-apks.py --aapt2 /workspace/android-sdk/build-tools/37.0.0/aapt2
for phase in p1 p2 p3 p4 p5a; do
  bash ./gradlew "-P${phase}ReleaseRuntime=true" :app:verifyP1ReleaseApks --offline --no-daemon --no-parallel --max-workers=2 --console=plain
  bash ./gradlew "-P${phase}ReleaseRuntime=true" ":app:${phase}ReleaseRuntimeCheck" --dry-run --offline --console=plain
done
bash ./gradlew -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --dry-run --offline --console=plain
```

Ordinary release BUILD SUCCESSFUL2m42s; обработанные target/test P5b APK3m06s;
p1Release lint1m21s,0 errors/15 warnings/1 hint. Все отдельные P1–P5a profiles
собраны/audited успешно. Dry-run всех шести runtime tasks проверил graph/gates,
не исполнил Android. Source identity проверена повторно после последней правки
Windows-инструкции; APK hashes/IDs остались прежними.

DEX linkage/hierarchy/package access, отсутствие duplicate/backend definitions
в test APK, необходимые classes/methods и обе mappings — PASS. Maintenance/rekey/
crypto только в target; synthetic helpers/keys только в test. Проверены6618 test
method refs,1886 field refs,30349 code edges,3950 pinned ICU resources и hashes.
Восемь негативных audits PASS: debug/второй backend/NFC data/inaccessible superclass/
missing runner class/method/inaccessible runner method/чужой mapping. Исходные
signed APK не изменены. Demo isolation:0 demo classes/images в ordinary release и
p1Release,27 classes/6 images только в debug; MainActivity launcher сохранён.

Artifact bundle `e7ac295d002b23120e596302341f33e76ec6d27d9c0f4ea67b69c26086299270`:

| Артефакт | APK SHA256 | R8 map ID |
|---|---|---|
| target | `0f28e552c557ebc2e5caeb742f2f30f352c4f160c5fd451092fbd3bfbd95a796` | `41b67a5183bc03ae45b9e4e35bcda32f64f8aedd95d89028adfedb2d370b00e4` |
| instrumentation | `c9c8cf11ab72e23d5add2eeef0084783e36d163b8684ba2cdf0eaa7688ab41b1` | `f7f45bba7a19ac7da29ffadbbe43d3a8caffe8296514e0e3bbfce71a34e31454` |

Обе mappings, их SHA256, configurations/ABI и оба APK сохранены вместе в
`app/build/reports/p5b-runtime/p1-diagnostics.zip` и artifact manifest. Outputs
локальные/не коммитятся; пользовательская сборка получает собственную привязку.
Фактический source capture: revision `5f91b77ca47ff38352060a64daa118ecb9733656`,
`sourceFingerprint=4151b5ea3877f7eb4e74cf008e49b8290dfa55e2855f83d9c060c2fa583e7137`,
sourceCodeDirty/sourceDirty/workingTreeDirty=true, wrapperModified=false.
Проверено рабочее дерево до публикации; это не заявление о build чистого итогового
commit. Runtime receipt сохранит фактическую revision/fingerprint/Gradle/JVM.


Android отсутствует: нет emulator/system images/dev-kvm,
adb не может создать служебный каталог в read-only HOME. Это NOT_RUN, не PASS.

Отдельная P5b fault enumeration: seed20539/0x503b,715 IO events/2938 failures,
8 journal publications/288 local fault combinations,8 durable snapshots и78
pre-cleanup checkpoint boundaries/156 lost-retained bytes+namespace crash images.
JVM injector повторно вызывает production credentials/unwrap/MAC/root; кэширует
только фиксированные synthetic Argon outputs. Runtime suite использует настоящий
KDF/crypto. Число сценариев не равно exhaustive power-loss доказательству.

## Android и открытые условия

[Windows/Android Studio инструкция](../../../tools/p5b/android-runtime-windows.md),
готовая **P5b Release Runtime**. Ровно8 методов P5bChangeSecretRuntimeTest на каждом
Honor Android15 и emulator Android17/API31+, failures/errors/skipped=0,
`runtime=PASS` и итоговый BUILD SUCCESSFUL. Затем отдельные P1/P2/P3/P4/P5a
регрессии7/8/8/10/8 на каждом устройстве. Старые/0/partial/stale/crash отчёты не PASS;
свежий gate проверяет XML/protobuf/exit/runId/APK/mapping/source identity.

```powershell
.\gradlew.bat -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp4ReleaseRuntime=true :app:p4ReleaseRuntimeCheck --console=plain
.\gradlew.bat -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
```

Успешный receipt: `app/build/reports/p5b-runtime/runtime-result.txt`; аналогичные
P1–P5a сохранить отдельно. При ошибке полный Terminal вывод и текущий
`app/build/reports/p5b-runtime/p1-diagnostics.zip`, XML/protobuf/exit/identity по
инструкции. Обе mappings связаны с APK SHA256; чужой mapping не достоверный retrace.

p1Release — non-debuggable, R8 обработан; suffix/test signing/instrumentation ABI
отличают его от ordinary release. Storage/ICU/CBOR/crypto остаются **вне** прежнего
androidx/kotlin/kotlinx optimization scope. PASS не byte/runtime identity обычного
release и не provider certification. Нужны новые пользовательские P5b+регрессии;
до них P5b не принят и PR Draft. Production SAF/profiles, физический power-loss,
Android lifecycle/OEM/leaks, рабочий UI/persistence и ordinary release runtime — P6.

## Пользовательское подтверждение от 22 октября 2026

Согласно подтверждению пользователя и сведениям из ТЗ P6a.1, шесть receipts
`P5b_results.zip` ранее проверены в чате. SourceRevision:
`2ec28bf8c458ace5b283a2fe6aad84872df7c3a1`. На каждом из Honor ABR-LX1 Android15
и эмулятора Android17: P5b/P1/P2/P3/P4/P5a соответственно **8/7/8/8/10/8 PASS**,
failures/errors/skipped=0; всего98. Это пользовательское выполнение на Windows,
не Android-исполнение агента. Raw XML в архиве отсутствовал; независимая повторная
проверка raw XML не заявляется. Подтверждены runtime gates этой APK-пары;
SAF/provider/power-loss/lifecycle/OEM/полная security review остаются отдельными gates.

```properties
artifactBundleId=56f5257ce4f4a1aa88cf29dcdb145c0e4de5fc7dfeadcc40e5115e04247a986e
p5bRunId=33d05211-e38a-4c62-9916-54cbb8bf53b2
sourceFingerprint=59306de5566341a3a63299f9629c3ec66e851cf5ea402eaee4c1c219010350b5
gradleVersion=9.6.0
jvmVersion=25.0.3+-15898627-b508.16
jvmVendor=JetBrains
sourceCodeDirty=false
sourceDirty=true
workingTreeDirty=true
wrapperModified=true
```

Все шесть receipts связаны с указанным artifactBundleId. Сохраняются прежние
ограничения p1Release относительно ordinary release: suffix/test signature/ABI,
частичная R8 scope; storage/ICU/CBOR/crypto вне optimization. Исторические
`Android агента NOT_RUN` и результаты сборок выше остаются историческими.
Они не переименованы в agent PASS; новые P6a APK требуют новых прогонов.
