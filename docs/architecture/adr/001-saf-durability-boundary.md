# ADR-001: рабочий путь при неполном SAF durability contract

22.10.2026. **Предложение к рассмотрению; архитектура не переключена.**
Контекст: [P6a.2 research](../storage-provider-qualification.md) не установил у
standard primary SAF атомарный pre-open alias identity и namespace durable ACK.
Повторение успешных fsync/probes не устраняет отсутствие разрешённого интерфейса.
Production allowlist остаётся пустой, формат1.1/1.2 и P3/P5 протоколы прежние.

| Вариант | Источник истины / переносимость и выбор | Crash/recovery, main/decoy | UX и объём |
| --- | --- | --- | --- |
| Ограниченный provider/backend с documented contract | Канонический portable внешний файл; trusted resolver + namespace force + conditional delete должны быть частью подтверждённой реализации | P3/P5 прежние; два независимых ключа и main-only; отдельная namespace/local qualification | Лучшее сохранение нынешней архитектуры, но нужен доступный контракт/стенд. Интеграция/сопровождение backend и версии; authority allowlist недостаточна |
| Иной явный доступ/публикация | Явно разрешённый интерфейс должен дать stable identity, exclusive create, directory transaction/force; SAF path guessing не допустим | Сохранить same snapshot/copy/whole verification, но отдельный adapter contract/recovery и grants/permissions review | Сначала определить применимый API без broad storage/root/hidden APIs; пока нет готового варианта для произвольного shared root |
| App-private рабочий контейнер, отдельные import/export/backup | Канонический файл внутри приложения, внешние копии — явные portable snapshots; перенос/переустановка больше не прозрачное открытие выбранного внешнего source | Сохраняются independent zones/main-only, но binding, recovery, external freshness и retention требуют явного нового дизайна. noBackup/power-loss/security gates всё равно нужны; удаление приложения может потерять рабочий файл | Наиболее практичное направление для дальнейшего проектирования, если ограниченного backend нет. Новые UX выбора/import/export/backup, предупреждения об истине/удалении, оценка места/rollback; отдельное ТЗ и ADR approval |
| Read-only неподходящий SAF root | Внешний файл остаётся истиной, portable; чтение при поддержанном regular FD/grant | Нет edit/create/maintenance/switch ACK без gates, две зоны независимо доступны для чтения | Уже действующее fail-closed поведение; не обеспечивает необходимый приложению рабочий write путь |

Рекомендация: сохранить read-only/refusal сейчас. Для полноценного продукта отдельно
рассмотреть app-private working container с явным import/export/backup, если нельзя
получить ограниченный documented provider. Это **изменение источника истины**, не
незаметный staging/cache, не исправление формата и не решение этого PR. Не считать
local путь квалифицированным только потому, что приложение владеет каталогом.

Минимально недостающее для прежней архитектуры: trusted pre-open identity, usable
namespace durable publication API/contract, конкретная FS/mount/flush/device failure
model и independent local publication evidence. Для compare-delete нужен атомарный
backend primitive; иначе retained artifacts. Для новой архитектуры нужны отдельный
согласованный UX/источник истины/portable-copy semantics и те же local/device/security
gates. P6a полностью открыт до рабочего нормативного пути или принятого пользователем
архитектурного решения. Агент не вводит новую внутреннюю копию в этом PR.

Параллельно возможен P6b без real data: foreground/top-resumed policy, screen-off,
rotation/multiwindow, picker invalidation, secure recents, IME/autofill/content capture/
clipboard/reveal/leak checks, backup/OEM transfer rules и owner quiescence. Это не
закрывает durability, UI integration P6c или независимую security/format review.


## Наблюдение Honor после reboot — 23 октября 2026

[Переданное пользовательское подтверждение](../validation/p6a2-report.md) разделяет data PASS и identity.
На Honor process samples: SAMPLED_MATCHES_ONLY, после reboot45→46:
**SAME_MODE_UNSTABLE_OBSERVED** — st_dev изменился и для r→r/rw→rw сохранённых
baseline, inode совпал. Emulator process/reboot: MODE_DEPENDENT_OBSERVED,
собранные same-mode samples совпали. Для обоих identity UNCONFIRMED,
admission REFUSED, три durability guarantees UNKNOWN, power/storage interruption
NOT_RUN. Этот результат не доказывает подмену и не даёт права нормализовать dev,
заменять identity inode/хэшем или разрешать production provider.
P6b.1 выполняется на synthetic данных. Перед P6c требуется явный выбор выполнимого
storage-пути с определёнными гарантиями; app-private канонический working container
пользователем не согласован и здесь не вводится.
