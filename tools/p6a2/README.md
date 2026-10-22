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
then PREPARED before worker termination/reboot. Raw URI/fstat/local-state identity
stay under app-private noBackup. The service is the existing nonexported helper,
with sequential IO and production publisher locks. No new production exports/UI.
All opened stores/FDs/local state are closed via use; timeout is failure and does
not prove quiescence. Worker process death is Binder-confirmed before fresh recovery.
Reboot preparation returns without rebooting; manual normal shutdown semantics are
explicit. No provider owned by Android/OEM is killed by this tool.

Recovery checks persistable read/write grants, unchanged environment, full bytes,
observed fstat equality, closed store, whole local binding+journal, production refusal
and fresh process/boot evidence. Successful recovery consumes the checkpoint. Unknown
create/delete outcomes are never resolved by searching names. All scratch documents
are retained; automatic SAF deletion is disabled without compare-delete contract.
Host run history is also limited to32 directories; preserve/move earlier evidence before another run.
At most32 private qualification sessions are created; session exhaustion is explicit
failure, not deletion of evidence. Keep artifacts until review; user may later remove
ONLY their chosen dedicated scratch folder. Never select a personal container.

## Diagnostics and failure semantics

`app/build/reports/p6a2-qualification/<hostRunId>/<deviceHash>/` contains compact
qualification-result.txt, ledger.properties, separate method receipts/logs and first
failure type. The host directory preserves one source/artifact pair and its immutable
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
