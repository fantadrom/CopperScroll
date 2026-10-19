# P1 verification tools

These tools use only synthetic/public fixtures. They do not create a protected vault.
The CLI opens its input with `FileChannel` READ, follows references and never scans for magic.
SHA256 checks are structural corruption checks, not authentication.

Run from the repository root with the existing Android SDK/JDK/Gradle environment:

```bash
python3 tools/p1/fixtures.py --check
python3 docs/architecture/reconciliation-materials/validate_docs.py
python3 -m pip install --require-hashes --target work/p1-reference -r tools/p1/requirements-reference.txt
PYTHONPATH=work/p1-reference python3 tools/p1/unicode_reference.py
PYTHONPATH=work/p1-reference python3 docs/architecture/reconciliation-materials/check_fixtures.py --adapter python3 tools/p1/s1_reference.py
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint :app:assembleRelease :app:assembleDebugAndroidTest
bash tools/p1/inspect.sh app/src/test/resources/p1/synthetic.cscroll
```

`inspect.sh` compiles the core with Gradle, then compiles a JVM-only read-only adapter.
It expects the pinned versions and AGP's built-in Kotlin classes directory, and uses
`GRADLE_USER_HOME` to find resolved jars. Exit0 means the bounded public structure
passed; exit1 means a structural/read error; exit2 means invocation/setup error.
No secret hints, record IDs, paths or plaintext are printed. Files above1TiB fail before reads.
Default traversal: map accounting≤32MiB,≤16777216 records/zone,≤1GiB reads,
30s elapsed and cancellation at each read/page. Exceeding a budget returns ResourceLimit.
The map accounts256B per range, including tree entry/boxed keys and spare capacity;
there is no full-file allocation. Raw record read≤1MiB+92B and page read16KiB.

Golden bytes in `app/src/test/resources/p1` are frozen in Git. `fixtures.py` is an
independent Python stdlib author using `struct`, `hashlib` and a small fixture-only
CBOR encoder. It never calls production Kotlin. `--check` compares against frozen
bytes; running without it regenerates fixtures intentionally. `golden.json` gives
expected field arrays (byte strings use `{ "bytes": "hex" }`); adjacent `.hex` files
are loaded by JVM tests. Binary examples cover full superblock, records, catalogue
leaf/branch, valid one/two-zone files and cyclic/overlapping negative graphs.
Arbitrary ciphertext/tag bytes are deliberately invalid for real AEAD use.
Reconciliation TSVs are lossless projections of the existing six wire and21 S1 fixtures.

The complete official Unicode16 NormalizationTest is committed as deterministic gzip
(no timestamp/name); raw SHA256 is checked by both independent runners. Source and
artifact checksums are in `provenance.json`; licenses/notices are in `licenses/`.
`unicode_reference.py` uses unicodedata2's CPython-derived C normalization and generated
UCD16 data, not ICU, `unicodedata`, or an OS normalizer. It performs all20 invariants
per row (NFC/NFD/NFKC/NFKD) and the Part1 exclusion identity sweep for every remaining
Unicode scalar. `SecretEncodingTest` performs the same on ICU4J76.1 plus the S1 NFC path.
The reference script is not shipped in the app. System Python UCD15 fails the required
Unicode16 composition example and is explicitly not accepted as a backend.

## Android debug / release runtime

The user confirmed one successful debug instrumentation run on physical Honor ABR-LX1,
Android15: `:app:connectedDebugAndroidTest`, `StorageDependencyTest.pinnedUnicodeAndCborOnAndroid`,
1 passed / BUILD SUCCESSFUL. This is user-supplied evidence, not an agent run, release run,
or a two-device matrix. Unsafe/protobuf warnings did not fail it.

For the ready Windows/Honor procedure, see [android-runtime-windows.md](android-runtime-windows.md).
Shared Android Studio Gradle run configuration: **P1 Release Runtime**. Equivalent PowerShell:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:connectedP1ReleaseAndroidTest --console=plain
```

`p1Release` inherits release optimization/R8 unchanged; only the install identity,
local test signing, dependency matching fallback and a non-UI asset marker differ.
Normal release signing is untouched. Current optimization scope is only `androidx.**`,
`kotlin.**`, `kotlinx.**`; storage/ICU/CBOR are outside it. A pass validates backend calls
in the APK after the existing R8 pipeline, not optimization of these excluded packages.
One actual test-only build failure (`javax.lang.model.element.Modifier` from Error Prone
annotations) is resolved by excluding that compiler-metadata artifact only from the
P1 instrumentation runtime classpath. It remains on the compile classpath; no keep/dontwarn
rules, dependency-version changes, or R8 disablement are used.

The opt-in selects `testBuildType=p1Release` and only `P1BackendRuntimeTest` (7 tests,
including all21 synthetic S1 vectors). Without it, default debug instrumentation remains
available; the P1 release connected task is not registered. Each test asserts its expected
target package, release marker/debuggable=false, and class identity through target loader.
There is no diagnostic screen or user entry point.

`connectedP1ReleaseAndroidTest` depends on `verifyP1ReleaseApks`, which builds both APKs
and runs the JDK11-compatible `VerifyRuntimeApks.java` audit. It reads actual DEX class_defs:
backend in target, no storage/ICU/CBOR implementation in instrumentation. It checks pinned
NFC/repertoire data hashes, all3950 ICU resources, licenses, and independently authored
fixtures (`settings-default.hex`,21-vector `s1.tsv`, fixed noncanonical CBOR).
The test APK contains fixtures, not production backend sources/resources. Full Unicode
conformance remains on JVM and is not packaged in instrumentation.

Agent preparation without an Android runner (bash; normal SDK/JDK environment):

```bash
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lint :app:assembleRelease :app:assembleDebugAndroidTest
bash ./gradlew -Pp1ReleaseRuntime=true :app:verifyP1ReleaseApks :app:lintP1Release
bash ./gradlew -Pp1ReleaseRuntime=true :app:connectedP1ReleaseAndroidTest --dry-run
python3 tools/p1/check-apk-audit.py
```

Preparation audit: `app/build/reports/p1-runtime/apk-audit.txt` (`runtime=NOT_RUN`).
Actual runtime HTML: `app/build/reports/androidTests/connected/p1Release/index.html`.
XML/logs: `app/build/outputs/androidTest-results/connected/p1Release/`.
An assembled APK, audit PASS or dry-run is not a runtime PASS. Honor release-runtime and
another Android configuration API31+ remain open until actual results arrive. The same
ready command runs the21 S1 vectors on each configuration. Current acceptance/evidence
matrix: [p1-report](../../docs/architecture/validation/p1-report.md).
