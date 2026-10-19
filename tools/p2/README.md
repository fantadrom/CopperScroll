# P2: crypto, независимые fixtures и Android

Production API: `storage.crypto` и `storage.access.CredentialManager`.
Примитивы — BC lightweight1.86 и системный JCA AES/GCM, без регистрации provider.
Fixtures содержат только синтетические секреты/ключи и поставляются **только**
в test resources/test APK. Они не пользовательские данные и не demo assets приложения.

Независимый oracle: Python cryptography50.0.0 / OpenSSL, без вызова Kotlin/BC.
Использует отдельный CBOR fixture author P1 для массива формата1.1.
Закреплённые31 поле включают Kraw/Kwrap/Kdesc/Krecord, оба AAD, envelope,
logical bytes/MAC, весь root record и chunk с другим owner/ненулевым LE ordinal.
JVM и Android читают одинаковые frozen bytes.
Для этих properties и точного BC notice задан LF в `.gitattributes`, включая Windows checkout.

```bash
python3 -m venv work/p2-reference
work/p2-reference/bin/python -m pip install -r tools/p2/requirements-reference.txt
work/p2-reference/bin/python tools/p2/fixtures.py --check
bash ./gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults --console=plain
bash ./gradlew -Pp2ReleaseRuntime=true :app:verifyP1ReleaseApks :app:lintP1Release --console=plain
```

Последняя команда **собирает и аудирует**, не выполняет Android runtime.
`verifyP1ReleaseApks` — существующее имя общего audit, расширенного на crypto/BC.
Оба APK, generated ABI rules и обе R8 mapping identities согласуются тем же путём P1.
P2 использует тот же non-debuggable `p1Release` target с `.p1runtime`, test signing
и теми же дополнительными instrumentation rules. Storage/ICU/CBOR/**BC** не входят
в optimization scope; обычный release дополнительных test ABI rules не получает.

Точная [Windows-инструкция](android-runtime-windows.md) использует одну команду:

```powershell
.\gradlew.bat -Pp2ReleaseRuntime=true :app:p2ReleaseRuntimeCheck --console=plain
```

Ожидается восемь методов `P2CryptoRuntimeTest` на **каждом** выбранном устройстве.
Результаты в `app/build/reports/p2-runtime/runtime-result.txt`:
`runtime=PASS`, `variant=p1Release`, имя suite, новый runId, bundle/mapping/APK identities,
`tests=8 failures=0 errors=0 skipped=0` для каждого устройства.
Отсутствие/ноль/неполный набор/старые отчёты/runner crash — ошибка команды.
Проверяются XML и protobuf completed statuses/exit code; parent/child suite не суммируются.

P1 regression запускается отдельно прежней командой, без P2-флага:

```powershell
.\gradlew.bat -Pp1ReleaseRuntime=true :app:p1ReleaseRuntimeCheck --console=plain
```

Её критерий остаётся семь методов `P1BackendRuntimeTest`; восемь P2 их не заменяют.
Default debug instrumentation также содержит оба correctness suite.

Benchmark **никогда не часть correctness-команд**. Явный PowerShell-запуск всех
9 whitelist-профилей ×1/2 envelopes на одном выбранном устройстве:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\p2\run-kdf-benchmarks.ps1 -Serial DEVICE_SERIAL -Repeats 20
```

Это18 новых instrumentation processes: на каждую точку один process-cold Argon2
и20 warm samples, синтетические теги специально неверны. Измеряется login/derivation
одного/двух envelopes, включая HKDF/GCM, без setup-KDF перед cold sample.
Первый cold sample на профиль — наблюдение, не статистический cold p50/p95.
Для формальных20 cold повторов на профиль добавить `-ColdRepeats 20` к команде
скрипта:360 новых processes, плюс20 warm samples на профиль в первом процессе.
Это длительный явный benchmark для слабого/среднего телефона. Одну выбранную точку
можно запускать отдельно готовой командой:

```powershell
.\gradlew.bat -Pp2KdfBenchmark=true -Pp2BenchmarkMemoryKiB=65536 -Pp2BenchmarkIterations=3 -Pp2BenchmarkSlots=2 -Pp2BenchmarkRepeats=20 :app:p2KdfBenchmarkCheck --console=plain
```

Выбор иных whitelist m/t/slots выполняется параметрами, без редактирования исходников.
TSV/logcat содержат elapsed ns, warm p50/p95, PSS до/после, Java heap, process VmHWM
(либо unavailable), API/model/ABI, thermal/power/low-memory условия и число повторов.
VmHWM — process-wide high-water, **не** per-call peak Argon2 allocation; PSS — не RSS.
ResourceLimit фиксируется и прекращает точку без понижения профиля.
Benchmark test PASS означает завершённое измерение, не пригодность профиля и не crypto PASS.
Скрипт сохраняет результаты каждой точки вне build в `work/p2-benchmarks/…`.
Эмулятор измеряет совместимость; его скорость не выбирает профиль телефона.

Нормативный wrapper9.6.0, SDK37/min31, AGP9.4.1, Kotlin2.2.10 сохранены.
Пользовательские P1-прогоны Windows/Gradle9.8.0 зафиксированы отдельно в p1-report.

Контрольные источники:

- [RFC9106§5.3](https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3), Argon2id19
  (малый RFC KAT32KiB/p4 — только внутренний test path, production whitelist не расширен).
- [RFC5869 Appendix A.1](https://www.rfc-editor.org/rfc/rfc5869.html#appendix-A.1), HKDF-SHA256.
- [RFC4231§4.2](https://www.rfc-editor.org/rfc/rfc4231.html#section-4.2), HMAC-SHA256.
- [NIST GCM examples](https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Standards-and-Guidelines/documents/examples/AES_GCM.pdf), GCM-AES256 Examples1/2, tag128.
- [BC1.86 Maven POM](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk15to18/1.86/bcprov-jdk15to18-1.86.pom),
  [BC advisories](https://github.com/bcgit/bc-java/wiki), [release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/).

Фактические проверки, владение памятью, части KEY/CRYPTO и открытые gates:
[p2-report.md](../../docs/architecture/validation/p2-report.md).
