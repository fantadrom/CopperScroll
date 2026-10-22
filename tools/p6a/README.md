# P6a.1 — Android adapters и gates

[Windows runtime/probe](android-runtime-windows.md) ·
[отчёт и ID-матрица](../../docs/architecture/validation/p6a-report.md).

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults --console=plain
bash ./gradlew -Pp6aReleaseRuntime=true :app:verifyP6aIsolation :app:verifyP4BuildIdentity :app:lintP1Release --console=plain
bash ./gradlew -Pp6aReleaseRuntime=true :app:p6aReleaseRuntimeCheck --console=plain
bash ./gradlew -Pp6aReleaseRuntime=true :app:p6aProviderProbe --console=plain
```

Первые две команды — host/build gates. Только третья даёт Android runtime PASS
после13 завершённых методов P6aSafRuntimeTest на каждом выбранном устройстве.
Четвёртая — отдельный явный scratch probe с системным picker; не часть automatic
suite и не production approval. Не совмещать runtime flags.

18 host checks валидатора включают два устройства, XML testsuites/вложенность,
0/partial/duplicate/failure/error/skipped/stale/missing, protobuf/runner exit и
source provenance. Источник tools/p6a и все app/src, включая variant/provider/
assets, входят в строгий byte fingerprint; Windows fixes P4 сохранены.

productionPolicy компилируется в debug/ordinary release и p1Release регрессий.
p6aHarness подставляет policy только в явно выбранный p1Release; классы provider,
helper, fault controls и диагностическая Activity отсутствуют в ordinary release.
Адаптеры/core/crypto одинаковые. Production allowlist пуста.
