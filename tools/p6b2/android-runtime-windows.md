# P6b.2 — Android Studio / Windows

Ветка `codex/copper-scroll-p6b2-data-leak-boundaries`, отдельный Draft PR от master
`30fd27d962b7ed9192717ec2c60e59d91bb5ccd7`. Checkout/pull, Gradle Sync, открыть Terminal
PowerShell в корне CopperScroll. Wrapper9.6.0/checksum не менять. Подключить
разблокированный Honor ABR-LX1 Android15 и Virtual_Pixel_6 Android17 с USB debugging.
Для automatic suite оба online; `ANDROID_SERIAL` должен отсутствовать, если нужны оба:

```powershell
Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2ReleaseRuntimeCheck --no-configuration-cache --console=plain
```

Можно выбрать готовую Android Studio Run configuration **P6b2 Release Runtime**.
Исходники/Gradle/keep rules вручную не редактировать. Использовать только synthetic
данные; suite записывает свой тестовый clipboard, не читает его историю. Не вводить
свои пароли/заметки и не нажимать кнопки host во время automatic suite. Основная
клавиатура/доступность/autofill services не меняются, PIN не отключается.

Устанавливаются `app/build/outputs/apk/p1Release/app-p1Release.apk` и
`app/build/outputs/apk/androidTest/p1Release/app-p1Release-androidTest.apk`.
Target `com.fantadrom.copperscroll.p1runtime`, nondebuggable, локальная debug-подпись,
production backend только в target. R8 включён с прежним scope androidx/kotlin/kotlinx;
storage/ICU/crypto и application UI вне optimization. Отличия от ordinary release:
suffix/подпись/opt-in harness/инструментационный ABI. Startup/lifecycle сохранены.

Ожидается **ровно12 completed PASS на каждом устройстве**, failures/errors/skipped=0,
`P6b2PrivacyRuntimeTest`, exact methods:

1. `sessionPoliciesFollowAuthenticatedZones`
2. `staleConnectionCannotReadOrMutateNextSession`
3. `editorInfoAndCredentialQueryBoundary`
4. `blockedCopyCutAndIndependentPaste`
5. `sensitiveCopyDoesNotEnableOtherExports`
6. `richTextUtf16AndStaleCallbacks`
7. `emptyAssistAutofillCaptureStructures`
8. `hiddenPasswordAccessibilityAndStaleActions`
9. `windowPolicyRejectsStaleAllowCallbacks`
10. `savedStateAndCanaryAudit`
11. `recreationRetiresNativeConnection`
12. `maintenanceHidesBeforeServiceWork`

`BUILD SUCCESSFUL` без exact suite receipt недостаточно. Gate отвергает zero,
partial/duplicates/skipped/crash/stale/foreign/XML–protobuf discrepancy/runner exit
и source/APK/mapping mismatch. Compact: `app/build/reports/p6b2-runtime/runtime-result.txt`;
HTML: `app/build/reports/androidTests/connected/p1Release/`;
ошибка: `app/build/reports/p6b2-runtime/p1-diagnostics.zip`.
Build failure до runtime: последний UUID в `app/build/reports/p6b2-build-attempts/`.
Оба mappings и их SHA256 связаны с APK. Не применять mapping другого APK.

## Guided privacy observations (отдельный результат)

```powershell
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2PrivacyProbe --no-configuration-cache --console=plain
```

Устройства по очереди,12мин/device. В synthetic host прокрутить до observation controls;
верхний native editor и login доступны прокруткой. После каждого revoke вручную
**Explicit synthetic login** либо **Synthetic Main**, когда owner освобождён.
Для каждой из пяти строк выбрать **OBSERVED_OK**, **FAIL**, **NOT_RUN** или
**NOT_CONFIRMED**, затем **Finish privacy observations**. Не подтверждать непроверенное.
Отсутствующее действие записывается NOT_RUN, любой FAIL сохраняется и валит gate.

- `active_ime`: коснуться editor, ввести synthetic текст, проверить нормальный ввод,
  composing/selection; Home и возврат требуют нового явного входа. Обычная IME
  получает открытый ввод; no-learning не доказывает удаления истории. Не исследовать
  чужие IME buffers. При недоступном IME/режиме — NOT_RUN.
- `clipboard_preview`: blocked copy/cut должны отказать без удаления текста; paste
  разрешён отдельно. **Explicit synthetic sensitive copy** сохраняет copy permission
  в зашифрованных RAM-fixture settings и копирует только текущий synthetic display.
  На API33+ проверить скрытый preview/нейтральный label. На API31–32 preview может
  отсутствовать — записать это ограничение. Не чистить чужой clipboard.
