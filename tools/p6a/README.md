# P6a.1 — Android adapters и gates

[Windows runtime/probe](android-runtime-windows.md) ·
[отчёт и ID-матрица](../../docs/architecture/validation/p6a-report.md).

```sh
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:testP6aRuntimeDiagnostics --console=plain
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

20 дополнительных host checks проверяют диагностический ZIP после success/failure,
отсутствующие/старые результаты, attempt identity, mappings, сохранение исходной
ошибки и suppressed ошибки записи. Финализатор собирает свежие runtime результаты
и причину отказа даже если connected завершился ошибкой; он не заменяет строгий
gate и не создаёт runtime PASS. Доступ собственного provider определяется по
ProviderInfo.applicationInfo.uid; внешний требует полный read/write URI-grant.
Реальная граница разных UID проверяется synthetic provider в test APK, без второй
копии backend или общей Kotlin-зависимости в его отдельном процессе.
Bootstrap grant идёт через отдельный owner service с signature permission и
проверкой точного UID/подписи; DocumentsProvider остаётся закрытым до grant.
Audit читает фактические AXML ACL и транзитивные DEX ссылки обоих отдельных процессов.
Недопустимый Binder payload получает SecurityException: исключён шаблон throw
IllegalArgumentException(String), который compiler переносил в общий synthetic
helper с target-only AndroidX ссылками. Keep rules и optimization scope прежние.

ProviderRestartFaults — test-only файл состояния процесса: one-shot потребляется
до смерти, persistent fault после перезапуска остаётся unavailable до явного clear.
Пять JVM-тестов проверяют новые экземпляры journal, повторный отказ, clear,
повреждение и границы имён. Runtime проверяет восстановленные identity/полный
fingerprint/гарантии и закрытие store, сохраняя отрицательные проверки и13 методов.

productionPolicy компилируется в debug/ordinary release и p1Release регрессий.
p6aHarness подставляет policy только в явно выбранный p1Release; классы provider,
helper, fault controls и диагностическая Activity отсутствуют в ordinary release.
Адаптеры/core/crypto одинаковые. Production allowlist пуста.
