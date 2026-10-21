# Icons Demo — Android Studio / Honor

1. Git → Fetch, checkout `codex/copper-scroll-icons-comparison-demo`, Pull,
   затем Gradle Sync. В **Build Variants** выбрать **app → debug**.
2. Подключить Honor ABR-LX1 / Android15, подтвердить USB debugging/RSA.
   В Run configurations выбрать **Icons Demo**, в устройствах — Honor, нажать Run.
   Runtime properties P1/P2/P3/P4 не нужны. Обычная конфигурация app/MainActivity
   сохраняется; демо — отдельная debug-only Activity без входа в контейнер.
3. Сразу появятся три секции, по6 строк:48/54/60dp. Сравнить обе детализации
   каждого сюжета на белом и светло-голубом фоне. Точки справа показывают только
   «Демонстрационный элемент».
4. Прокрутить все18 строк, повернуть телефон (в том числе на середине списка),
   проверить сохранение фона/позиции. Нажать Home и вернуться. Проверить
   портрет/альбом, крупный системный шрифт, обрезку длинного названия многоточием
   и доступность TalkBack. Строки при крупном шрифте могут стать выше минимума.

Debug APK после сборки: `app/build/outputs/apk/debug/app-debug.apk`.
ApplicationId: `com.fantadrom.copperscroll`, Activity:
`com.fantadrom.copperscroll.demo.IconComparisonActivity`.

Резервный запуск уже установленного debug APK через ADB:

```text
adb -d shell am start -W -n com.fantadrom.copperscroll/com.fantadrom.copperscroll.demo.IconComparisonActivity
```

`-d` выбирает единственное USB-устройство, даже если включён эмулятор.
Использовать adb из SDK/platform-tools, выбранного в Android Studio.
Если adb не добавлен в PATH, для стандартного SDK location Windows команда:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -d shell am start -W -n com.fantadrom.copperscroll/com.fantadrom.copperscroll.demo.IconComparisonActivity
```

При необходимости собрать/установить APK из корня проекта:

```powershell
.\gradlew.bat :app:installDebug --console=plain
```

После просмотра сообщить:

- Предпочтительный размер:48,54 или60dp.
- Для карты, паспорта и журнала/писем — упрощённый или подробный вариант.
- Удобна ли высота строки/прокрутка; есть ли проблемы с текстом, краями или фоном.

При density408 (factor2.55) стороны контейнеров около122/138/153px; это не
высота силуэта. Размеры задаются dp по реальной Android density, прозрачные поля
всех файлов сохранены. Окончательные размеры/стиль пока **не выбраны**.

## Производные изображения

Готовые6 PNG256×256 RGBA находятся только в debug drawable-nodpi; Gradle не
запускает Python. Мастера и manifest в docs/reference/icons-demo неизменны.
Для осознанного воспроизведения нужен Python3 с Pillow (проверено12.3.0):

```text
python tools/icons-demo/prepare-icons.py
python tools/icons-demo/prepare-icons.py --check
```

После сборки debug/release/p1Release доступен read-only APK audit (Pillow+aapt2):

```text
python tools/icons-demo/audit-apks.py --aapt2 <SDK>/build-tools/37.0.0/aapt2
```

На Windows указать путь к aapt2.exe. Проверяются реальные DEX definitions,
launcher/merged manifests/resource table и pixels PNG в APK. Это не UI smoke.

Только явные6 файлов; input bytes/SHA256/dimensions/mode/alpha сверяются с manifest.
Lanczos в premultiplied RGBa, затем RGBA; сохраняется полный квадрат, без crop
alpha bounds/изменения композиции. `--check` сравнивает decoded pixels, размеры и
alpha без записи (PNG compression может различаться между версиями zlib).
Полные цвета выводятся через Image/ContentScale.Fit без tint, один файл на три
размера; image loading library не добавлена.

[Отчёт и границы проверки](../../docs/validation/icons-demo-report.md).