- `accessibility`: если TalkBack уже используется, проверить кнопки/навигацию и
  открытый note, после revoke отсутствие прежнего текста/действий. Этот probe не
  включает/не устанавливает service. Если TalkBack не используется — NOT_RUN;
  отдельное явное включение пользователем допускается вне automatic команды,
  выключить/вернуть прежнее состояние через Settings → Accessibility в конце.
  Полный Card/password UI ещё отсутствует: его не обозначать проверенным.
- `recents_screenshot`: до входа, в default main/decoy и после Home/revoke сделать
  обычную попытку screenshot и посмотреть Recents. Не должно быть sensitive кадра.
  Actual screenshot/recents визуально наблюдаются отдельно от проверки FLAG_SECURE.
  В стенде нет кнопки разрешения снимков: allow policy проверяет automatic suite;
  full future UI/OEM policy остаётся отдельной ветвью.
- `autofill_capture`: если уже есть пользовательский autofill/assistant, проверить
  synthetic поле без предложений сохранения/выдачи скрытого текста, после revoke —
  пусто. Не устанавливать service ради probe; отсутствие инструмента — NOT_RUN.
  Empty ViewStructure automatic check не доказывает поведение всех services/OEM.

Receipt: `app/build/reports/p6b2-privacy/<runId>/device-N/runtime-result.txt` и
`P6b2PrivacyProbe-helper-result.txt`; ZIP рядом с device-N: `diagnostics.zip`.
USER_OBSERVATIONS_COLLECTED не означает полную privacy certification.

## Основной UI-process death (внешний controller)

```powershell
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2ProcessProbe --no-configuration-cache --console=plain
```

Ничего нажимать не нужно. Controller ждёт PRIMARY_PREPARED с реальным PID/session,
двумя независимыми состояниями: **Open с synthetic текстом**, затем **Locked с pending
picker**. Для каждого сохраняет отдельный receipt и делает
`am force-stop` **только test target**, проверяет исчезновение PID, запускает fresh
instrumentation. Fresh PID/session и Locked/no capability/picker/plaintext обязательны.
Две подготовительные instrumentation намеренно прерваны: Process crashed в её отдельном
журнале допустим только при подтверждённой цепочке controller. Это не completed PASS
и не power-loss. Итог **OBSERVED_PRIMARY_DEATH_FRESH_LOCKED** для каждого устройства.
Receipt/logs/ZIP: `app/build/reports/p6b2-process/<runId>/`; у каждого device-N
отдельные `open/` и `picker/`, обе цепочки обязательны.

## Home/screen-off в управляемом handoff

```powershell
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2HandoffProbe --no-configuration-cache --console=plain
```

Controller ждёт HANDOFF_WAITING после реального beginConfiguration, до recreate;
main looper свободен. Он отправляет настоящий Home, затем отдельным прогоном настоящий
KEYCODE_SLEEP, ждёт реального системного observation, выполняется Activity recreation,
новое окно возвращается Locked. После screen-off команда будит экран; если Honor
просит PIN — разблокировать **вручную сразу после сообщения Terminal**. PIN не вводится
автоматически, настройки блокировки/анимаций не меняются. Не нажимать Home/Power
самостоятельно посреди этого controller. Поля home/screenoff требуют
OBSERVED_ACTUAL_EVENT_AND_RECREATION_LOCKED; любой неизвестный исход — FAIL с evidence.

Граница намеренно узкая: между beginHandoff и вызовом recreate, а не доказанный
момент физического rotation/resize внутри OEM relaunch. Та последняя ветвь остаётся
NOT_CONFIRMED. Receipt/checkpoints/ZIP: `app/build/reports/p6b2-handoff/<runId>/`.

## Обычный release (отдельный black-box путь)

```powershell
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2ReleaseProbe --no-configuration-cache --console=plain
```

Собирает настоящий `app-release-unsigned.apk`, отдельный static ordinary-release
DEX/manifest/backup/mapping audit. Подписывает **копию** текущим локальным Android debug
ключом, проверяет неизменность всех code/resources ZIP entries, сохраняет certificate
SHA256 в certificate.txt. Штатная release signingConfig не меняется. Устанавливает
package **com.fantadrom.copperscroll** через install -r, без uninstall/clear/run-as,
запускает только публичную MainActivity. В APK нет harness/test Activity/service/API.
Внешний observer работает в отдельном opt-in p1runtime process и использует только
public launcher/UiAutomation обычного приложения; ordinary release не является
instrumentation target, его ABI/код не открываются. Поэтому дополнительно
устанавливаются и opt-in target/test APK; их mappings связаны отдельно.

