# P6a.2 qualification tools

These tools record **observations**, never production authorization. The compiled
production ProfileAdmission remains empty. Research scope and gaps:
[qualification](../../docs/architecture/storage-provider-qualification.md),
[ADR](../../docs/architecture/adr/001-saf-durability-boundary.md),
[report](../../docs/architecture/validation/p6a2-report.md),
[Windows steps](android-qualification-windows.md).

## Reproducible commands

```sh
bash gradlew :app:testP6a2Qualification :app:testP4SourceIdentity --console=plain
bash gradlew -Pp6aReleaseRuntime=true :app:p6a2ProcessQualification --no-configuration-cache --console=plain
bash gradlew -Pp6aReleaseRuntime=true :app:p6a2RebootPrepare --no-configuration-cache --console=plain
# Only after the user's ordinary restart/unlock, copy the host runId printed by prepare:
bash gradlew -Pp6aReleaseRuntime=true -Pp6a2RunId=HOST-RUN-ID :app:p6a2RebootResume --no-configuration-cache --console=plain
```

The first command is host only. Other commands require Android/adb and explicit
scratch-folder selection; none reboot a device. A non-primary selection is refused before scratch creation. Use one opt-in flag per invocation.
Each controlled experiment has exactly **two** completed instrumentation methods
per selected device: prepareWorker/recoverWorker or prepareReboot/recoverReboot.
PrepareReboot alone is one completed method, experimentOutcome=NOT_RUN. These four
methods are an explicit controller API, not a newly selected connected correctness
suite. The automatic P6a suite retains all13 names/XML/protobuf/runner-exit/freshness
gates. The probe used during preparation runs inside the prepare method; it is not
another claimed standalone test. Actual backend resides in the target APK.

Configuration cache is disabled only for these explicit commands: the failure
finalizer needs this invocation's task failure states. R8/keep rules/optimization
scope/toolchain are unchanged. SDK>=31, runtime BOOT_COUNT must be available;
unavailable boot evidence causes failure/unsupported evidence, never skipped PASS.

## Evidence schema and invariants

`evidence-ledger.properties` is the bounded version1 research manifest, with four
explicit scopes. Each applicable guarantee has its own evidenceId, contract,
processExperiment and powerExperiment. Exact unknown dimensions remain UNKNOWN;
NOT_APPLICABLE does not grant a guarantee. This manifest is host input, not a
production policy registry or destination permission. Its source contract is a
reference implementation, not a claim about the actual OEM/Android17 build.

`QualificationLedger` defines the executable observation schema version1,
policyVersion=P6A2-RESEARCH-1. Per-device `ledger.properties` includes runId,
intervention, phase, experimentOutcome, completedMethods, sourceRevision/fingerprint,
all source dirty/wrapper/Gradle/JVM fields, APK/mapping IDs/hashes and artifactBundleId,
provider/OS build hashes/version/API, selected root hash, UNKNOWN volume/backend,
contracts, grants/identity/lock/recovery observations and cleanup status. Each of
existingWriteDurable/durableCreateIdentity/durableLocalState stays UNKNOWN;
productionAdmission=REFUSED, productionApproved=false are independent of experiment
PASS. physicalInterruption/controlledStorageInterruption remain NOT_RUN. There is
no global qualification=PASS or caller-controlled qualified flag.

The validator limits UTF-8 to64KiB,160 fields,4096 chars/value; rejects duplicate keys,
unknown schema/policy, promotion, missing source/artifact identity and private URI/path/
secret fields. Terminal decoder requires one exact completed method/status0, correct
class, one receipt matching method/runId, OK(1 test), runner final -1 and host exit0;
zero/partial/skip/failure/crash/duplicate/foreign evidence fails. Each prepare/resume
pair matches scope/root/artifacts/source/device set and phase sequence. Worker
recovery requires changed PID + session and same boot count; reboot requires a
strictly increased public Settings.Global.BOOT_COUNT and changed worker session.
Numeric PID reuse across reboot is allowed. Replaying a consumed/failed attempt is
rejected. A previous host receipt cannot create a missing Android checkpoint.

