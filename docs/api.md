# API

Swagger UI: `http://localhost:8090/swagger-ui.html`.

## POST /api/datasets

Загрузка входного GeoJSON `FeatureCollection` (до 3 ГБ). `multipart/form-data`, поле `file`.
Файл сохраняется на временный диск, разбор идёт в фоне.

Ответ `202`:

```json
{"id": "3f1c...", "status": "PROCESSING"}
```

`400`: пустой файл.

## GET /api/datasets

Список загрузок: `id`, `name`, `status`, `file_size`, `created_at`, `finished_at`, `error`.

## GET /api/datasets/{id}

Статус и отчёт диагностики. `404`, если загрузки нет.

| Поле `report` | Смысл |
|---|---|
| `sourceCrs` | CRS из файла (`urn:ogc:def:crs:OGC:1.3:CRS84`) |
| `featuresRead` / `featuresStored` / `featuresRejected` | сколько прочитано, принято, отброшено |
| `objectTypeCounts`, `restrictionTypeCounts` | счётчики принятых объектов |
| `totalFlowTph` | сумма `flow_tph` принятых точек ОКС |
| `bboxWgs84` | `[minLon, minLat, maxLon, maxLat]` |
| `issueCounts` | число проблем по ключу `СЕРЬЁЗНОСТЬ:КОД` |
| `issueSamples` | первые проблемы с номером объекта в файле и id (до `heat.ingest.max-issue-samples`) |

Коды проблем:

| Код | Серьёзность | Когда |
|---|---|---|
| `NOT_A_FEATURE`, `NO_PROPERTIES` | ERROR | элемент не Feature или без properties |
| `NO_OBJECT_TYPE`, `NO_ID` | ERROR | нет обязательного атрибута |
| `NO_RESTRICTION_TYPE` | ERROR | ограничение без `restriction_type` |
| `BAD_FLOW` | ERROR | `flow_tph` нет или ≤ 0 |
| `BAD_GEOMETRY`, `EMPTY_GEOMETRY` | ERROR | геометрия не разбирается или пустая |
| `WRONG_GEOMETRY_TYPE` | ERROR | тип геометрии не подходит сущности (таблица 2.2 rules.md) |
| `COORDS_OUT_OF_RANGE` | ERROR | координаты вне диапазона lon/lat |
| `INVALID_GEOMETRY` | ERROR | невалидная геометрия, исправить не удалось |
| `UNKNOWN_OBJECT_TYPE` | WARN | объект сохранён, в расчёте не участвует |
| `UNKNOWN_RESTRICTION_TYPE` | WARN | тип без правил (R-IN-03) |
| `DUPLICATE_ID` | WARN | id повторяется внутри `object_type` |
| `BAD_DIAMETER` | WARN | у `heat_network` нет корректного `diameter` |
| `GEOMETRY_FIXED` | WARN | невалидная геометрия исправлена (JTS GeometryFixer) |
| `UNEXPECTED_CRS` | WARN | CRS файла не WGS 84 |

ERROR означает, что объект отброшен, WARN - что принят.

## GET /api/datasets/{id}/summary

Состав данных по факту в БД:

```json
{
  "counts": [{"object_type": "heat_network", "restriction_type": null, "count": 29}, ...],
  "heatNetworkLengthM": 1459.7,
  "extentCalc": {"min_x": 413661.4, "min_y": 6172533.6, "max_x": 415675.7, "max_y": 6174162.3}
}
```

Пример - конкурсный датасет: 144 объекта, отброшенных 0, `totalFlowTph` = 488,72.

`extentCalc` - охват в EPSG:32637, метры.
