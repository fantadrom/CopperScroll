# P5b — ChangeSecret

[Windows / Android Studio](android-runtime-windows.md),
[отчёт и нормативная матрица](../../docs/architecture/validation/p5b-report.md).

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults --console=plain
bash ./gradlew -Pp5bReleaseRuntime=true :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity --console=plain
bash ./gradlew -Pp5bReleaseRuntime=true :app:p5bReleaseRuntimeCheck --console=plain
```

Первые две команды проверяют JVM/host и артефакты; Android PASS даёт только
последняя команда после точных восьми завершённых методов на каждом устройстве.
Не включать несколько runtime flags одновременно. `verifyP1ReleaseApks` и
`verifyP4BuildIdentity` — общие task names; property выбирает P5b report directory,
source provenance и suite. Обязательные Windows/source gates сохранены.

P5bChecks вызывает production coordinator/credentials/crypto/rekey/recovery.
Independent P5bOracle проверяет positive data, старый ZDK с правильными refs/AAD и
последующую обычную T. P5bFaultTest отдельно перечисляет все наблюдённые IO и
journal transitions, before/after/partial failures и pre-cleanup crash images.
Кэширование фиксированных synthetic Argon outputs применяется только в JVM
fault injector; device suite использует реальный KDF. Это не provider/power-loss
сертификат. Fixture keys/helpers остаются в test APK, не в target.

18 host checks нового gate охватывают точный PASS, два устройства, XML wrappers,
ноль/неполный/duplicate/skipped/failure/отсутствующий/stale XML/protobuf/exit,
чужие device/suite и provenance. PASS печатается после cleanup, primary exception
сохраняется с suppressed cleanup. Проверка source identity включает tools/p5b,
в том числе ignored inputs, и не нормализует исходники ради PASS.
