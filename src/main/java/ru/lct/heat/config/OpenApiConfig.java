package ru.lct.heat.config;

import java.util.List;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание API для Swagger UI (/swagger-ui.html) и схемы OpenAPI (/v3/api-docs).
 * Имена и описания групп операций лежат здесь константами, а контроллеры ссылаются на них
 * в своих аннотациях тега: так в схеме не появится двух тегов с одним именем и разным описанием,
 * то есть и дублирующихся групп в Swagger UI.
 * Серверы намеренно не заданы: сервис разворачивают на разных машинах, базовый адрес берётся из запроса.
 */
@Configuration
public class OpenApiConfig {

    /** Группа операций «файл на входе - файл на выходе» (RoutingController). */
    public static final String TAG_ROUTING_NAME = "Трассировка одним запросом";

    public static final String TAG_ROUTING_DESCRIPTION =
            "Загрузка GeoJSON в теле запроса и готовый результат в ответе: без базы, без статусов и без опроса.\n"
            + "Три точки входа - экономичный режим, монтажный режим и оба режима в одном файле.\n"
            + "Путь для файлов примерно до сотни мегабайт, когда ответ ждут в том же соединении.\n"
            + "Показатель качества S = 0,7 * C / 25 000 000 + 0,3 * L / 100, где C - расчётная стоимость\n"
            + "в рублях (calculated_cost), L - длина новой сети в метрах (new_network_length);\n"
            + "чем меньше S, тем лучше вариант.";

    /** Группа операций приёма входного GeoJSON (DatasetController). */
    public static final String TAG_DATASETS_NAME = "Датасеты";

    public static final String TAG_DATASETS_DESCRIPTION =
            "Приём входного GeoJSON размером до 3 ГБ: файл разбирается в фоне в PostGIS, запрос сразу отдаёт id.\n"
            + "По id доступны статус загрузки, отчёт диагностики (что принято, что отклонено и почему)\n"
            + "и состав данных - счётчики по типам объектов, длина существующей сети и охват.\n"
            + "\n"
            + "Вход - GeoJSON FeatureCollection в WGS 84 с существующей теплосетью (object_type = heat_network),\n"
            + "камерами (heat_chamber), источниками (source), точками подключения ОКС (oks_connection_point,\n"
            + "расход flow_tph в т/ч) и пространственными ограничениями (restriction: здания, дороги, трамвай,\n"
            + "газ, кабель, вода, ж/д, парки).\n"
            + "\n"
            + "Порядок работы: загрузить файл (POST /api/datasets), дождаться status = READY\n"
            + "(GET /api/datasets/{id}), посмотреть состав данных (GET /api/datasets/{id}/summary)\n"
            + "и только затем запускать расчёт по этому id.";

    /** Группа операций расчёта по датасету и выгрузки результата (SolveController). */
    public static final String TAG_SOLVE_NAME = "Расчёт";

    public static final String TAG_SOLVE_DESCRIPTION =
            "Расчёты по загруженному датасету: запуск в фоне, список расчётов, статус со сводкой по вариантам\n"
            + "(S, стоимость, длина, нарушения правил) и выгрузка результата в GeoJSON по разделу 7 ТЗ -\n"
            + "в плане (mode=2d) или с глубиной (mode=depth). Здесь же входные объекты датасета для карты.\n"
            + "Статусы расчёта: PROCESSING - идёт, READY - готов, FAILED - ошибка (текст в поле error).";