The host checks hashes of BOTH installed base APKs (pm path + sha256sum), not merely
local files. No install occurs during reboot resume. Android environment is compared
privately across the interruption; OS/provider update causes refusal. The ordinary
release remains free of every harness controller/fault/grant component. Source
fingerprint includes tools/p6a2, even ignored sources. No EOL normalization is applied
to the actual repository; Windows fixture handling from P4 is preserved.

## What the experiment does

A test-only Activity requests a dedicated clean primary-storage folder. Preparation
performs the existing raw IO/lock probe, then a same-UID helper creates ONE additional
UUID scratch, writes deterministic262144 bytes, forces/closes and reads through the
production read-only adapter. It initializes/replaces a real AppPrivateLocalState
record (synthetic binding + Prepared journal), hashing the whole decoded record.
This is not a container/VerifiedCopy and is not a new write policy. Existing controlled
P6a/P3/P5 fault suites cover full transactions/copy/compact/rekey/recovery. They remain
separate from real-root observations, which cannot execute those forbidden workflows.

Private checkpoints are forced before/after meaningful create/write/force phases,
then PREPARED before the final read-back checks. A separate forced
preparationVerified=true marker is written only after every read-back/close succeeds;
recovery requires that marker. PREPARED alone cannot authorize worker termination,
recovery or a completed method. Raw URI/fstat/local-state identity
stay under app-private noBackup. The service is the existing nonexported helper,
with sequential IO and production publisher locks. No new production exports/UI.
All opened stores/FDs/local state are closed via use; timeout is failure and does
not prove quiescence. Worker process death is Binder-confirmed before fresh recovery.
Reboot preparation returns without rebooting; manual normal shutdown semantics are
explicit. No provider owned by Android/OEM is killed by this tool.

Recovery checks persistable read/write grants, unchanged environment, full bytes,
mode-scoped fstat observations, closed store, whole local binding+journal, production refusal
and fresh process/boot evidence. Successful recovery consumes the checkpoint. Unknown
create/delete outcomes are never resolved by searching names. All scratch documents
are retained; automatic SAF deletion is disabled without compare-delete contract.
Host run history is also limited to32 directories; preserve/move earlier evidence before another run.
At most32 private qualification sessions are created; session exhaustion is explicit
failure, not deletion of evidence. Keep artifacts until review; user may later remove
ONLY their chosen dedicated scratch folder. Never select a personal container.

## Mode-scoped identity observations (version2)

Each stage reads/verifies through the real production read-only adapter, closes it,
then independently opens/closes r,r,rw,rw descriptors of the owned synthetic scratch.
Every sample must be regular,262144 bytes and have the requested O_RDONLY/O_RDWR
access flags. No descriptor duplicates are opened/closed under an adapter lease.
These raw rw samples never write/truncate and cannot qualify the production factory.
Data is read/verified AGAIN after all mode samples, so a destructive provider open
cannot silently pass the data check.

Private checkpoints retain the original creation rw sample and distinct preparation
r/rw baselines; recovery never replaces them. Receipts expose only three booleans per
pair: `.deviceMatches`, `.inodeMatches`, `.matches` (the conjunction of BOTH fields):

| Pair field | Comparison |
| --- | --- |
| identityCreatedRwToR | Original creation rw FD → current first r FD (legacy comparison) |
| identityCreatedRwToRw | Original creation rw FD → current first rw FD |
| identityRwToR | Current first rw FD → current first r FD |
| identityRToR | Current r → independent r reopen |
| identityRwToRw | Current rw → independent rw reopen |
| identityRAfterRestart | Saved preparation r → first r after worker death/reboot |
| identityRwAfterRestart | Saved preparation rw → first rw after worker death/reboot |

The last two pairs are explicitly NOT_RUN during preparation; all21 fields must
contain coherent booleans during recovery. identityObservationVersion=2 rejects
legacy receipts/checkpoints. Raw dev/inode remain private; no inode-only comparison,
content-based identity or read/write alias/lock inference is introduced.

A mismatch is a measured finding and does not stop collection/recovery of synthetic
data. An inaccessible/missing/malformed sample remains a failed experiment. Full
method/runner/count/source/artifact/scope/byte/local-state checks remain mandatory.
Data outcome is PREPARED_ONLY after prepare; PASS requires successful recovery and
exactly two completed methods/device. No failed/lost-ACK method can confirm data.
identityConclusion is ALWAYS UNCONFIRMED, even with matching samples. identityStability
combines BOTH receipts (a preparation mismatch cannot disappear on recovery):

