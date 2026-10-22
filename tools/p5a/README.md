# P5a — copy / selective compact

[Windows / Android Studio / Honor15 + emulator17](android-runtime-windows.md).
Основной отчёт и нормативная матрица:
[validation/p5a-report.md](../../docs/architecture/validation/p5a-report.md).

Host/JVM, из корня проекта, закреплённый wrapper:

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults --console=plain
bash ./gradlew -Pp5aReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity --console=plain
```

`verifyP1ReleaseApks`/`verifyP4BuildIdentity` — общие существующие task names;
property выбирает P5a suite, source provenance и `reports/p5a-runtime/`.
Это сборка/audit, **не Android PASS**. Runtime запускается единой командой:

```sh
bash ./gradlew -Pp5aReleaseRuntime=true :app:p5aReleaseRuntimeCheck --console=plain
```

P5aChecks —8 содержательных методов в JVM и Android; расширенные JVM
P5aFaultTest/P5aBoundaryTest проверяют IO/journal/restart, opaque schema oracle,
filled template flag, host FileChannel и границы. Fault matrix явно указывает
seed, события и число сценариев в XML system-out; не называть это exhaustive
power-loss доказательством. Fixtures и импортированные test keys остаются в
`testShared`/test APK, не production target. Криптографические примитивы и T
берутся из production P1–P4.

`VerifyP5aRuntimeResultsTest.java` проверяет exact suite, два устройства,
вложенные XML wrappers без двойного подсчёта, отсутствие/0/неполный набор,
failure/error/skipped, неверные suite/device, protobuf/exit code/freshness и provenance.
`RuntimeSourceIdentityTest` из P4 остаётся обязательным с Windows-safe cleanup,
CRLF/autocrlf и suppressed exceptions. Фактический байтовый fingerprint не ослаблен;
в область наблюдения добавлены `tools/p5a/` (включая ignored source files).
