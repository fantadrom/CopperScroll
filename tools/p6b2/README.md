# P6b.2 evidence tooling

Read [Windows/device instructions](android-runtime-windows.md) and
[report/matrix](../../docs/architecture/validation/p6b2-report.md).

`p6b2ReleaseRuntimeCheck` delegates to the existing fresh XML+protobuf+runner-exit
strict gate, with twelve exact methods. Runtime API generation and BOTH mappings
remain bound to actual APK hashes. `testP4SourceIdentity` is mandatory. Host fixtures
are synthetic evidence of the gate, never Android execution.

`PrivacyProbeController` controls independent privacy, primary-process death,
handoff, backup and ordinary-release probes. Each invocation has a UUID directory,
each device a new UUID; primary responses precede acceptance and failure archives.
Process preparation covers separate Open-with-canary and Locked-with-picker deaths.
Both preparations are intentionally interrupted externally and never counted as
completed PASS. No IME/autofill/accessibility service is installed or selected.

Backup requests run only on an emulator with the local transport ALREADY selected
and enabled. Otherwise the exact branch is NOT_RUN; personal Honor is always skipped
for backup requests. No cloud upload, reset, data clear, transport switch, root, or
uninstall. Probe removes only its exact three synthetic canary paths/preferences.
Transport contents/restore/OEM/iOS claims remain independent and open.

Ordinary-release probe signs a COPY of the actual optimized unsigned release APK,
verifies unchanged ZIP code/resources, saves certificate/mapping/APK hashes and
installs without uninstall/clear. Signature conflict is FAIL. Existing local Android debug key is used only for this copy and is never committed/archived. The target has no test components,
instrumentation ABI or debuggable/run-as access. UI automation uses the public launcher,
real demo editor and Home only. External observer runs in the isolated p1runtime process and filters UiAutomation
nodes to the ordinary package before reading static controls/synthetic editor. No
ordinary target ABI is used. Full raw UI hierarchy/window/clipboard data are NOT
saved or dumped to temporary files. Synthetic output and safe codes only. This does not create an authenticated
container editor, durable note-save ACK, or qualified storage path.

Useful host checks:

```bash
bash gradlew :app:testDebugUnitTest :app:testP6b2RuntimeResults :app:testP4SourceIdentity
bash gradlew -Pp6b2ReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity :app:verifyP6aIsolation :app:verifyP6b2OrdinaryRelease --no-configuration-cache
python3 tools/p6b1/check-backup.py p6b2
python3 tools/p6a/check-isolation.py p6b2
```

`python3 tools/p6b2/check-privacy-apks.py` additionally rejects an actually exported
privacy Activity and a wrong ordinary-release mapping marker, using APK copies only.
