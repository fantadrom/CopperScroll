# Утверждённые требования: уплотнение выбранных зон

Дата согласования:18 октября2026. Статус: включено в согласованную архитектуру1.1,
реализация отсутствует. Полный протокол —
[design§8](container-design.md#8-копии-и-общий-протокол-обслуживания),
[формат](container-format-v1.md), [тесты](container-test-plan.md),
[отдельные задания](container-prototype-plan.md). Документы согласованы; это
дополнение больше не отменяет противоречащие абзацы. Историческое состояние
остаётся в Git и отчёте SHA54e40f3.

## Доступ и подтверждение

Compact только из текущей main-сессии. Из decoy нет меню, запроса main password,
перехода в обслуживание или предложения другой зоны. Coordinator отвергает direct
dispatch/deep-link/старую capability первым действием до picker/KDF/резервирования/
файлов. Low-level symmetric copier не предоставляет этого пользовательского права.
COMPACT-02 теперь проверяет отказ, а не доступ в корзине decoy.

Выбор main/decoy/both, обе существующие default selected; main-only файл не
предлагает decoy, empty запрещён. Decoy-only также из main. Для selected decoy
сообщить необходимость её текущего пароля и проверить **expected decoy zoneId**,
envelope/MAC до reservations/backup/candidate. Main secret в decoy prompt не успех.
Wrong/forgotten/cancelled/unsupported/ResourceLimit не создают artifacts и не
дают automatic main-only fallback. Вернуться, снять decoy и подтвердить заново
можно только явно. Дополнительная main reauth самим compact не требуется.

Destinations/grants/profiles заранее. Если нужен picker, main сначала блокируется;
valid result сохраняет лишь Locked настройку. После нового входа новая команда,
credentials и confirm, без продолжения старой сессии. Rotation оставляет одну
memory operation/token, не новый worker.

## Один snapshot, backup, candidate и результат

После проверки ключей и окончательного подтверждения разрешить pending save/
CommitUnknown, захватить exclusive maintenance lease обеих зон, сверить исходную
identity/envelopes и зафиксировать один S. Запрет всех mutations/password/add/
migration на весь workflow. Preflight полного known reachable graph выбранных;
unknown refs — отказ всей операции до backup, без partial fallback.

Одна новая VerifiedCopy всего committedLength S, затем один новый пустой контейнер.
Selected — reachable records с trash/settings/note/media dependencies, raw copy
без перешифрования. Unselected — весь набор catalogue entries и raw records
побайтно без её пароля. Descriptor[0..16] прежний, physical catalogue pages и
поле[17] rebuild под новые offsets. Каталог не доказывает полноту уже атакованного
source; unknown plaintext невыбранной разрешён только при known outer framing
и известном MAC relocation contract.

Whole candidate verify: selected AEAD/refs/logical equality, unselected полный
recordId/raw-hash multiset/logical descriptor, ranges, force/reopen и durable
identity. Два header одинаковы, containerId прежний, instanceId новый,
generation=S+1, zoneSeq/IDs/nonce/MAC прежние. Никаких отдельных кандидатов/
переключений после каждой зоны; failure между зонами/verify не partial success.

До AcceptSwitch cancel/error/revoke сохраняет source binding. AcceptSwitch и
Revoke сериализованы: revoke-first запрещает принятие; принятое до блокировки
решение может завершить durable publication, но late callback не принимает новый
switch и не открывает UI. Неопределённость — SwitchUnknown/Locked, durable journal,
повторная проверка и явное решение. Нет ложного Changed/успеха/«точно отменено».
Source и backup retained, не выбирать candidate автоматически по имени/mtime/restart.

## Ключи и lifecycle

Main secret не выводит decoy ZDK. Временные independently obtained handles только
maintenance context, не dual UI login. Service decrypt для traversal разрешён,
decoy editor/list/search/history/thumbnails/media не открываются. Keys/S1/plaintext
не попадают в journal/settings/saved state/logs/plaintext temps. Все buffers/caches/
queues/crypto states/borrowed handles включены в ownership и cleanup.

Home/screen-off немедленно hide/revoke. StopRequested context освобождает используемые
keys/FD/lock после фактической quiescence. Cancel/timeout не доказывают возврат
syscall,2с уничтожение всех RAM copies не обещается. Новый writer/доступ ждёт
окончания и reconciliation. Успешное обслуживание закрывает старую сессию, новый
вход отдельно. Rotation не background.

## Размеры, совместимость и проверки

Показать selected/processed, S/C/S−C, новый путь и retained source/backup. С новой
backup S суммарно2S+C, прирост S+C; удаление одного source оставляет S+C.
Учесть прежние copies/scratch/partial artifacts и logical length против physical
allocation. C≥S честно сообщается. Удаление source/backup отдельно.

Два selected не требуют новых layout полей: offset-free LogicalRef, offsets/
instanceId вне Krecord/AAD, catalogueRoot вне logicalMAC. Minor0→1 отдельно фиксирует
уточнённые S1/folder/enums прежнего draft, не новое поле ради selection.
ChangeSecret с новым ZDK — другой вид общего обслуживания, не re-encryption compact.

Обязательны COMPACT-01…10, COPY/MAINT/LIFE/LEAK/DUR и applicable SCHEMA/KEY:
все selections/defaults/absent/empty, wrong/cancelled target secret без artifacts,
один backup/candidate, failure между зонами, revoke/switch, blocking IO, stale
journal, cleanup и portability. Это будущие испытания, не пройденные device tests.
