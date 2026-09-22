# API

Swagger UI: `http://localhost:8090/swagger-ui.html`. Карта: `http://localhost:8090/`.

Порядок работы: загрузить датасет → дождаться `READY` → запустить расчёт → дождаться `READY` → забрать GeoJSON.

```bash
ID=$(curl -s -F "file=@dataset.geojson" http://localhost:8090/api/datasets | jq -r .id)
curl -s http://localhost:8090/api/datasets/$ID | jq .status                 # READY
SID=$(curl -s -X POST http://localhost:8090/api/datasets/$ID/solve | jq -r .id)
curl -s http://localhost:8090/api/solutions/$SID | jq .summary.variants     # сводка по вариантам
curl -s "http://localhost:8090/api/solutions/$SID/geojson?download=true" -o result-2d.geojson
```

## Датасеты

### POST /api/datasets

Загрузка входного GeoJSON `FeatureCollection` (до 3 ГБ). `multipart/form-data`, поле `file`.
Файл сохраняется на временный диск, разбор идёт в фоне. Ответ `202`: `{"id": "...", "status": "PROCESSING"}`.

### GET /api/datasets

Список загрузок: `id`, `name`, `status`, `file_size`, `created_at`, `finished_at`, `error`.

### GET /api/datasets/{id}

Статус (`PROCESSING` / `READY` / `FAILED`) и отчёт диагностики `report`:

| Поле | Смысл |
|---|---|
| `sourceCrs` | CRS из файла |
| `featuresRead` / `featuresStored` / `featuresRejected` | прочитано, принято, отброшено |
| `objectTypeCounts`, `restrictionTypeCounts` | счётчики принятых объектов |
| `totalFlowTph` | сумма `flow_tph` точек ОКС |
| `bboxWgs84` | `[minLon, minLat, maxLon, maxLat]` |
| `issueCounts`, `issueSamples` | проблемы по ключу `СЕРЬЁЗНОСТЬ:КОД` и первые примеры |

Коды проблем:

| Код | Серьёзность | Когда |
|---|---|---|
| `NOT_A_FEATURE`, `NO_PROPERTIES` | ERROR | элемент не Feature или без properties |
| `NO_OBJECT_TYPE`, `NO_ID` | ERROR | нет обязательного атрибута |
| `NO_RESTRICTION_TYPE` | ERROR | ограничение без `restriction_type` |
| `BAD_FLOW` | ERROR | `flow_tph` нет или ≤ 0 |
| `BAD_GEOMETRY`, `EMPTY_GEOMETRY` | ERROR | геометрия не разбирается или пустая |
| `WRONG_GEOMETRY_TYPE` | ERROR | тип геометрии не подходит сущности |
| `COORDS_OUT_OF_RANGE` | ERROR | координаты вне диапазона lon/lat |
| `INVALID_GEOMETRY` | ERROR | невалидная геометрия, исправить не удалось |
| `UNKNOWN_OBJECT_TYPE` | WARN | объект сохранён, в расчёте не участвует |
| `UNKNOWN_RESTRICTION_TYPE` | WARN | тип без правил (R-IN-03), в расчёте - запрет 1 м |
| `DUPLICATE_ID` | WARN | id повторяется внутри `object_type` |
| `BAD_DIAMETER` | WARN | у `heat_network` нет корректного `diameter` |
| `GEOMETRY_FIXED` | WARN | невалидная геометрия исправлена |
| `UNEXPECTED_CRS` | WARN | CRS файла не WGS 84 |

### GET /api/datasets/{id}/summary

Состав данных в БД: счётчики по типам, длина существующей сети, охват в EPSG:32637.

### GET /api/datasets/{id}/geojson

Входные объекты в WGS 84 в районе расчёта (для карты, до 50 000 объектов).

## Расчёт

### POST /api/datasets/{id}/solve

Запуск расчёта в режиме 2D в фоне. `202`: `{"id": "...", "status": "PROCESSING"}`.
`409` - датасет ещё не загружен, `404` - датасета нет. Одновременно идут `heat.solver.workers` расчётов,
остальные ждут в очереди.

### GET /api/datasets/{id}/solutions

Расчёты по датасету.

### GET /api/solutions/{id}

Статус и `summary`:

```json
{
  "elapsedMs": 21450,
  "oksCount": 17,
  "totalFlowTph": 488.72,
  "diagnostics": [],
  "variants": [
    {
      "variant_id": "v1", "rank": 1, "strategy": "largest-flow-first",
      "score": 13.66, "calculated_cost": 274393318, "construction_cost": 274393318,
      "pipe_cost": 224393318, "chamber_construction_cost": 50000000,
      "existing_chamber_tie_in_count": 0, "unconnected_penalty": 0,
      "new_network_length": 1993.2, "new_chambers": 10, "tie_ins": 4, "unconnected": 0,
      "validation_errors": [], "notes": ["ОКС 3: выход из здания через ближайшую допустимую точку границы ..."]
    }
  ]
}
```

### GET /api/solutions/{id}/geojson

Выход по разделу 7 ТЗ: один `FeatureCollection` в WGS 84 со всеми вариантами (`variant_id` = `v1`…`v3`,
`v1` - лучший по S). `?download=true` - скачивание файлом. `?mode=depth` - режим с глубиной: свои варианты
и ранжирование, у участков заполнены `depth_start`/`depth_end` (глубина верха габарита, м) и `k_depth`.
Сводка режима - `summary.depthVariants` в `GET /api/solutions/{id}`.

| `object_type` | Геометрия | Свойства |
|---|---|---|
| `heat_network` | LineString | `id`, `variant_id`, `start_node_id`, `end_node_id`, `flow_tph`, `diameter`, `length`, `laying_method` (`base`/`special`), `depth_start`, `depth_end` (null в 2D), `cost`; для спецпроходов ещё `special_restriction_types`, `k_spec` |
| `heat_chamber` | Point | `id`, `variant_id`, `diameter` (max ДУ примыканий, включая существующую линию), `cost`, `chamber_role` (`tie_in`/`branch`), `tie_in_line_id` |
| `technical_node` | Point | `id`, `variant_id` |
| `variant_summary` | null | `rank`, `construction_cost`, `chamber_construction_cost`, `existing_chamber_tie_in_count`, `existing_chamber_tie_in_cost`, `unconnected_penalty`, `calculated_cost`, `new_network_length`, `score`, `unconnected_oks_ids`; дополнительно `strategy`, `unconnected_reasons`, `validation_errors`, `notes` |

`start_node_id`/`end_node_id` - id точки ОКС или существующей камеры из входа (с исходным типом id)
либо id новой камеры/технического узла (`v1_ch_3`, `v1_tn_2`). Направление координат - от ОКС к врезке.
