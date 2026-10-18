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

Android gate: `StorageDependencyTest` must run on API31+ (and the two-device S1 matrix).
An assembled test APK or JVM corpus pass is not execution on Android. Run connected
instrumentation in an equipped environment; also exercise the dependency calls in a
release/R8 target before accepting P1. Current runtime/packaging evidence and remaining
gates are in [p1-report](../../docs/architecture/validation/p1-report.md).
