# P4: VFS/cards validation

Production код находится в `storage/vfs`, `storage/cards` и существующем P3
`TransactionCore`. Формат и budgets: [нормативное расширение](../../docs/architecture/container-p4-extension.md).
Итоги: [P4 report](../../docs/architecture/validation/p4-report.md).

`fixtures.py` — независимый Python stdlib encoder, без вызовов production Kotlin.
`python tools/p4/fixtures.py --check` проверяет provenance и одинаковые JVM/Android
3 positive /12 negative fixtures. `--write` предназначен только для осознанного
обновления схемы; старые P1/P2 fixtures не изменяются.

```text
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP4SourceIdentity --console=plain
```

JVM P4:10 shared scenarios,5 boundary tests, independent model (seeds328705/328706,
по128 commands), fault matrix(seed328707). Последняя не включена в Android suite.
Host gate fixtures проверяют exact set, XML/protobuf/exit/run identity, вложенные
testsuites без двойного счёта и отрицательные исходы. Source identity tests
используют временные синтетические Git repositories, а не пользовательские файлы.

[Windows / Android Studio](android-runtime-windows.md):10 runtime методов после R8,
одна команда; P1/P2/P3 regression запускаются отдельно без P4-флага.

`RuntimeSourceIdentity` фиксирует реальную версию текущего Gradle/JVM, Git HEAD,
SHA256 исходников и wrapper, признаки общих/source/code/wrapper modifications.
Capture предшествует обоим pre-build tasks; verify выполняется до установки и
после runtime. Изменение исходников/HEAD/wrapper во время запуска — ошибка.
В fingerprint входят tracked/untracked и ignored source/assets под app/src,
tools/p1…p4, gradle, а также Gradle build scripts/wrappers; __pycache__/pyc исключены.
Пределы:20000 files,256MiB суммарно,64MiB/file, symlinks не поддержаны.
Читаются только эти исходники и Git metadata, не credentials/environment files.
Raw bytes fingerprint зависит от checkout line endings и не обещает совпадения
Windows/Linux. SourceCodeDirty отделён от WrapperModified; clean HEAD сам по себе
не доказывает отсутствие ignored inputs. Изменения только документации отражены
workingTreeDirty, но не считаются изменением compiled source (tools docs входят
в fingerprint консервативно). Статусы и build-identity SHA автоматически связаны
с runId и artifact bundle в runtime-result.txt. Capture/build/audit — не runtime PASS.
