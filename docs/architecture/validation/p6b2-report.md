# P6b.2 — Android output boundaries and remaining lifecycle evidence

Обновление24 октября2026: исправление long-press и safe viewport в том же Draft
PR№16. Актуальный обязательный набор — **14 методов на устройство**. Новые Android
прогоны агента NOT_RUN; **предыдущий отказ handoff на Honor остаётся OPEN**.
Диагностика, причина, изменения и результаты повторных проверок приведены в
[обновлении ниже](#исправление-долгого-нажатия-и-отступов--24-октября-2026).
Ниже сохранена история исходной реализации23 октября, её результаты не подменяют
проверку исправления на устройствах.

23 октября2026. Ветка `codex/copper-scroll-p6b2-data-leak-boundaries`, база актуального
origin/master **30fd27d962b7ed9192717ec2c60e59d91bb5ccd7** (merge PR15). Fetch/API
подтвердили merge и ancestry принятого implementation head
758ba918b10f94d45763c50b6c35e51f78ec699c. Начальное дерево чистое, AGENTS.md в repo/
родителях/дочерних исходниках не обнаружен. Применены design§6–7, format1.1/1.2,
review-resolution, P4 extension, prototype/test plan, ADR001, P6a/P6a.2/P6b.1 reports,
реальные session/worker/owner/card/settings/editor/legacy UI и source/artifact gates.

**Реализация и host/build evidence не означают приёмку всего P6b. Новые Android
runtime/probes агента NOT_RUN. Production storage admission REFUSED, allowlist пуста,
ExistingWriteDurable/DurableCreateIdentity/DurableLocalState UNKNOWN.** ADR001 остаётся
предложением; источник истины не изменён, app-private canonical container не вводился.
До P6c требуется отдельный выбор выполнимого storage-пути. PR отдельный Draft, без merge.

[Windows/Android Studio инструкция](../../../tools/p6b2/android-runtime-windows.md),
[tooling](../../../tools/p6b2/README.md), [принятый P6b.1](p6b1-report.md#пользовательская-приёмка-p6b1--23-октября-2026).
Пользовательская приёмка P6b.1 записана отдельно:140 completed PASS на двух устройствах
(16 P6b.1 +124 регрессии), два guided runs; source758ba91, sourceCodeDirty=false,
wrapperModified/sourceDirty/workingTreeDirty=true, Gradle9.6.0/JetBrains25.0.3.
Архивы успешных прогонов в этом поручении не переданы: агент использовал предоставленные
в ТЗ точные receipts/provenance, не утверждает их самостоятельного открытия. Старые
отказы21f0b57, исправления758ba91 и локальные проверки сохранены исторически.

## Готовые production-компоненты

`session/ui/UiAccess` — UI authority поверх текущего SessionCoordinator, без второго
session engine/IO/ключей/контента. Worker-delivered SettingsData из существующего
зашифрованного codec; bool defaults screenshot/copy=true, неизвестные допустимые
sort/view enums не изменяют их. Схема/wire/preferences не меняются. Привязка к
конкретным capability и window; maintenance/revoke/handoff/старое окно отказывают
при обращении. Retire окончательный; старое allow не может стать authority новой зоны.

`session/android/ProtectedEditText` — общий native boundary:

- no-personalized-learning, no-extract/no-fullscreen, пустой initial surrounding text, no-suggestions для системного spellchecker,
  без MIME content/inline external-content contract; обычный note остаётся multiline
  text, credential ввод — password type. IME получает открытый пользовательский ввод;
  flags — requests, не удаление её копий/истории;
- настоящий framework InputConnection обёрнут по runtime interface (API31–37):
  generation+lease+attached+актуальный coordinator проверяются перед любым обращением.
  Stale read/edit/composing/delete/selection/paste отказаны, новый текст следующей
  сессии не доступен. Snapshot/мониторинг extracted text/cursor/spell-check и внешние
  content/private/handwriting пути не включены. Async bounds/handwriting возвращают
  безопасный отказ без данных. `sendKeyEvent` не оставляет отложенную отправку в
  другую сессию. Прямой тест connection не означает проверку всех active IME;
- стандартный copy/cut/context/keyboard callback проверяется в точке действия.
  При отказе cut не удаляет текст; paste отдельно разрешён в активном editable.
  Разрешённый copy помечает ClipData sensitive через совместимый API31–32 literal,
  label нейтральный. Copy не разрешает share/process-text/search/translate/drag;
- по AOSP Editor PROCESS_TEXT может обойти customSelection callback до него. Поэтому
  native selection ActionMode и контекстное меню получают ограниченные callbacks
  вместо исходного intent-dispatch. TextClassifier.NO_OP, нет implicit export menu.
  Собственный long-press выделяет слово с UTF‑16 границами и безопасным меню;
  mouse MOVE drag отдельно отменён. Ни Intent/URI grant для такого экспорта не выдаётся;
- реальные assist/autofill/content-capture/virtual ViewStructure callbacks возвращают
  пустой текст и0 детей, дополнительно NO_EXCLUDE_DESCENDANTS; поздний autofill() отвергается, receive-content
  допускается только в пределах явного current-generation clipboard paste. Нет текста в labels/
  hints/errors/saved state; статические навигационные подписи допустимы;
- hidden-password node получает только фиксированное conceal, не raw/длину/selection.
  После revoke пусто. Accessibility editing/copy/cut/paste/unknown process-text actions
  отказаны, чтение обычного открытого note и focus/navigation остаются доступны.
  Accessibility events имеют bounded weak generation stamp: current note speech
  разрешена, скрытый пароль/revoked/queued old-generation events очищаются в dispatch.
  Rich todo использует отдельный generation-bound custom action ID: старый node
  не редактирует перепривязанный editor, fresh node сохраняет TalkBack todo action.
  Sensitive flag API34+ — дополнительный platform contract, не оценка доверия к service;
- invalidation на UI-потоке сбрасывает composing/focus/текст/menus и generation,
  не ждёт IO и не закрывает owner/FD. SavedState snapshot не создаётся даже при прямом
  вызове: точечное MissingSuperCall suppression объяснено отказом вызывать TextView
  plaintext snapshot, saving также disabled. Глобального lint suppression нет.

`SessionWindowPolicy` — weak окна Activity/Dialog/Popup, FLAG_SECURE при регистрации
до кадра, current screenshot policy и API33+ Recents control. Queued refresh читает
актуальную authority внутри UI runnable. `SecureSessionPresentation` принимает
worker-authenticated settings, проверяет epoch/window перед публикацией, при revoke
возвращает secure и чистит собственные sensitive dialogs. MainActivity baseline
secure/recents применяется до первого кадра. Общий port готов; P6c должен регистрировать
каждое новое sensitive окно и использовать актуальный settings result.

`RichNoteEditText` теперь использует этот boundary; paragraphs/todo/styles/selection/
scroll/UTF‑16 модель сохранена, old detached callbacks не редактируют NoteSession.
Нативный folder input также использует его вместо plaintext Compose TextField; нет
параллельной Compose semantics с текстом поля. Legacy demo получает явно ограниченную
RAM demo authority с blocked copy/screenshots; **не authenticated container UI**.
NoteSaveHook остаётся no-IO Unit-hook, не durable ACK. Работающий Card/media editor
не добавлен. CardDraftSession по-прежнему единственный источник reveal/display/copy;
P6c передаёт View только display, hidden field — conceal, reveal только явно и reset
на закрытии/revoke. Служебные decoy/old/new keys не экспортируются во View.

`backup/RefuseBackupAgent` — defense in depth: no data on backup/full backup и отказ
restore без default traversal/записи destination. Сохраняются allowBackup=false,
compiled cloud/device-transfer и legacy exclusions девяти разрешённых domains.
Новый FullRestoreDataInput callback API37 отдельно отвергнут, старый API31 callback
также; существующий Startup/lifecycle не отключён. В iOS registration не добавлены
несуществующие bundle/team IDs. OEM bypass и реальный cross-platform путь не доказаны
невозможными; это открытые P0, а не обещание защиты flags на всех устройствах.

## Контракты Android, применимость и границы

Проверено23.10.2026 по официальным API и SDK37/AOSP; зависимости/toolchain не обновлены:

| Контракт | API31–37 применение | Ограничение |
| --- | --- | --- |
| [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection) / [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo) | generation wrapper, API31 surrounding, API33 snapshot denial, API34 async denial; no-learning сAPI26 | active IME видит ввод; monitoring/cache/внешние gestures не разрешены; сторонние/OEM клавиатуры ещё guided |
| [Secure clipboard](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling) / [copy-paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) | sensitive literal API31–32, совместим с API33+ preview | Clipboard не зашифрован; уже разрешённую копию приложение не отзывает атомарно |
| [Autofill](https://developer.android.com/identity/autofill/autofill-optimize) / [View](https://developer.android.com/reference/android/view/View) | actual empty structures + flags; sensitive accessibility guarded34+ | API31–33 очистка/маскирование обязательны; реальный service/OEM ещё не покрыт полностью |
| [FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) / [Activity](https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)) | before frame; Recents toggle guarded33+ | поведение конкретного capture/Recents требует device observation |
| [Auto Backup](https://developer.android.com/identity/data/autobackup) / [BackupAgent](https://developer.android.com/reference/android/app/backup/BackupAgent) | allowBackup false, девять domains, empty agent; cross-platform с36.1 и новый restore callback в SDK37 | отсутствующий раздел transfer не означает universal deny; XML iOS требует реальных platform params, их нет; OEM gate открыт |
| [AOSP Editor](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/Editor.java) | callback order/ProcessText before custom callback; отдельный mouse drag path | код изучен через официальный mirror; OEM modifications не сертифицированы |

Защита не распространяется на скомпрометированную ОС/privileged software, прошлый
разрешённый экспорт/clipboard/IME, произвольное физическое уничтожение JVM String/
IME/crypto-engine копий. Нет обещания обнаружить любые утечки по отсутствию canary.
Root/SAF файл/чужие snapshots не управляются Android app backup rules.

## Automatic suite и независимые виды evidence

Один `-Pp6b2ReleaseRuntime=true`, смешанные flags отвергаются. Переиспользованы P1/P4/
P6a инфраструктура, target-only backend, частичная R8 optimization scope и generated
ABI. Nondebuggable p1Release с test suffix/signature/harness/ABI не равен обычному
release; storage/ICU/crypto/application UI вне scope. Никаких blanket keep/R8 off.
Оба mappings сохраняются с APK SHA256/marker IDs. Test controls отсутствуют в ordinary
release по DEX/manifest/isolation audit. Обычный release дополнительно имеет отдельный
mapping-marker/manifest/exclusions/production class audit без runtime ABI.

Актуальные exact14 `P6b2PrivacyRuntimeTest` совпадают с validator/DEX audit/Windows инструкцией:

| Метод | Основной реальный boundary |
| --- | --- |
| sessionPoliciesFollowAuthenticatedZones | сохранение Settings через actual T, отдельные main/decoy defaults |
| staleConnectionCannotReadOrMutateNextSession | реальный InputConnection → revoke → новый decoy epoch, чтение/изменения старым объектом |
| editorInfoAndCredentialQueryBoundary | actual EditorInfo, no-learning/extract/fullscreen, credential query/async отказ |
| blockedCopyCutAndIndependentPaste | native context/shortcut; cut не меняет, paste работает |
| sensitiveCopyDoesNotEnableOtherExports | sensitive ClipData/нейтральный label; share/assist/accessibility/long-press |
| richTextUtf16AndStaleCallbacks | настоящий RichNoteEditText, surrogate pair/styles/todo/selection и revoked callbacks, stale/fresh accessibility todo action IDs |
| emptyAssistAutofillCaptureStructures | пять actual ViewStructure callbacks, включая virtual/capture |
| hiddenPasswordAccessibilityAndStaleActions | actual node/event и скрытый пароль, stale mutation/export отказ |
| windowPolicyRejectsStaleAllowCallbacks | реальный Window FLAG_SECURE, разрешённая зона → revoke → старое allow |
| savedStateAndCanaryAudit | actual saveHierarchyState, files/cache/noBackup/prefs и свой process log; negative control |
| recreationRetiresNativeConnection | actual Activity recreation, новый window, старый connection/empty old editor |
| maintenanceHidesBeforeServiceWork | actual coordinator Maintenance admission немедленно скрывает/отказывает native input |
| longPressAfterReplacementKeepsUtf16AndSessionBoundary | реальный injected touchscreen long-press после replace/setText, конец/empty/multiline/emoji, reentrant selection mutation и bind/revoke, разрешённый sensitive copy и отказ экспорта |
| harnessInsetsRemainOutsideScrollAcrossOrientations | реальные portrait/landscape layouts общего host, privacy controls, собственного dialog и picker; safe root до ScrollView, прокрутка к обоим краям |

Gate: fresh per-device exact XML + completed protobuf + runner exit0, без zero/
partial/duplicates/skipped/crash/stale/foreign/results/source/APK mismatches.
Host fixtures проверяют вложенные testsuites без двойного подсчёта и негативные
условия; это не Android acceptance. Исходный source gate обязательный, не обходился.

Остальные результаты **не складываются с14 correctness tests**:

- privacy: пять отдельных user statuses,12мин/device, отсутствующее NOT_RUN, FAIL
  сохраняется до gate; нет автоматической установки/смены IME/services/settings;
- primary-process: внешний host переживает force-stop, сохраняет PRIMARY_PREPARED,
  проверяет две независимые смерти из Open с canary и Locked с pending picker;
  исчезновение PID, fresh PID/session/Locked/no capability/picker/plaintext обязательны
  в обеих цепочках, отдельные open/picker receipts.
  Первая instrumentation намеренно прервана и не является completed PASS. Нельзя
  предполагать finally после kill или называть это power-loss;
- handoff: наблюдаемый beginConfiguration checkpoint, настоящее system Home/KEYCODE_SLEEP,
  receiver/lifecycle observation, затем реальный recreate/new foreground Locked.
  Main looper свободен, нет sleep-to-hit/animation/timeouts-for-PASS. Это **узкая
  граница до вызова recreate**, физический rotation/resize внутри OEM relaunch остаётся
  NOT_CONFIRMED. Никаких adapter.onPause/hardRevoke вместо системного события;
- ordinary release: подпись копии фактического unsigned release текущим local debug
  key, code/resources побайтно неизменны. Certificate digest/APK hash/release mapping
  сохраняются. Публичный launcher, real RAM demo editor, secure windows и Home return;
  без instrumentation ABI/hooks/debuggable/run-as в ordinary APK. Observer имеет
  свой exact1 `P6b2OrdinaryReleaseProbe.publicLauncherEditorAndHomeReturn` во внешнем
  p1runtime process, только public UI; host дополнительно проверяет Window secure. Signature mismatch — FAIL, без
  uninstall/clear. Нет engine-authenticated editor и P6c ACK/integration claim;
- backup: отдельный явный probe. Honor — NOT_RUN без запроса/изменений. Только AVD
  с уже выбранным enabled local transport: свои synthetic canaries, system request/
  response и возможный local restore request, finally cleanup ровно собственных paths.
  Нет cloud/transport switch/reset/root/uninstall. Transport contents, restore effects,
  OEM D2D и iOS — раздельные статусы; bmgr refusal не закрывает их.

Receipts имеют UUID/device/API/build, source/fingerprint/codeDirty/wrapper/Gradle/JVM,
variant/signing/scope, APK/mapping hashes, checkpoint и observation method. Исходные
ответы instrumentation сохраняются до strict gate, ошибочные raw runner outputs
не превращаются в PASS. ZIP — средство диагностики, не acceptance. Full clipboard,
IME history, чужие UI dumps/скриншоты в ZIP не собираются. Внешний UiAutomation observer ordinary release работает в отдельном p1runtime
process, фильтрует nodes по ordinary package и не делает raw hierarchy dump/temporary
XML. Ordinary release не является instrumentation target, его backend/ABI не вызываются.

## Матрица нормативных IDs и каналов

PASS model/host — только указанная ветвь; ни одна строка не закрывает целиком P0 без
нужного Android/ordinary/OEM evidence. N/A здесь означает отсутствующую функцию,
а не недоступный стенд. Приоритеты исходных IDs не снижены.

| ID / канал | Production implementation | Model/host | Android P6b.2 | Ordinary release | OEM | Ограничение / следующий gate |
| --- | --- | --- | --- | --- | --- | --- |
| LIFE-01 P0 Home/lock/Closing | coordinator+signals+UI lease | PASS existing owner/model | NOT_RUN; P6b.1 пользовательский scoped PASS отдельно | NOT_RUN demo only | NOT_RUN | late IO/save исходы + новый код на обоих devices |
| LIFE-02 P0 configuration/keyboard/selection | memory handoff, generation invalidation | PASS existing handoff + UiAccess | NOT_RUN actual recreation suite | NOT_RUN demo only | NOT_RUN | full P6c card/media surfaces |
| LIFE-03 P0 late picker/process | old epoch/token denial | PASS P6b.1 model | NOT_RUN primary-process probe; fresh pending picker проверяется | N/A no real picker | NOT_RUN | actual DocumentsUI/result delivery P6c |
| LIFE-04 P1 explicit picker | existing bounded Locked picker | PASS existing tests | NOT_RUN P6b.1 regression | N/A no picker | NOT_RUN | real UI integration |
| LIFE-05 P1 remembered-folder/cursor | repository existing + rich offsets | PASS existing VFS/model | NOT_RUN regression + rich suite | N/A no engine folder | NOT_RUN | P6c login starts folder not editor |
| LIFE-06 P0 noncancellable IO | owner unchanged, UI lease separate | PASS existing blocking-owner tests | NOT_RUN P6b.1/P6a regression | N/A no engine IO | NOT_RUN | no grace-timeout release claim |
| LIFE-07 P0 multi-resume/shade | existing actual foreground facts | PASS existing model | NOT_RUN new code; previous guided limited | NOT_RUN demo baseline | NOT_RUN | actual multiwindow/service observations |
| LIFE-08 P1 handoff interruptions | existing coordinator + opt-in host barrier | PASS model Home/handoff | NOT_RUN narrow actual-event probe | N/A no authenticated handoff | NOT_CONFIRMED physical rotation+Home/Power | новый checkpoint не доказывает всё OEM relaunch |
| LIFE-09 P0 final snapshot/late result | existing ClosingSave+worker+UI denial | PASS existing P3/P6b.1 tests | NOT_RUN8/13 regressions | N/A no durable ACK | NOT_RUN | P6c async ACK integration |
| LEAK-01 P0 state/logs/temps | no saved snapshots, no raw-key exporter | PASS scanner encodings + negative control | NOT_RUN actual canary scan | NOT_RUN limited public UI; no private read | NOT_RUN | crash payloads/other future surfaces остаются открыты |
| LEAK-02 P0 IME/stale connection | ProtectedEditText generation proxy | PASS authority tests; native code requires Android | NOT_RUN direct actual connection + guided active IME | NOT_RUN native demo | NOT_RUN | no claim all OEM keyboards/history erased |
| LEAK-02 P0 copy/cut/paste | gated native/context/shortcut ClipData | PASS policy/gate | NOT_RUN clipboard suite | NOT_RUN demo input | NOT_RUN | allowed-copy retention product decision open |
| LEAK-02 P0 share/process-text/drag | safe menu callbacks/no classifier/drag cancellation | review AOSP order; no implicit export UI | NOT_RUN native menu/mouse paths | NOT_RUN limited UI | NOT_RUN | real OEM drag/toolbar observations required |
| LEAK-02 P0 autofill/capture/assist | empty real/virtual structures | review/compile; host not Android proof | NOT_RUN5 callbacks + guided services | NOT_RUN public UI | NOT_RUN | actual services/Compose future virtual surfaces |
| LEAK-02 P0 accessibility/password | concealed nodes/events, deny edit/export | PASS authority; no full Card UI | NOT_RUN actual nodes/events | NOT_RUN demo; Card N/A | NOT_RUN | TalkBack/hidden Card/reveal integration P6c |
| LEAK-02 P0 screenshots/recents/windows | reusable window policy/secure before frame | PASS policy lifetime; flags require Android | NOT_RUN Window + guided visual | NOT_RUN black-box secure/visual | NOT_RUN | images/OEM Popup/Compose surfaces independent |
| LEAK-03 P0 static backup exclusions | allowBackup false,9 domains, refusal agent | APK audits recorded below | NOT_RUN runtime | static audit below; runtime NOT_RUN | NOT_RUN | static cannot certify OEM |
| LEAK-03 P0 system backup/transport/restore | refusal agent + explicit probe | host parser/receipt fixtures PASS | NOT_RUN local transport probe | NOT_RUN | NOT_RUN | contents/read/restore-effects ledger independent |
| LEAK-03 P0 D2D/iOS | XML+refusal agent, no invented platform IDs | review current official contracts | NOT_RUN | NOT_RUN | NOT_RUN P0 | separate disposable devices/setup/matching iOS rig |
| LEAK-04 P0 service decoy/old/new keys | no UI-key exporter; maintenance hides; P5 contexts unchanged | PASS existing contexts/authority tests | NOT_RUN maintenance + P5 regression | N/A no service UI, future P6c OPEN | NOT_RUN | full screenshots/search/history/media after integration |
| MAINT-05 P0 revoke-before-switch | existing serialized AcceptSwitch boundary | PASS existing P5/P6b.1 | NOT_RUN required regression | N/A no maintenance UI | NOT_RUN | no new switch protocol or late callback acceptance |
| MAINT-06 P0 accepted-before-revoke | existing Unknown/Unreported+owner | PASS existing fault tests | NOT_RUN required regression | N/A no engine UI | NOT_RUN | no UI reopen/false rollback claim |
| MAINT-08 P0 restart/no service-key restore | existing P5 contexts + fresh coordinator | PASS existing tests | NOT_RUN primary probe + P5 regression | N/A no recovery UI | NOT_RUN | primary probe не полная credential recovery UI |
| MAINT-09 P0 cleanup/contexts | existing quiescence + UI invalidation | PASS existing P5/P6b.1 | NOT_RUN new code/regressions | N/A no service UI | NOT_RUN | used IO handles не закрываются ради hide |
| API31 runtime compatibility | guards/min31, runtime interface | compile/lint only | NOT_RUN no image/runner | NOT_RUN | NOT_RUN | compile minSdk не заменяет smoke |

## P6c точки интеграции и продуктовые решения

Подключить worker-loaded encrypted settings к `SecureSessionPresentation.applySettings`
с **захваченным** epoch/window/capability. Каждый async completion/native action
проверяет актуальную привязку; нельзя читать current epoch в позднем callback и
выдавать результат прошлой зоны новой. Для Card — worker `display/copyDisplay`,
явный reveal, reset на close/revoke; не помещать raw hidden password в View/semantics.
Регистрировать каждое новое Dialog/Popup/Compose/media window и handle current
policy до кадра. Не передавать service plaintext/keys через пользовательские display
API. Legacy demo не доказывает authenticated Main/Decoy/Closing или Card/media.
NoteSaveHook заменить в отдельном P6c на immutable async ACK, dirty/revision/Unknown;
изменения этого PR не создают сохранения/нового source of truth.

Действительно открытые продуктовые решения: (1) выполнимый storage-path/ADR001 до
P6c; (2) срок/UX очистки уже разрешённого sensitive clipboard, если он нужен. Нормы
автоочистки в прочитанных документах нет, таймер/foreign clipboard deletion не введён.
Новых preferences, biometric, полного card/template/media UI, crypto/format changes,
artwork/sorting/icon overlays и suppressible warnings нет. App message suppression
по-прежнему только для согласованного card-editor сообщения.

## Историческая проверка исходной реализации23 октября и передача

Среда Linux, Oracle JDK25.0.4.1, нормативный Gradle9.6.0/checksum, AGP9.4.1,
Kotlin2.2.10, compile/target37, min31, target11, pinned dependencies и R8 scope прежние.
Managed cloud skill применён к среде/сетевой политике; приложение сетевых прав не получило.
Android NOT_RUN: предоставленного device runner/образа нет, /dev/kvm и system-images
отсутствуют, adb enumeration завершается Cannot mkdir /home/agent/.android (read-only).
Это не утверждение об успешно полученном пустом списке устройств. API31 smoke открыт.

Default validation завершилась BUILD SUCCESSFUL:201 JVM tests/32 suites,
0 failures/errors/skipped; lintDebug/release0 errors (16/18 warnings), debug/release/
debugAndroidTest собраны. Окончательные opt-in audit counts/hashes приведены ниже
после их фактического завершения. Windows probes готовы, но не объявлены выполненными.
Обязательная новая матрица:14/device P6b.2 +8/device P6b.1 +13/device P6a. Общая runtime
packaging/ABI/agent startup изменены, поэтому7/8/8/10/8/8 P1/P2/P3/P4/P5a/P5b на новом
коде обоснованы; старую пользовательскую приёмку это не отменяет. KDF benchmarks и
P6a.2 reboot автоматически не запускаются. Power-loss/storage qualification,
полная P6c integration, performance/KDF measurements и independent security review
остаются отдельными условиями до real secrets/release. Draft PR не сливать.


### Завершённые host/build проверки

| Проверка | Фактический результат агента |
| --- | --- |
| Full JVM | PASS:201 tests/32 suites,0 failures/errors/skipped |
| P6b.2 host gate / probe fixtures | PASS:69 checks, включая exact/nested/per-device, XML/protobuf, statuses, stale/foreign/duplicates, APK fields, primary PID/session/Locked и receipt scopes |
| Existing source/result/diagnostics gates | PASS: source92, P6b.1 result24, P6a diagnostics20; P1/P2/P3/P4/P5a/P5b/P6a/P6a.2 host validators также выполнены |
| lintDebug / lintRelease / lintP1Release | PASS:0 errors;16 /18 /44 warnings, без глобальных suppressions/изменений JDK/dependencies |
| Debug / ordinary release / debugAndroidTest | BUILD SUCCESSFUL; production sources с защитами собраны |
| p1Release + p1ReleaseAndroidTest | BUILD SUCCESSFUL после существующего R8; backend target-only,0 duplicate definitions, package access/linkage PASS,3950 ICU resource files и pinned hashes/goldens PASS |
| Actual manifest/backup/isolation | PASS target/test/ordinary; AAPT resource IDs разрешены в реальные compiled XML paths |
| Ordinary release | static backup/classes/mapping-marker audit и actual DEX hierarchy/package access PASS; runtime NOT_RUN |
| Actual APK negative mutations | PASS:14 backup/agent +7 isolation/provider ACL +2 privacy-host ACL/ordinary mapping;23 отказанных отрицательных копии, originals unchanged |
| Dry-run всех шести entry points / смешанные flags | task graph подготовлен; mixed flags отвергнуты; это не исполнение Android |
| Honor15 / Android17 / API31 новые runtime | NOT_RUN агентом, нужны пользовательские receipts |

Канонические команды агента (Linux, подготовленные JDK/SDK/cache):

```bash
bash gradlew :app:testDebugUnitTest :app:testP1RuntimeResults :app:testP2RuntimeResults :app:testP3RuntimeResults :app:testP4RuntimeResults :app:testP5aRuntimeResults :app:testP5bRuntimeResults :app:testP6aRuntimeResults :app:testP6a2Qualification :app:testP6b2RuntimeResults :app:testP6b1RuntimeResults :app:testP4SourceIdentity :app:testP6aRuntimeDiagnostics :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash gradlew -Pp6b2ReleaseRuntime=true :app:testP6b2RuntimeResults :app:testP4SourceIdentity :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity :app:verifyP6aIsolation :app:verifyP6b2OrdinaryRelease :app:lintP1Release --offline --no-daemon --no-parallel --max-workers=2 --no-configuration-cache --console=plain
python3 tools/p6b1/check-backup.py p6b2
python3 tools/p6a/check-isolation.py p6b2
python3 tools/p6b2/check-privacy-apks.py
```

После точечного усиления static ordinary-audit (удаление прошлого PASS перед отказом)
и уточнения Windows-инструкции выполнен повтор APK/source/isolation/ordinary audit:
BUILD SUCCESSFUL19s, R8 outputs UP-TO-DATE, source recaptured. После расширения
primary-process experiment на Open и Locked/picker — ещё BUILD SUCCESSFUL1m30s,
69 host checks, свежие instrumentation APK/mapping/source и повтор всех23 negatives.
После review восстановлен long-click у разрешённого editor при display (hide ранее
выключал его); после этого, а также после закрытия replay статического accessibility todo action
в RichNoteEditText, повторены полные JVM/lint/debug/release и opt-in audits. Предыдущий полный
opt-in run BUILD SUCCESSFUL5m1s; default run7m56s. Краткие промежуточные compile/lint
отказы устранены до окончательных успешных команд; они не засчитывались как runtime.
Точечный onSaveInstanceState suppression описан выше и не снимает strict gate.

Artifact snapshot **перед commit**, проверенное рабочее дерево будущего PR:

```properties
sourceRevision=30fd27d962b7ed9192717ec2c60e59d91bb5ccd7
sourceFingerprint=f43530be0b8ffaecda87452f62e1200e605998ef98252dcbbd509a4ae0a801a0
sourceCodeDirty=true
sourceDirty=true
workingTreeDirty=true
wrapperModified=false
gradleVersion=9.6.0
gradleJvmRuntime=25.0.4.1+1-LTS-5
gradleJvmVendor=Oracle Corporation
wrapperPropertiesSha256=00532bb2c7ac00936fab93e51aa172168c16526f3f74eefe340e1fb8b529e4c8
wrapperJarSha256=76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3
artifactBundleId=93ac21700a2c37bceb35d38fe128ef06a91a2df3795a9ca7b2f57b509d2b1f5b
targetSha256=e806b12def43354fa5483699828e06a5a37678ca6f00a6d63ec8af9fda488275
testSha256=3148ee640d7057ceba23e7a589e9674c170f5d26a96f18aedc0eba75c31f1816
targetMappingSha256=98be1215904632740db72ad4eb853e20e6e64f2e602657e600e454f7f125dfba
testMappingSha256=1c67cb6bcd68841705e71a3e05a581c4598c9033d6c9e5a255a728801a71ce8c
targetMapId=437f9f3dcf2d9587d44475d2b053d424f2f224f7f0a0b0a71cb0918280699373
testMapId=d3df33631d5fef629cedf47f93c600222d53b8ec4aee227aa067b97d14634d90
ordinaryReleaseSha256=9f7879f5a24a3a19e21e0e2760cdb6744af5ca4d040f6e4394f8cac381f556cb
ordinaryReleaseMappingSha256=ebe835229e2aaed136045f1d6ffe42064b2cffa0e2d27b6a48c84848fd56059c
ordinaryReleaseMapId=687b4ff5a91eaeb623792d46a656f8ea2f93a6e6aae0eb3c5697d542b80b4bec
AndroidRuntime=NOT_RUN
```

Это APK не называется clean build будущего commit: capture произошёл на изменённом
рабочем дереве с base SHA. Пользовательские команды пересобирают/фиксируют актуальный
HEAD и фактические Windows wrapper/JVM/dirty значения; будущие результаты должны
ссылаться на свою пару APK/mappings, не автоматически на эти SHA. Report-only правки
после snapshot исключены из source fingerprint, не меняют проверенные source bytes.
Финальный implementation SHA указан в Draft PR и передаче; merge агентом не выполняется.

Ограничение scanner: synthetic markers/UTF‑8/UTF‑16/JSON escaping, текущие доступные
files/cache/noBackup/prefs и свой process log, depth12/4096 files/16MiB per file budget.
Это конкретный canary audit, не доказательство отсутствия произвольных утечек/heap/
чужих IME copies/недоступных crash payloads. Углублённые и OEM/P6c ветви остаются P0.

Process experiment использует external force-stop и не сертифицирует все LMK/task-
restore/OEM сценарии. Open-with-canary и pending picker проверяются отдельно;
прерывание runner не считается завершённым тестом, память/Bundle source controls
и actual recreation suite остаются самостоятельными evidence.

Final opt-in после generation-bound Rich todo и accessibility events: BUILD SUCCESSFUL
6m39s,69 host checks +92 source checks, source unchanged, новые APK/mappings и все23
negative APK audits PASS. Последующий default refresh подтверждает актуальные debug
classes/tests/lint; runtime остаётся NOT_RUN.

Подготовка подписи ordinary release без Android runner также выполнена локально:
апк-копия подписана имеющимся test debug key, apksigner verify PASS, ZIP code/resources
побайтно совпадают с unsigned (кроме signing metadata). Никакой установки/runtime не было.

```properties
preparedSignedOrdinarySha256=b096d94bd53086f868b8d4d884535b73c3c841571d7e8babc5ebe624e4e8c354
preparedOrdinaryCertificateSha256=da723d3527344e3372b2b585b3be52424c510c85a2bf2fb05e8553a66ccbe823
preparedSigningRuntime=NOT_RUN
```

Ключ находится вне репозитория и не включён в PR/диагностику. На Windows используется
свой local debug key, поэтому certificate/installed hash будут записаны заново.

Заключительный default refresh на тех же финальных production sources завершился
BUILD SUCCESSFUL5m36s:201 tests/32 suites,0 failures/errors/skipped; lint0 errors
(16/18 warnings), debug/release/debugAndroidTest PASS. Финальный p1Release lint
0 errors/44 warnings. Direct RuntimeSourceIdentity verify после report-only правок PASS.


## Исправление долгого нажатия и отступов — 24 октября 2026

Рабочая ветка и актуальный remote head перед изменениями:
`codex/copper-scroll-p6b2-data-leak-boundaries`,
**90ceeb20e9264548c5eeeab262eb2febecaff0ed**. Fetch и PR API подтвердили PR№16
OPEN/DRAFT, без merge. Изменения сделаны в этой ветке; R8/optimization/keep rules,
Gradle9.6.0/checksum, AGP/Kotlin/dependencies, storage admission и правила
SessionCoordinator не менялись.

### Входная диагностика и установленная причина

Прочитан пользовательский `p6b2-privacy.zip`, SHA256
`91d2d110774c9207f23060026ad152354f9712a3cc10ba7878d8095c6db3ca65`.
Два hostRunId: `45115955-0a7f-41db-af1c-4daa3adb212d` и
`754426a1-c1e6-4ead-801b-5eb8d034ca72`. В каждом device-0 — Honor ABR-LX1/API35,
device-1 — Virtual_Pixel_6/API37. Все четыре runtime-result: **FAIL**, runner
`Process crashed`, controller `PROBE_RUNNER_FAILED`. Это пользовательские
Windows/Gradle9.6.0 прогоны, не исполнение агента; пройденные до падения шаги не
составляют успешный privacy probe.

Стек указывает на `IndexOutOfBoundsException: setSpan (33 ... 33) ends beyond
length …`, `ProtectedEditText._init_$lambda$2(ProtectedEditText.kt:63)`. В первом
run длина editor18 на обоих устройствах, во втором29/32. Вложенные artifact ZIP
содержат соответствующие mappings, их marker IDs совпадают с provenance:

```properties
sourceRevision=90ceeb20e9264548c5eeeab262eb2febecaff0ed
targetMapId=437f9f3dcf2d9587d44475d2b053d424f2f224f7f0a0b0a71cb0918280699373
testMapId=cfa8a325decb338b116f56ee6471eaf7dde5938b7e236e4305a745aacd9acc73
targetMappingSha256=98be1215904632740db72ad4eb853e20e6e64f2e602657e600e454f7f125dfba
testMappingSha256=7b56f8ef7401e89e828db206998434f85cd0f6498fda66695a669f7dbce58ecc
```

Application editor находится вне R8 optimization scope; его исходные имена уже
присутствуют в стеке. Анализ исходника90ceeb2 и `javap -c -p` сохранённого compile
JAR подтвердил receiver shadowing: в
`BreakIterator.getWordInstance().apply { setText(text.toString()) }` имя `text`
обращается к **BreakIterator.getText(): CharacterIterator**, а не к editor.text.
Iterator получает строковое представление своего CharacterIterator. Границы по
чужой строке применяются к editor. Это установленная ошибка независимо от
сокращения текста или предполагаемой гонки; чужой mapping не использовался для
достоверного retrace. JVM negative control воспроизводит ошибочный receiver и
выход границы за длину короткого editor.

### Production-исправление

`WordSelection.range` получает один immutable String; `BreakIterator.setText`
вызывается с ним явно, возвращая UTF-16 offsets именно этого снимка. На конце
выбирается последняя code point, границы не разрывают surrogate pair; emoji,
combining marks и переводы строк не нормализуются и не изменяются.

`ProtectedEditText` захватывает Editable identity, text revision, String snapshot,
UI authority и binding generation. После вычисления offset, перед setSelection
и после синхронных selection callbacks повторно проверяются все эти условия и
живой session/window access. Replace/setText, rebind/revoke отменяют прежнюю
попытку; повторный long-click из callback не входит в обработчик рекурсивно.
Узкая обработка IndexOutOfBoundsException между двумя Selection span writes
допустима только при **доказанной утрате актуальности снимка**; при неизменном
состоянии исключение повторно выбрасывается. Исправление не сводится к catch.

ActionMode также связан с прежними generation/authority при create/prepare/click;
позднее меню не получает прав следующей сессии. Сохраняются выбор слова/существующего
актуального выделения, sensitive copy при разрешении зоны, запрет share/assist/
process-text/drag, скрытые пароли и немедленный отказ после revoke. IO, owner,
ключи, persistence, S1 и формат контейнера не затронуты.

### Изменения только тестового интерфейса и стенда

Общий P6b.1/P6b.2 host, synthetic picker и собственный Dialog используют
`HarnessWindowInsets`: root viewport вне ScrollView, сверху/снизу эффективный
отступ **max(40dp, systemBars/displayCutout)**. Измеренная уже потреблённая часть
decor вычитается, по бокам учитываются панели и cutout в landscape. Все controls,
включая Close dialog, находятся в прокрутке. adjustResize и измерение оставшегося
IME overlap позволяют не применить уменьшение под IME дважды. У собственного
окна secure flag задаётся до показа, scope/register/close остаются прежними;
постоянного диагностического входа в ordinary release нет.

Новые Android методы включены одновременно в exact result gate, DEX audit,
host fixtures и Windows-инструкцию: теперь ровно14 completed PASS/device, исходные
12 методов сохранены. Long-press отправляет MotionEvent DOWN/UP через UiAutomation,
ждёт фактический callback по условию, вычисляет координаты заново из layout,
padding/scroll и текущего положения View. Проверяются исходный текст → сокращение
Editable → замена setText, начало/середина/конец, empty/multiline/emoji, синхронная
mutation/reentry selection, смена binding и revoke → новая decoy session.
Фиксированных задержек для «стабилизации» нет. Ordinary-release координатные
действия уже вычислялись по актуальным accessibility node bounds; screen constants
после изменения layout не добавлены.

Viewport regression проверяет portrait/landscape, общую status/editor/нижние
privacy controls, собственное окно/Close и synthetic picker/Cancel. Ориентация
меняется в Locked, а не обходом handoff; coordinator/admission не ослаблены.
Геометрия consumed insets и IME дополнительно покрыта JVM. Это не заменяет
визуальную проверку Honor corners/camera cutout и реальную IME на обоих устройствах.

### Проверки исправления

Предварительный host run: **210 JVM tests /34 suites,0 failures/errors/skipped**;
P6b.2 host gate69, source gate92, P6b.1 result gate24, diagnostics20 — PASS.
Финальная Android instrumentation Kotlin compilation — BUILD SUCCESSFUL. Проверки
после исправления завершены (это host/build evidence, не Android execution):

| Проверка | Результат24 октября |
| --- | --- |
| Full JVM | 210 tests/34 suites,0 failures/errors/skipped; новые WordSelection5 и InsetsGeometry4 включены |
| Host gates | P6b.2 result69, обязательный source92, P6b.1 result24, runtime diagnostics20 — PASS |
| lintDebug / lintRelease / lintP1Release | 0 errors/fatal;16/18/45 warnings, без новых blanket suppressions; дополнительный opt-in SetTextI18n относится к synthetic Close dialog button |
| debug / ordinary release / debugAndroidTest | BUILD SUCCESSFUL3m43s |
| p1Release / p1ReleaseAndroidTest + audits | BUILD SUCCESSFUL1m43s; существующий R8 сохранён, target/test реально собраны, final source unchanged PASS |
| Окончательный source/artifact/ordinary/isolation refresh после правки инструкции | BUILD SUCCESSFUL15s; оба APK UP-TO-DATE, input fingerprint захвачен заново, source unchanged PASS |
| Actual APK audits | production backend/crypto target-only,0 duplicate definitions, hierarchy/package access и linkage PASS;3950 ICU resources/goldens PASS |
| Compiled manifest / backup / isolation / ordinary mapping | PASS, test controls отсутствуют в ordinary release |
| Negative copies |14 backup/agent +7 isolation/provider ACL +2 privacy ACL/ordinary mapping =23 отказанных копии; originals unchanged |
| User entry points | dry-run проверки automatic/privacy подготовлен отдельно; не исполняет Android |
| Honor15 / emulator17 / API31 runtime исправления | NOT_RUN агентом |

Команды текущей проверки (Linux, JDK25.0.4.1/Gradle9.6.0, подготовленные SDK/cache):

```bash
bash gradlew :app:testDebugUnitTest :app:testP6b2RuntimeResults :app:testP4SourceIdentity :app:testP6b1RuntimeResults :app:testP6aRuntimeDiagnostics --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash gradlew :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --offline --no-daemon --no-parallel --max-workers=2 --console=plain
bash gradlew -Pp6b2ReleaseRuntime=true :app:testP6b2RuntimeResults :app:testP4SourceIdentity :app:verifyP1ReleaseApks :app:verifyP4BuildIdentity :app:verifyP6aIsolation :app:verifyP6b2OrdinaryRelease :app:lintP1Release --offline --no-daemon --no-parallel --max-workers=2 --no-configuration-cache --console=plain
python3 tools/p6b1/check-backup.py p6b2
python3 tools/p6a/check-isolation.py p6b2
python3 tools/p6b2/check-privacy-apks.py
bash gradlew -Pp6b2ReleaseRuntime=true :app:p6b2ReleaseRuntimeCheck :app:p6b2PrivacyProbe --dry-run --offline --no-daemon --no-parallel --max-workers=2 --no-configuration-cache --console=plain
```

Новые pre-commit артефакты (HEAD90ceeb2 с исправленными исходниками, не clean build
будущего commit):

```properties
sourceRevision=90ceeb20e9264548c5eeeab262eb2febecaff0ed
sourceCodeDirty=true
sourceDirty=true
workingTreeDirty=true
wrapperModified=false
sourceFingerprint=3548669c7d345b17d04b9e417c7b13a9b49456c335052c25b0105ff601345c6a
artifactBundleId=64911741e506e8d2ae61094fa1ed5589ed4abf3f6bd09f240ad82c39b75320ce
targetSha256=06e460883cdf9b177ca32dbb729d9122f64175e68a7b47051b89b5d4f451b00f
testSha256=65eab70a92e1ed84c4239270723528fc65328e169da6f430001cb59e9cfd194c
targetMapId=8f4e34faa7fc68eee1d40a36b256f5a17ce4226945d2f9bc3ae2e9dba1738ff8
testMapId=cb691b5d9420c27087a3f8a10b55fdf8de83638d9e1be5170274e183ec6e697f
targetMappingSha256=ad12856fb5526be6d01ea370e6a3f21bdb8d83985b1a3fbea16bdf2e4c3d964e
testMappingSha256=38add240c3652c8bcc3fccdd61f0eb60379ac183720415bba280c8d85f442940
ordinaryReleaseSha256=b96f470be6036327b3bf558af1fca8c43cc10e527c4c5da90cce388b45faf1a8
ordinaryReleaseMapId=77d87bc9f94728d9649627fa28ec2e185cedc06b8b5c19672fab9decb0aaaa0a
AndroidRuntime=NOT_RUN
```

Оба mappings, configuration, generated instrumentation ABI и manifests находятся
в `app/build/reports/p6b2-runtime/artifacts/<artifactBundleId>/`, audit reports —
рядом, failed/build attempt archives — отдельно. Следующий пользовательский запуск
захватит свой свежий SHA/fingerprint/APK/mappings; старые receipts не переносятся.
Приведённые выше historical23 October hashes сохранены как история.

### Оставшиеся условия и повторный запуск

Android runner здесь отсутствует: нет /dev/kvm, system-images и emulator;
`adb devices -l` не смог запуститься из-за read-only `/home/agent/.android`.
Это не успешно полученный пустой список устройств. Исправленный автоматический
runtime, ручной privacy probe, portrait/landscape/IME/dialog на Honor15 и AVD17 —
**NOT_RUN агентом**, runtime PASS по компиляции или APK audit не заявлен.

После pull текущей ветки PR№16 выполнить последовательно в Windows Terminal:

```powershell
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2ReleaseRuntimeCheck --no-configuration-cache --console=plain
.\gradlew.bat -Pp6b2ReleaseRuntime=true :app:p6b2PrivacyProbe --no-configuration-cache --console=plain
```

Первый результат:14 completed PASS на каждом устройстве (28 на двух),0 failures/
errors/skipped и BUILD SUCCESSFUL. Второй: отдельные пользовательские observations
пяти групп; проверить выделение после замены, оба положения экрана и клавиатуру,
собственное окно, верх/низ прокрутки по [инструкции](../../../tools/p6b2/android-runtime-windows.md).
Прислать свежий runtime-result, оба privacy receipts и описание обеих ориентаций;
при отказе — текущий diagnostics ZIP/Terminal. Старый12-method report gate не
принимает. Требуемая P6b.1 регрессия по-прежнему8/device, остальные регрессии
и ограничения p1Release/обычного release, API31 и storage REFUSED сохранены.

**Отдельная открытая проблема: предыдущий пользовательский handoff FAIL на Honor.**
Этот архив касается long-press/privacy, нового успешного handoff receipt не
содержит. Причина того отказа и его исправление не объявлены установленными;
long-press/viewport PASS не закроет его. P6b.2/P6b целиком не приняты; PR№16
сохраняется Draft, без merge.