Использовать только demo без своих данных и разблокированный экран. UI automation
нажимает «Создать» → «Новая заметка», вводит ASCII synthetic marker в настоящий
native editor, делает Home/return, проверяет secure window. Если поле недоступно
UiAutomation из-за sensitive accessibility — nativeEditor=NOT_CONFIRMED_SENSITIVE_NODE_NOT_EXPOSED; это не PASS
проверки ввода. Signature mismatch даёт FAIL и ничего не удаляет: сохранить диагностику;
не удалять приложение с нужными данными. Локальная подпись отличается от будущей
release-подписи; code/resources — обычный release, не p1Release.

Внешний observer выполняет ровно один метод
`P6b2OrdinaryReleaseProbe.publicLauncherEditorAndHomeReturn`; это отдельное evidence,
не дополнительный метод automatic suite12.

Итог OBSERVED_ORDINARY_RELEASE_LAUNCH_SECURE_WINDOWS_HOME_RETURN и независимый
nativeEditor статус. Screenshot/recents изображения требуют guided observation;
authenticated Main/Decoy/Card/media/Closing в ordinary release ещё отсутствуют.
Receipt, unsigned/installed APK hashes, ordinary mapping, certificate, ZIP:
`app/build/reports/p6b2-release/<runId>/`. Ключ в архив не включается.

## Backup/restore/transfer — только отдельный эксперимент

```powershell
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2BackupProbe --no-configuration-cache --console=plain
```

На Honor всегда NOT_RUN_PERSONAL_DEVICE: никаких backup requests/cloud/settings
changes/reset/uninstall. На эмуляторе эксперимент выполняется только если **уже**
выбран и включён `com.android.localtransport`; иначе явный NOT_RUN. Команда не включает
и не переключает transport. Для более полного опыта нужен отдельный расходный AVD,
где пользователь заранее явно настроит local transport по официальной Android
инструкции; личные аккаунты не добавлять. Не использовать Google cloud transport.

При доступном local transport: seed ровно трёх synthetic canaries в files/noBackup/
prefs, системный backupnow только test package, сохранить response; при существующем
local restore token — restore request только test package, без очистки данных.
finally удаляет только эти собственные canaries/preferences. Изменений IME/services/
settings нет, поэтому восстанавливать их команда не должна. Если cleanup отказал,
прислать diagnostics, не запускать automatic suite до устранения этой ошибки;
нельзя стирать всё app data в качестве незаметного исправления.

Ledger раздельно: staticExclusions, systemBackupRequest, transportContents,
restoreAttempt, oemD2D, crossPlatformIos. Недоступный privileged transport read,
расходный OEM device/initial setup или matching iOS app — NOT_RUN, остаются P0.
Отказ bmgr не доказывает невозможность OEM transfer. Никакого factory reset личного
Honor. Receipt/request-response/ZIP: `app/build/reports/p6b2-backup/<runId>/`.

## Обязательные регрессии / API31

На финальном коде нужны P6b.1=8/device и P6a=13/device. Общая runtime packaging/ABI
и backup-agent startup затронуты, поэтому P1/P2/P3/P4/P5a/P5b=7/8/8/10/8/8 повторить
на этой новой APK-паре; принятые старые140 PASS не отменены. Готовый последовательный
PowerShell блок (остановится при первом отказе):

```powershell
$checks = @(
  @('p6b1ReleaseRuntime','p6b1ReleaseRuntimeCheck'),
  @('p6aReleaseRuntime','p6aReleaseRuntimeCheck'),
  @('p1ReleaseRuntime','p1ReleaseRuntimeCheck'),
  @('p2ReleaseRuntime','p2ReleaseRuntimeCheck'),
  @('p3ReleaseRuntime','p3ReleaseRuntimeCheck'),
  @('p4ReleaseRuntime','p4ReleaseRuntimeCheck'),
  @('p5aReleaseRuntime','p5aReleaseRuntimeCheck'),
  @('p5bReleaseRuntime','p5bReleaseRuntimeCheck')
)
foreach ($check in $checks) {
  & .\gradlew.bat "-P$($check[0])=true" ":app:$($check[1])" --no-configuration-cache --console=plain
  if ($LASTEXITCODE -ne 0) { throw "Regression failed: $($check[1])" }
}
```

Сохранить runtime-result.txt/diagnostic ZIP каждого профиля перед следующим обновлением
кода. KDF benchmark и P6a.2 reboot автоматически не нужны. При доступном отдельном
API31 AVD запустить ту же exact12 automatic команду с ним как единственным online
устройством; отсутствие API31 runner записать NOT_RUN (minSdk compile не runtime).

При ошибке прислать compact receipt, diagnostics.zip/p1-diagnostics.zip либо последний
build-diagnostics.zip, Terminal output и модель/API/какое действие выполнялось. Не
прикладывать реальные clipboard/IME history, чужие скриншоты, URI и пользовательские
секреты. Предыдущие пользовательские140 PASS и guided runs сохранены отдельно в
p6b1-report.md; новые результаты агентом пока не выполнены.
