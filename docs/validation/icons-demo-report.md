# Демонстрация иконок

21 октября2026, Asia/Bishkek. Ветка `codex/copper-scroll-icons-comparison-demo`
создана от проверенного актуального master
`aa739e5145baac8d148bc0b5f0739dcd090705bb`, tree
`e98759cb3e3c41a72c33c3b5773016fc1e5b8fa3`. GitHub подтвердил merge PR №9
с head `ff4981f05647cbded261dff872ac0ad674c6c1df`; master содержит P4 и6 мастеров.
Перед работой дерево было чистым, AGENTS.md в репозитории/родителях не найден.
Изучены MainActivity, WorkspaceUi, CopperScrollTheme, build config и README/manifest
исходных изображений. Новая задача не относится к P5/P6.

Коммит реализации: [`478bd00eeac387ae37f2e30e177ee96e1fe5919b`](https://github.com/fantadrom/CopperScroll/commit/478bd00eeac387ae37f2e30e177ee96e1fe5919b),
tree `bb18cebdbac0514e661c113f635d1b7bb732886b`.
[Отдельный Draft PR №10](https://github.com/fantadrom/CopperScroll/pull/10)
с base master, без merge. Последующий коммит добавляет только ссылку/SHA в этот
отчёт; проверенные app code/resources не меняет.

Пользовательский P4 receipt внесён отдельно в
[p4-report.md](../architecture/validation/p4-report.md): runId
`8c35290f-917b-42dd-872e-cc301f8ff4d2`, по10 PASS на двух устройствах,
sourceRevision=ff4981f/sourceCodeDirty=false/wrapperModified=true/Gradle9.6.0.
Это не запуск агента или clean-wrapper прогон; исходные XML повторно не проверялись.
Ограничения release-derived p1Release сохраняются. PR №9 слил пользователь.

## Пользовательский выбор после просмотра на телефоне

По заданию P5a от21 октября2026: основной размер списка **60×60dp**, базовая
высота строки **72dp** с увеличением под системный масштаб текста/accessibility.
Стилистика и детализация ещё уточняются. Режим маленьких иконок — возможное
будущее расширение, сейчас не требуется. Demo activity/assets не переделаны;
рабочий UI списка в P5a не создаётся. PR №10 слит пользователем в master
`93557e1aa4e0d6d9685f33afc2ac75c8779207bd`; прежние Draft-статусы ниже исторические.

## Изменения

- `app/src/debug/java/.../demo/IconComparisonActivity.kt`: самостоятельная
  ComponentActivity и Compose list, три секции/18 строк, размеры48/54/60dp,
  min row60/66/72dp. Padding6dp vertical/12dp left/gap12dp, dots target48dp.
- `app/src/debug/AndroidManifest.xml`: exported Activity только в debug,
  без launcher intent filter/новых permissions/Intent inputs.
- Debug-only strings и6 PNG в drawable-nodpi; MainActivity/WorkspaceUi/theme,
  persisted icon IDs и storage backend не менялись.
- `.run/Icons Demo.run.xml`: Android App/specific Activity, модуль
  Copper_Scroll.app.main, обычный debug выбирается в Build Variants; без runtime
  properties. Основная launcher-конфигурация не заменена.
- `tools/icons-demo/prepare-icons.py`, `audit-apks.py`, [инструкция](../../tools/icons-demo/README.md)
  и этот отчёт; отдельно зафиксирован пользовательский P4 receipt.

Тексты статичны, дата21.10.2026. Title16sp/secondary12sp одинаковы для всех секций;
title single-line/ellipsis, secondary может переноситься при крупном fontScale.
Фиксируется только minimum height, fontScale не отключён. safeDrawingPadding,
светлая тема без dynamic colors и реальная Android density. Белый default,
голубой `screen_background=#E1EAF2`. rememberSaveable хранит только Boolean фона,
rememberLazyListState — позицию при повороте; постоянных preferences нет.
Строки объединяют текстовую accessibility семантику, Image не дублирует её;
кнопки имеют label с сюжетом/вариантом/размером, выбор фона — RadioButton semantics.
Нажатия не запускают file/editor/storage operations. «Больше не показывать» нет.

## Изображения

Все6 мастеров1254×1254 RGBA, bytes/alpha0…255/SHA256 сверены с manifest.
Мастера/manifest/prompts не изменены. Производные — полный квадрат256×256 RGBA,
PNG alpha0…255, premultiplied RGBa Lanczos→RGBA (Pillow12.3.0,zlib1.3.2).
Нет crop/отрисовки нового рисунка/автотонирования. Один ресурс для трёх размеров.
Всего430633B; в APK не декодируются мастера1254px. Скрипт не подключён к Gradle.

|Файл|Размер|Bytes|SHA256 производного PNG|
|---|---|---|---|
|demo_bank_simple.png|256×256|47533|41670bbe896f704832748aec3adf5c12db8a51c3c3595a62ef32d64762dcdbd0|
|demo_bank_detailed.png|256×256|62436|a095a7097fea8eb2e929592b30b7371ba54de38ae8118bd1b52cda6aadac79f7|
|demo_passport_simple.png|256×256|58307|d4faa8aea79c539b3425fe60731350221935c5211c74146ea52b97bb11b8ecba|
|demo_passport_detailed.png|256×256|84991|11697969f30a408a80b36d2e27a35fc51184f4395ebf64b629d311b82793996f|
|demo_papers_simple.png|256×256|82246|590aadf1a583a25c6c25afbbc0533e886878c3f971a04d6ff7dd97eff7a61e74|
|demo_papers_detailed.png|256×256|95120|c97f1c1e15032d7edff31db42bf22d23bbd254f1fd389ce5df7f9f41aa21c034|

Исходные PNG и уменьшенные композиции просмотрены на белом/голубом фоне:
прозрачные поля/цвета сохранены, чёрная подложка не добавлена. Это просмотр PNG,
не screenshot Android UI и не доказательство поведения на Honor.
При408dpi/factor2.55 контейнеры около122/138/153px, в коде этих px нет.

## Проверки

Linux/Gradle9.6.0/JVM25.0.4.1+1-LTS-5, Java target11/SDK37/min31/AGP9.4.1/
Kotlin2.2.10. Wrapper/dependencies/optimization scope/keep rules/build types
не изменены; новых permissions/applicationId нет.

```text
python3 tools/icons-demo/prepare-icons.py
python3 tools/icons-demo/prepare-icons.py --check
bash ./gradlew :app:assembleDebug :app:lintDebug --no-daemon --no-parallel --max-workers=2 --offline --console=plain
bash ./gradlew :app:testDebugUnitTest --tests com.fantadrom.copperscroll.WorkspaceStateTest --tests com.fantadrom.copperscroll.editor.NoteDocumentTest --tests com.fantadrom.copperscroll.ExampleUnitTest --offline --console=plain
bash ./gradlew :app:assembleRelease :app:assembleP1Release --no-daemon --no-parallel --max-workers=2 --offline --console=plain
```

Debug build/lint PASS1m40s, lint0 errors/13 warnings/1 hint.
23 selected unit tests PASS,0 failures/errors/skipped (4 WorkspaceState,
18 NoteDocument,1 example). Source backend и конфигурация не менялись, поэтому
тяжёлые P3/P4 matrices/KDF/device gates не повторялись; не добавлялись тесты,
дублирующие UI-константы. Release/p1Release BUILD SUCCESSFUL2m11s.
Дополнительный assembleP1Release выполнен без runtime properties для проверки
debug source-set isolation; device suites/generated instrumentation ABI не
запускались. R8 release scope сохранён.

```text
python3 tools/icons-demo/audit-apks.py --aapt2 /workspace/android-sdk/build-tools/37.0.0/aapt2
python3 docs/architecture/reconciliation-materials/validate_docs.py
git diff --check
```

APK audit PASS: debug содержит27 demo DEX definitions и6 PNG256×256 с теми же
decoded RGBA pixels/alpha в drawable-nodpi; release/p1Release содержат0 demo
definitions/PNG/strings, Activity отсутствует в обоих merged manifests.
Во всех трёх APK launcher=MainActivity и её DEX definition присутствует.
У debug Demo Activity exported=true, без launcher intent filter. `docs/reference`
и app/src/main/build configuration побайтово не менялись относительно базы.
Script prepare --check PASS, docs validation/diff PASS. Android runtime NOT_RUN.
Подпись debug APK проверена apksigner, копия для передачи имеет тот же SHA256.

|APK|Bytes|SHA256|
|---|---|---|
|debug|54662482|5509d8c0bf7f03344a2873aef791983155fb0fa077129c0533f432117ef03969|
|release unsigned|24324913|c247b6c809220e7cb88cbcb15530a683983d784a252055549e2cc5cd1830e748|
|p1Release|24333263|2f2a7cb024b4ee8bc89b7786dd9dbd2544c7f673f27c93b055f7882bc69a195f|

Это локальные артефакты working tree до коммита; пользовательская сборка создаст
собственный APK. Повторный прогон P4 не нужен для выбора иконок.

## Передача

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Обычный launcher остаётся MainActivity. Icons Demo — отдельная exported Activity
в debug; код/строки/картинки отсутствуют в release/p1Release.

Android UI smoke **NOT_RUN**: в среде нет emulator/system image/`/dev/kvm`,
`adb devices -l` завершился exit134 (read-only `/home/agent/.android`), список
devices не получен. Старт18строк/фон/прокрутка/поворот/Home/TalkBack/крупный
системный шрифт нужно проверить пользователю. Build/PNG review не заменяют smoke.

Fetch/Pull ветки → Gradle Sync → app debug → **Icons Demo** → Honor → Run.
[Windows/ADB checklist](../../tools/icons-demo/README.md), резервная команда:

```text
adb -d shell am start -W -n com.fantadrom.copperscroll/com.fantadrom.copperscroll.demo.IconComparisonActivity
```

Пользователь сообщает размер48/54/60dp, степень детализации для каждого из трёх
сюжетов и удобство высоты строки. **Окончательный размер и стиль ещё не выбраны.**
Реальные файлы/контейнеры/UI каталога/назначение icon IDs/биометрия/P5/P6 вне задачи.
Отдельный PR публикуется без merge; визуальный выбор остаётся пользователю.
