# Материалы критической проверки

Источник: архитектура Copper Scroll на SHA
`54e40f38d8622fc683672a96b643bf243c59b3b6`.
Выводы, точные ссылки на строки и ограничения: [отчёт](../container-critical-review.md).

Запуск из корня репозитория, Python 3, только стандартная библиотека:

```sh
python3 docs/architecture/review-materials/format_probe.py
python3 docs/architecture/review-materials/maintenance_model.py
python3 docs/architecture/review-materials/unicode_probe.py
```

* `format_probe.py` считает размеры форм CBOR и ёмкость деревьев. Повторённые
  максимальные entries используются только для расчёта длины, не образуют
  валидное sorted tree. Граф future schema — абстрактный контрпример неполного
  обхода, не существующий v1 fixture. Encoder ограничен нужным здесь subset;
  это не реализация parser, криптографии или storage engine.
* `maintenance_model.py` перечисляет порядки check/revoke/switch и показывает
  опасность снятия barrier после одного backup. Исправленный атомарный fence
  проверяется только в абстрактной модели; durable IO/journal не моделируются.
* `unicode_probe.py` показывает фактический NFC текущего Python и ожидаемые
  bytes из опубликованного Unicode16 conformance vector. На проверенном runner
  Unicode15.0.0 значения различались. На новом runtime script может показать
  совпадение; это не отменяет наблюдения на старом runtime. Android не запускался.

Все три скрипта выполнены при подготовке отчёта. Они не заменяют device/provider,
fault injection реального движка, crypto known-answer или fuzz tests.
