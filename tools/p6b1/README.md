# P6b.1 session/lifecycle verification

[Windows commands, exact8-method suite and separate guided probe](android-runtime-windows.md).
[Architecture/state table and evidence matrix](../../docs/architecture/validation/p6b1-report.md).

Opt-in property `p6b1ReleaseRuntime=true` includes the existing P6a harness under
one flag, plus a nonexported sensitive host and memory-only real synthetic login.
Ordinary release contains SessionCoordinator/SessionAuthentication/SessionWorker
and Android presentation/signals/window root; synthetic bootstrap/host is excluded.
R8 scope/signing/runner/backend-copy contracts remain unchanged.

Host gate fixtures (24 cases, including manual runner completion/crash checks): `:app:testP6b1RuntimeResults`.
APK backup AXML + manifest resource-reference gate includes positive +5 negative rule fixtures, plus12 actual
mutated-APK negatives from `python tools/p6b1/check-backup.py` after building.
`python tools/p6a/check-isolation.py p6b1` exercises shared component isolation/ACL
against the current P6b.1 APKs; signed originals are never modified/installed.

Automatic output: `app/build/reports/p6b1-runtime/runtime-result.txt`, both mappings
and APK hashes in `artifact-manifest.properties` and `p1-diagnostics.zip`.
Guided output: `app/build/reports/p6b1-manual/<hostRunId>/device-N/manual-result.txt`
and `lifecycle-diagnostics.zip`. Current compile/R8/audit/runner failures also
produce `app/build/reports/p6b1-build-attempts/<attemptId>/build-diagnostics.zip`.
The host persists the instrumentation response BEFORE checking success. Manual
receipt means USER_OBSERVATIONS_COLLECTED, not full automated LIFE/LEAK acceptance.

All data is synthetic. Diagnostics contain bounded lifecycle labels/status/code/
stack locations, no editor content/secrets/keys/user-file URI. No device runner
here means Android NOT_RUN regardless of a successful APK build/audit.