- SAME_MODE_UNSTABLE_OBSERVED: any same-mode/creation-rw/restart pair differs;
- MODE_DEPENDENT_OBSERVED: same-mode samples match, but a cross-mode pair differs;
- SAMPLED_MATCHES_ONLY: all collected pairs match; this still grants no identity contract.

NOT_OBSERVED/INCOMPLETE describe missing measurements. Thus experimentOutcome=PASS
means completed data recovery and observations, and may coexist with identity
instability. The compact report prints dataOutcome, identityConclusion,
identityStability and all per-pair fields. The lock label is
PROBE_OBSERVED_NOT_IDENTITY_QUALIFIED: earlier interprocess probe observations do
not prove safe r/rw aliases or lifetime locking. Production still compares full
qualified dev+inode, requires its pre-open identity/locking contract and refuses
unqualified writes; productionAdmission=REFUSED and all three gates remain UNKNOWN.

## Diagnostics and failure semantics

`app/build/reports/p6a2-qualification/<hostRunId>/<deviceHash>/` contains compact
qualification-result.txt, ledger.properties, separate method receipts/logs and first
failure type. Every invocation saves `<method>-helper-response.properties` BEFORE
checking the strict terminal gate, including a status=error receipt. Missing/malformed
receipts get `<method>-helper-response-unavailable.txt`; command failures preserve any
partial response too. Only method/runId-bound fields can update the ledger; accepted
`<method>.properties` still requires the strict method/runner gate.

checkpointPhase is the latest observed private checkpoint, not completion. phase is
host progress (PREPARING/PREPARATION_FAILED/PREPARED/RECOVERING/RECOVERY_FAILED/RECOVERED);
preparationVerified records the helper's forced post-verification marker, while
preparationCompleted means the prepare method actually passed the strict gate.
completedMethods remains0 on prepare failure even if checkpointPhase=PREPARED.
A lost acknowledgement or failed runner cannot promote either marker to method PASS.
Recovery receipts refresh checkpoint/PID/session in the ledger instead of leaving
preparation's values behind. helperMethod identifies the response; transient diagnostic
values are replaced per response, so a previous method's checks cannot look current.

Failure codes VERIFY_MODE, VERIFY_SIZE and VERIFY_HASH retain the mandatory data/
read-only adapter checks. VERIFY_IDENTITY now denotes a failed descriptor/sample
operation or malformed private baseline, not an observed identity inequality. Safe
observations include writable/grant-count/durability, expected/observed size,
contentHashMatches and mode-specific device/inode comparisons.
Operation stages also distinguish open/read/close/checkpoint/local-state failures.
Bounded failureTypeN/failureOriginN/failureFramesN/failureRelationN preserve the
primary, cause and suppressed exception frames (up to4 nodes/6 frames each, with
explicit truncation). Messages, raw URI/path/fstat identity, content digests and data
are never serialized. Consult the exact target mapping from the same artifact pair
if helper frames need retrace; test mapping is for the instrumentation stack.

The host directory preserves one source/artifact pair and its immutable
mapping/configuration ZIP, plus qualification-diagnostics.zip. Reboot resume uses
the existing host directory; no stale output is accepted for a new method execution.
The public ledger exposes no user URI/path/binding/credentials/plaintext. Error logs
contain test failures, synthetic metadata and tooling diagnostics only.

`app/build/reports/p6a2-build-attempts/<attemptId>/build-diagnostics.zip` is a separate
Gradle finalizer archive, including first failed task/stack. It runs on preparation,
compile/R8/audit/controller failure; artifacts are included ONLY if THIS invocation's
APK audit succeeded, otherwise explicitly NOT_AVAILABLE. Configuration/script failures
before Gradle can create tasks require full Terminal output. Controller failures also
preserve fresh instrumentation/checkpoint-phase output when obtainable. No archive or
finalizer can issue runtime PASS. A failed connected regression uses the existing P6a
runtime diagnostics finalizer as before. Do not mix an earlier APK pair into retrace.

No Android runtime, reboot, VM/storage or physical interruption is inferred from host
checks, successful build, previous P6a.1 receipts or a clean lint run.
