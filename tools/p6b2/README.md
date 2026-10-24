# P6b.2 evidence tooling

Read [Windows/device instructions](android-runtime-windows.md) and
[report/matrix](../../docs/architecture/validation/p6b2-report.md).

`p6b2ReleaseRuntimeCheck` delegates to the existing fresh XML+protobuf+runner-exit
strict gate, with fourteen exact methods. Runtime API generation and BOTH mappings
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

The 24 October correction adds a real injected touchscreen long-press regression
(text shortening/replacement, end offsets, empty/multiline/emoji, synchronous
selection callback mutation/reentry and binding/revoke changes), plus a portrait/
landscape harness viewport regression. Both are mandatory exact methods; older
12-method reports cannot satisfy the current gate. Touch points are recomputed
from the laid-out editor, not screen constants. Ordinary-release taps already use
current accessibility node bounds.

Shared P6b.1/P6b.2 host, synthetic picker and owned dialog reserve an outer viewport
before scrolling: effective top/bottom distance is max(40dp, safe system inset),
left/right reserve system bars/cutout, existing decor consumption is subtracted.
IME overlap is measured after adjustResize to avoid applying it twice. Geometry
unit checks cover consumed insets and IME; actual IME, Honor corner/cutout visibility
and both device orientations still need the guided observations. This does not
change coordinator rules or qualify storage. The user reports that the repeated stage-2 handoff passed (24 October chat);
the former Honor handoff failure is historical, not a current OPEN condition.
The supplied runtime ZIP contains automatic-suite evidence, not a handoff receipt.

The follow-up to 16c1d3d synchronizes insets without a deferred padding post and
waits for two stable pre-draw samples of the root and descendant layout/scroll.
The picker uses explicit WRAP_CONTENT controls, checks the entire button after
scrolling and injects a real tap at its measured centre, and cleanup closes only test-owned picker/dialog windows before restoring
orientation. Primary failures retain cleanup errors as suppressed.

Gesture clipping is converted from root-view to screen coordinates, including
window offset and internal editor scrolling. The requested line is exposed before
coordinates are sampled. The real DOWN/UP injection and selected-word assertions
remain mandatory; no direct handler call substitutes for the gesture.

Sensitive-copy probes use fresh synthetic values and exactly one production write/
one read. Production refuses export unless its outgoing clip still has IS_SENSITIVE.
`P6B2_SAFE_CLIPBOARD` records booleans only: successful pre-write verification,
item/payload/neutral-label match, key presence/value after read, foreground,
extras, known-host-label/overlay and timestamp-window indicators. It logs no
clipboard text/hash/label/URI. Replaced/missing items and lost sensitive flags all
fail. Host echo is a possible independent cause, never an assumed PASS or an
automatic reason to disable synchronization. No automatic clipboard re-copy/retry.