    private static final String DESCRIPTION =
            "Сервис подбирает трассы подключения объектов капитального строительства (ОКС) к существующей\n"
            + "тепловой сети: строит новые участки, ставит камеры, подбирает диаметры, считает стоимость и длину,\n"
            + "проверяет правила прокладки и выдаёт несколько вариантов на выбор.\n"
            + "\n"
            + "### Что подаётся на вход\n"
            + "Один файл GeoJSON FeatureCollection: существующая теплосеть (heat_network), точки подключения ОКС\n"
            + "с расходом в т/ч и пространственные ограничения - здания, дороги, трамвайные пути, газопроводы,\n"
            + "силовые кабели, водные объекты, железные дороги, парки и социальные зоны. Координаты в WGS 84,\n"
            + "расчёт идёт в метрической проекции (heat.crs.calc, по умолчанию EPSG:32637), выход снова в WGS 84.\n"
            + "\n"
            + "### Что возвращается\n"
            + "GeoJSON FeatureCollection по разделу 7 ТЗ: все варианты лежат в одной коллекции и различаются\n"
            + "свойством variant_id. Тип объекта задаёт свойство object_type:\n"
            + "\n"
            + "- `heat_network` - участок новой сети: diameter, flow_tph, length, cost, laying_method\n"
            + "  (base или special), depth_start и depth_end, на спецпереходах дополнительно k_spec\n"
            + "  и special_restriction_types (типы ограничений строкой через запятую), в режиме с глубиной\n"
            + "  ещё k_depth, связи по start_node_id и end_node_id;\n"
            + "- `heat_chamber` - камера: chamber_role (tie_in - врезка в существующую сеть, branch -\n"
            + "  ответвление), diameter, cost, у врезки ещё tie_in_line_id - id существующей линии;\n"
            + "- `technical_node` - технический узел на границе спецперехода, режет участок на части;\n"
            + "- `crossing` - пересечение с существующей коммуникацией, появляется в режиме с глубиной:\n"
            + "  restriction_type, passage (above - сверху, below - снизу), passage_name, depth, z,\n"
            + "  vertical_clearance и vertical_clearance_required;\n"
            + "- `variant_summary` - сводка варианта, объект без геометрии: score (показатель S),\n"
            + "  calculated_cost, construction_cost, chamber_construction_cost, existing_chamber_tie_in_count\n"
            + "  и existing_chamber_tie_in_cost, unconnected_penalty, new_network_length, rank, routing_mode\n"
            + "  и routing_mode_name, strategy, validation_errors (нарушения правил самопроверки),\n"
            + "  manual_review (места, где выдержать нормативную глубину или зазор не удалось),\n"
            + "  unconnected_oks_ids, unconnected_reasons и notes.\n"
            + "\n"
            + "### Показатель качества S\n"
            + "S = 0,7 * C / 25 000 000 + 0,3 * L / 100, где C - расчётная стоимость в рублях (calculated_cost),\n"
            + "L - длина новой сети в метрах (new_network_length). Чем меньше S, тем лучше вариант, по S варианты\n"
            + "и ранжируются внутри своего режима: rank = 1 - лучший.\n"
            + "\n"
            + "### Два режима трассировки\n"
            + "- `economy` (Экономичный) - минимум показателя S, трасса идёт свободно, лишь бы не нарушать\n"
            + "  ограничения. Идентификаторы вариантов e1, e2, e3.\n"
            + "- `installation` (Монтажный) - углы 45 и 90 градусов по сетке квартала, прокладка вдоль улиц,\n"
            + "  без параллельных прокладок рядом с существующей сетью. Идентификаторы m1, m2, m3.\n"
            + "\n"
            + "Запрос /api/routing/all отдаёт оба режима в одном файле, у каждого варианта проставлен routing_mode.\n"
            + "\n"
            + "### Блок summary\n"
            + "В ответе одним запросом рядом с features лежит поле summary: elapsedMs, featuresRead,\n"
            + "featuresStored, featuresRejected, issueCounts (ключ «severity:code»), diagnostics, timeBudgetS\n"
            + "и modes - по одной сводке на режим.\n"
            + "\n"
            + "У асинхронного расчёта сводка приходит в GET /api/solutions/{id}, поле summary: elapsedMs,\n"
            + "oksCount, totalFlowTph, existingNetworkComponents, diagnostics, timeBudgetS, modes и variants\n"
            + "(варианты экономичного режима, оставлены для совместимости), а при включённом режиме с глубиной\n"
            + "ещё depthModes и depthVariants. Диагностика разбора входа здесь не повторяется - она лежит\n"
            + "в отчёте датасета (GET /api/datasets/{id}, поле report).\n"
            + "\n"
            + "В сводке по режиму есть mode и mode_name, а у каждого варианта - variant_id, rank, strategy,\n"
            + "score, calculated_cost, construction_cost, pipe_cost, chamber_construction_cost,\n"
            + "existing_chamber_tie_in_count, unconnected_penalty, new_network_length, new_chambers, tie_ins,\n"
            + "unconnected, validation_errors, manual_review и notes.\n"
            + "\n"
            + "### Одним запросом или асинхронно\n"
            + "- Одним запросом (POST /api/routing/economy, /api/routing/installation, /api/routing/all): файл\n"
            + "  уходит в теле запроса, в ответе сразу готовый GeoJSON, ничего не сохраняется и состояние не нужно.\n"
            + "  Путь для файлов примерно до сотни мегабайт, когда ответ можно дождаться в том же соединении.\n"
            + "- Асинхронно (POST /api/datasets, затем POST /api/datasets/{id}/solve, GET /api/solutions/{id}\n"
            + "  и GET /api/solutions/{id}/geojson): файл до 3 ГБ разбирается в фоне в PostGIS, расчёт запускается\n"
            + "  отдельным запросом, результат забирается, когда статус станет READY. Путь для больших выгрузок,\n"
            + "  повторных расчётов по одним и тем же данным и для показа входных объектов на карте.\n"
            + "\n"
            + "Параметр budgetSeconds задаёт бюджет времени оптимизатора в секундах на каждый режим: 0 или\n"
            + "отсутствие параметра - значение из конфига heat.solver.time-budget-s (по умолчанию 45),\n"
            + "максимум 600 секунд.\n"
            + "\n"
            + "### Режим с глубиной\n"
            + "Выгрузка GET /api/solutions/{id}/geojson?mode=depth - отдельный результат со своим ранжированием:\n"
            + "координаты содержат отметку Z (поверхность земли Z = 0, глубже - меньше), у участков заполнены\n"
            + "depth_start, depth_end и k_depth, добавлены точки crossing с passage и vertical_clearance, а места,\n"
            + "где нормативную глубину или вертикальный зазор выдержать не удалось, перечислены в разделе\n"
            + "manual_review сводки варианта.\n"
            + "\n"
            + "### Пример\n"
            + "```\n"
            + "curl -F \"file=@input.geojson\" \"$HOST/api/routing/all?budgetSeconds=60\" -o result.geojson\n"
            + "```\n";

    /** Титул, развёрнутое описание сервиса и порядок групп операций в Swagger UI. */
    @Bean
    public OpenAPI heatRoutingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Сервис моделирования трасс подключения к тепловым сетям")
                        .version("1.2.0")
                        .description(DESCRIPTION))
                .tags(List.of(
                        new Tag().name(TAG_ROUTING_NAME).description(TAG_ROUTING_DESCRIPTION),
                        new Tag().name(TAG_DATASETS_NAME).description(TAG_DATASETS_DESCRIPTION),
                        new Tag().name(TAG_SOLVE_NAME).description(TAG_SOLVE_DESCRIPTION)));
    }
}
