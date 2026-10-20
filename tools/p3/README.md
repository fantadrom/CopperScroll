# P3: синтетические транзакции и fault model

Ветка: `codex/copper-scroll-p3-transactions`. База — пользовательский merge P2
`cbc4d44dd7928d753374503ee1eb566d528332b0` (PR №7).
Это memory/fault-store и локальные синтетические файлы. UI, SAF, реальные данные,
compact/rekey и полноценные VFS-команды не подключены.

```bash
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:lint :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --no-parallel --max-workers=2 --console=plain
bash ./gradlew :app:assembleRelease --console=plain
bash ./gradlew -Pp3ReleaseRuntime=true :app:verifyP1ReleaseApks --console=plain
bash ./gradlew -Pp3ReleaseRuntime=true :app:lintP1Release --no-daemon --no-parallel --max-workers=2 --console=plain
```

Третья команда собирает и аудирует обработанные APK, **не исполняет Android**.
R8 включён; production scope сохранён. В P3 используется существующий `p1Release`,
а storage/ICU/CBOR/BC находятся вне optimization scope. Дополнительные test ABI
rules/suffix/signing отличают его от обычного release.

В `P3CoreTest` восемь сценариев, общих с Android. Более длинные JVM-only проверки:

- `P3FaultMatrixTest`: create/add/mutate main/decoy; seed `0x50301` (`328449`),
  before/after каждого IO, byte prefixes каждого write до512B включительно;
  для больших writes/force — границы8B/4KiB/16KiB/64KiB и16 воспроизводимых prefixes;
  потеря/сохранение всех или трёх случайных volatile ranges;
- `P3FileStoreTest`: независимый сотрудничающий JVM process/lock, FD/hardlink,
  реальные FileChannel IO, create namespace до/после identity force и local-state partial/checksum tests;
- `P3RetryWindowTest`:257 commits, окно256, stale Add, закрытие/вытеснение ключей,
  поздний close, KDF revoke, incomplete-create/corrupt-readback reconciliation
  и освобождение всех ресурсов при ошибке одного close.

Печатаются только безопасные seed/счётчики/коды. Test-only `P3Fixtures` строит
нормативные зашифрованные root/settings/index/note graphs; фиксированные ключи и
предавторизованные fault proposals отсутствуют в target APK. Публичный Add отдельно
проверяется через production current-main/new-twice/inequality API. Reflection в
JVM/shared fixture author обращается к уже существующему private construction path,
чтобы не выполнять KDF тысячи раз; production обхода авторизации не добавлено.

Oracle — отдельные правила ожидаемого логического S0/S1, не второй вызов успешной
мутации как единственный источник ожиданий. Он сравнивает seq/settings/root IDs,
operation hint, неизменность чужого descriptor/catalogue/raw records. Crypto bytes
P2 по-прежнему проверяются независимыми KAT/fixtures.

[Windows / Android](android-runtime-windows.md):

```powershell
.\gradlew.bat -Pp3ReleaseRuntime=true :app:p3ReleaseRuntimeCheck --console=plain
```

Ожидаются8 завершённых PASS `P3TransactionRuntimeTest` на каждом устройстве.
Компактный результат — `app/build/reports/p3-runtime/runtime-result.txt`.
P1/P2 сохраняют прежние отдельные команды и7/8 методов. Benchmark в P3 не входит;
он запускается отдельно по [P2 README](../p2/README.md).

Модель различает file force, namespace identity и atomic local binding+journal.
Даже FileChannel subprocess test не доказывает физическую durability или SAF.
Checksum local metadata не защищает от rollback ОС; journal flags не проверяют
файлы source/candidate. Реальные provider/local-state profiles остаются пустыми.

[Подробный отчёт и части нормативных ID](../../docs/architecture/validation/p3-report.md).
Перед заданием P4 отдельно учесть согласованное расширение: карточки с типизированными
полями и шаблоны. Здесь оно не реализовано и новые перечисления типов объектов не введены.
