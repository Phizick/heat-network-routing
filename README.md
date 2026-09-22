# heat-routing

Сервис моделирования трасс подключения к тепловым сетям (кейс ЛЦТ-2026, ДИТ Москвы).

По входному GeoJSON (существующая теплосеть, точки подключения ОКС, пространственные ограничения)
сервис строит древовидную сеть подключения, подбирает диаметры, считает стоимость и выдаёт
до 3 вариантов, отранжированных по показателю `S`. Правила и параметры задачи: [docs/rules.md](docs/rules.md).

## Статус

| Этап | Статус |
|---|---|
| Потоковая загрузка GeoJSON до 3 ГБ, диагностика данных, EPSG:32637, PostGIS | готово |
| Топология существующей сети, врезка по правилам R-TIE | готово |
| Маршрут: граф видимости + A*, углы ≤ 90°, отступы по ДУ, спецпереходы дорог и коммуникаций | готово |
| Дерево: объединение ОКС, камеры ветвления, лес с несколькими врезками | готово |
| ДУ по расходу, монотонность, предельная длина, перепроверка отступов | готово |
| Стоимость, S, до 3 разных вариантов, выгрузка GeoJSON по ТЗ | готово |
| Независимая проверка правил каждого варианта | готово |
| Карта для просмотра (загрузка, расчёт, варианты) | готово |
| Режим с глубиной | нет (дополнительная задача) |

Конкурсный датасет: подключены все 17 ОКС, S ≈ 13,6, нарушений правил 0.

Алгоритм и допущения: [docs/architecture.md](docs/architecture.md).

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

## Использование

Карта: http://localhost:8090/ - загрузить GeoJSON, «Построить сеть», выбрать вариант, скачать результат.

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/api/datasets` | загрузить GeoJSON (multipart, поле `file`), разбор в фоне |
| GET | `/api/datasets/{id}` | статус и отчёт диагностики |
| POST | `/api/datasets/{id}/solve` | запустить расчёт |
| GET | `/api/solutions/{id}` | статус и сводка по вариантам |
| GET | `/api/solutions/{id}/geojson` | выход в формате ТЗ |

Подробнее: [docs/api.md](docs/api.md).

## Структура

```
src/main/java/ru/lct/heat/
  config/    параметры (application.yml, префикс heat): таблицы ДУ и ограничений, стоимости, веса S
  ingest/    потоковое чтение GeoJSON, проверка объектов, отчёт диагностики
  dataset/   REST загрузок, хранение в PostGIS
  model/     правила (Rules) и входной сценарий
  route/     препятствия по ДУ, граф видимости, A*, цели врезки
  tree/      сеть-лес, жадная вставка и локальный поиск, ДУ и L_max, стоимость, валидатор
  solve/     REST расчёта, выгрузка GeoJSON
src/main/resources/static/        карта (Leaflet)
src/main/resources/db/migration/  схема БД (Flyway)
docs/rules.md                     правила задачи из ТЗ с кодами R-...
```

## CI/CD

Локальный GitLab `lct-heat/heat-routing`: `test` (mvn test) → `build` (образ `heat-routing:<sha>` в Docker хоста, без registry) →
`deploy` (`docker compose -p heat-routing up -d` на хосте раннера).

## Документация

Хук `.claude/hooks/docs-sync.sh` после правок кода в фоне запускает Claude, который обновляет
README.md и docs/ и коммитит только их. Лог: `.claude/docs-sync.log`. Хук срабатывает, когда
Claude Code открыт в папке репозитория.
