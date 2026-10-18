# Нормативные малые fixtures и проверка согласования1.1

Эти материалы относятся к [формату1.1](../container-format-v1.md),
[решениям проверки](../container-review-resolution.md) и первому заданию P1.
Синтетические открытые значения не являются пользовательскими паролями/данными.
Это CBOR components и абстрактные модели, не движок/зашифрованные `.cscroll` files.
Исторические [review-materials](../review-materials/README.md) не изменены.

* `wire-fixtures.json`:6 фиксированных hex vectors: folder null и отрицательный
  array payload, settings defaults/known/unknown display enums, unknown note icon.
  Bytes представлены JSON marker `{"bytes":"hex"}`; это оболочка fixture, не CBOR map.
* `s1-vectors.json`:21 нормативный ввод/ожидаемые bytes/отказ S1, data16.0.0;
  valid text в `text`, pattern в `hits`, malformed UTF-16 в `utf16le_hex`.
  Статус `InvalidSecret` агрегирует validation error до KDF; UI может показать
  безопасную конкретную причину. Схемы/ресурсные пределы полностью в format§3.
* `check_fixtures.py`:tiny independent CBOR decoder сравнивает literal bytes с
  values; отдельный ранее написанный size encoder проверяет те же bytes. Проверка
  schema ограничена конкретными fixtures, не production parser/fuzzer. Также
  проверяет внутреннюю согласованность S1 expectations и показывает Unicode
  runtime, **не реализует** NFC/UCD16.0.0.
* `protocol_model.py`:порядки AcceptSwitch/Revoke/durable persist, lease на весь
  snapshot, rekey ref rewrite и counterexample замены одного root, namespace
  predicate. Record/key labels символические: cryptographic old-key rejection
  этим не доказан, он проверяется будущим REKEY-02.
* `validate_docs.py`:локальные ссылки/якоря, таблицы, уникальные test IDs,
 13 resolution rows, версия/несколько запрещённых старых критериев; с baseline
 проверяет docs-only scope и неизменность исторического review. Это статические
 checks, не доказательство всех семантических свойств.

Запуск из корня repo, Python3 stdlib:

```sh
python3 docs/architecture/reconciliation-materials/check_fixtures.py
python3 docs/architecture/reconciliation-materials/protocol_model.py
python3 docs/architecture/review-materials/format_probe.py
python3 docs/architecture/review-materials/maintenance_model.py
python3 docs/architecture/review-materials/unicode_probe.py
python3 docs/architecture/reconciliation-materials/validate_docs.py --baseline cc9871a4fe6d5965ba000dc016e4b5902faeb6ca
```

Фактически выполнено в docs-only согласовании: все шесть команд завершились0. Проверены32 локальных ссылки/якоря,26 таблиц,
106 уникальных будущих test IDs и13 строк решений R/C; staged diff проверен отдельно.
Новые проверки:6 CBOR fixtures,21 S1 static expectations; host Unicode15.0.0
даёт для пары `f0918e82f0918f89`, нормативный16.0.0 ответ `f0918e83`.
Host не признан реализацией нового S1. Abstract model проверила3 допустимых
порядка accept/revoke/persist, frozen snapshot и ref rewrite5 target records.
Исторический layout probe:header65536/record76, budgets прежние (minor1 размер
поля не меняет). Исторические модели сохраняют свои контрпримеры старых пробелов.

## Как проверить S1 на независимых реализациях

Будущий adapter получает на stdin JSON `{unicode_version, fixtures}` **без**
expected answers. Он должен использовать pinned Unicode16.0.0 NFC/repertoire
и вернуть `{unicode_version:"16.0.0", results:[{id,status,hex?},...]}`.
Поля input те же, что выше; `normalized` не требуется в ответе. Один ответ на
каждый id; valid status `ok`+hex, invalid `InvalidSecret`. Проверка запускается:

```sh
python3 docs/architecture/reconciliation-materials/check_fixtures.py --adapter java -jar /path/to/s1-adapter.jar
python3 docs/architecture/reconciliation-materials/check_fixtures.py --adapter /path/to/independent-s1-adapter
```

Это протокол испытания, не существующие приложенные adapters. Каждый обязан
отдельно доказать настоящую data version и пройти полный официальный
[NormalizationTest16.0.0](https://www.unicode.org/Public/16.0.0/ucd/NormalizationTest.txt),
repertoire по [UnicodeData16.0.0](https://www.unicode.org/Public/16.0.0/ucd/UnicodeData.txt),
raw/normalized bounds и surrogate rejection. Echo expected, большинство старых
vectors или системная NFC не conformance. Fixtures не полный corpus Unicode.

Pinned16 normalizer/два adapters/полный conformance **сейчас не запускались**;
это exit gate P1. Crypto known-answer будущей библиотеки, engine rekey/AEAD,
Android build/device/power-loss/fuzz/provider durability не выполнены. Успешный
small script не закрывает эти gates. Новых app dependencies не добавлено.
