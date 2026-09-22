# heat-routing

Сервис моделирования трасс подключения к тепловым сетям (кейс ЛЦТ-2026, ДИТ Москвы).

По входному GeoJSON (существующая теплосеть, точки подключения ОКС, пространственные ограничения)
сервис строит древовидную сеть подключения, подбирает диаметры, считает стоимость и выдаёт
до 3 вариантов, отранжированных по показателю `S`. Правила и параметры задачи: [docs/rules.md](docs/rules.md).

## Статус

| Этап | Статус |
|---|---|
| Потоковая загрузка GeoJSON до 3 ГБ, проверка данных, перепроецирование в EPSG:32637, запись в PostGIS | готово |
| Восстановление топологии существующей сети | план |
| Маршрут одной ОКС до сети (граф видимости + A*, углы ≤ 90°, отступы) | план |
| Объединение ОКС в дерево, подбор ДУ, предельная длина | план |
| Стоимость, `S`, варианты, выгрузка GeoJSON | план |
| Режим с глубиной | план (опционально) |

Подробнее: [docs/architecture.md](docs/architecture.md).

## Стек

Java 11, Spring Boot 2.6.3, springdoc-openapi-ui 1.7.0, JTS, proj4j, PostgreSQL 16 + PostGIS, Flyway,
docker-compose (совместим с 1.29.2).

## Запуск

```bash
docker compose -p heat-routing up -d --build
```

- Swagger UI: http://localhost:8090/swagger-ui.html
- Health: http://localhost:8090/actuator/health

Порт меняется переменной `APP_PORT`, пароль БД `DB_PASSWORD`.

Тесты без локального Maven:

```bash
docker run --rm -v "$PWD":/src -v heat-m2:/root/.m2 -w /src maven:3.8.8-eclipse-temurin-11 mvn -B test
```

## API

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/api/datasets` | загрузить GeoJSON (multipart, поле `file`), ответ `202` с `id`, разбор в фоне |
| GET | `/api/datasets` | список загрузок |
| GET | `/api/datasets/{id}` | статус (`PROCESSING` / `READY` / `FAILED`) и отчёт диагностики |
| GET | `/api/datasets/{id}/summary` | состав данных в БД: счётчики по типам, длина сети, охват |

```bash
curl -F "file=@src/test/resources/lct-dataset.geojson" http://localhost:8090/api/datasets
```

Подробнее: [docs/api.md](docs/api.md).

## Структура

```
src/main/java/ru/lct/heat/
  config/    параметры (application.yml, префикс heat), пул фоновой обработки
  geo/       перепроецирование WGS 84 ↔ EPSG:32637
  ingest/    потоковое чтение GeoJSON, проверка объектов, отчёт диагностики
  dataset/   REST API загрузок, хранение в PostGIS
src/main/resources/db/migration/   схема БД (Flyway)
docs/rules.md                      правила задачи из ТЗ с кодами R-...
```

## CI/CD

Локальный GitLab `lct-heat/heat-routing`: `test` (mvn test) → `build` (образ `heat-routing:<sha>` в Docker хоста, без registry) →
`deploy` (`docker compose -p heat-routing up -d` на хосте раннера).

## Документация

Хук `.claude/hooks/docs-sync.sh` после правок кода в фоне запускает Claude, который обновляет
README.md и docs/ и коммитит только их. Лог: `.claude/docs-sync.log`. Хук срабатывает, когда
Claude Code открыт в папке репозитория.
