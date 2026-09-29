package ru.lct.heat.solve;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.config.OpenApiConfig;

/**
 * Расчёт по заранее загруженному датасету: запуск в фоне, опрос статуса, выгрузка результата.
 * Статусы расчёта: PROCESSING - идёт, READY - готов (есть сводка и выгрузка), FAILED - ошибка
 * (текст в поле error; тот же статус ставится расчётам, прерванным перезапуском сервиса).
 */
@RestController
@Tag(name = OpenApiConfig.TAG_SOLVE_NAME, description = OpenApiConfig.TAG_SOLVE_DESCRIPTION)
public class SolveController {

    private static final int MAP_LIMIT = 50_000;

    private final SolveService service;
    private final SolutionRepository repository;
    private final HeatProperties props;
    private final ObjectMapper mapper;

    public SolveController(SolveService service, SolutionRepository repository, HeatProperties props, ObjectMapper mapper) {
        this.service = service;
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
    }

    @Operation(summary = "Запустить расчёт трассировки по датасету",
            description = "Ставит расчёт в очередь и сразу отдаёт его идентификатор: трассировка идёт в фоне, "
                    + "ответ ждать не нужно. Считаются оба режима трассировки (монтажный - если включён "
                    + "heat.installation.enabled): «Экономичный» - минимум показателя качества S, «Монтажный» - "
                    + "углы 45 и 90 по сетке квартала, вдоль улиц, без параллельных прокладок. По каждому режиму "
                    + "строится до трёх вариантов: e1..e3 у экономичного, m1..m3 у монтажного, вариант с номером 1 "
                    + "лучший по S. Показатель S = 0,7 * C / 25 000 000 + 0,3 * L / 100, где C - расчётная "
                    + "стоимость в рублях, L - длина новой сети в метрах; чем меньше S, тем лучше вариант. "
                    + "Если включён heat.depth.enabled, тем же расчётом считается режим с глубиной - отдельная "
                    + "выгрузка и своё ранжирование вариантов.\n\n"
                    + "Типовая последовательность вызовов:\n"
                    + "1. POST /api/datasets - загрузить GeoJSON с существующей сетью, точками подключения ОКС "
                    + "и ограничениями (здания, дороги, трамвай, газ, кабель, вода, ж/д, парки);\n"
                    + "2. GET /api/datasets/{id} - дождаться статуса READY;\n"
                    + "3. POST /api/datasets/{id}/solve - запустить расчёт и запомнить id расчёта из ответа;\n"
                    + "4. GET /api/solutions/{id} - опрашивать, пока статус PROCESSING, по READY забрать сводку;\n"
                    + "5. GET /api/solutions/{id}/geojson - выгрузить результат по разделу 7 ТЗ, при "
                    + "необходимости отдельным запросом ?mode=depth.\n\n"
                    + "Одновременно считаются heat.solver.workers расчётов, остальные ждут в очереди. Расчёт не "
                    + "стартует, пока датасет не в статусе READY (ответ 409). Если в датасете нет точек "
                    + "подключения ОКС или нет существующей сети heat_network, расчёт создаётся, но сразу "
                    + "завершается статусом FAILED с текстом ошибки.")
    @ApiResponse(responseCode = "202", description = "Расчёт принят и поставлен в очередь: id расчёта и статус",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(name = "Расчёт принят",
                            value = "{\n"
                                    + "  \"id\": \"3f2a1c7e-8b41-4d9a-9f3c-2b1e4c5d6a70\",\n"
                                    + "  \"status\": \"PROCESSING\"\n"
                                    + "}")))
    @ApiResponse(responseCode = "404", description = "Датасета с таким id нет", content = @Content)
    @ApiResponse(responseCode = "409", description = "Датасет не готов: разбор ещё идёт или завершился ошибкой",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(name = "Датасет не готов",
                            value = "{\"error\": \"датасет ещё не загружен: статус PROCESSING\"}")))
    @PostMapping("/api/datasets/{id}/solve")
    public ResponseEntity<Map<String, Object>> solve(
            @Parameter(description = "Идентификатор датасета (UUID) из ответа на загрузку GeoJSON. "
                    + "Датасет должен быть в статусе READY", required = true,
                    example = "b1d4c9a2-5e77-4a10-8c33-9f0b2d7e4451")
            @PathVariable UUID id,
            @Parameter(description = "Бюджет времени оптимизатора в секундах на каждый режим трассировки. "
                    + "0 или без параметра - берётся heat.solver.time-budget-s из конфигурации, значения "
                    + "больше 600 обрезаются до 600. От бюджета зависит число перезапусков жадного построения "
                    + "и глубина шлифовки, то есть качество S, но не состав выхода",
                    schema = @Schema(type = "number", format = "double", defaultValue = "0", maximum = "600"))
            @RequestParam(defaultValue = "0") double budgetSeconds) {
        try {
            UUID solution = service.start(id, Math.min(budgetSeconds, 600));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", solution, "status", "PROCESSING"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Список расчётов по датасету",
            description = "Все расчёты, запущенные по одному датасету, новые сверху. По каждому: id расчёта, "
                    + "mode выгрузки, статус (PROCESSING, READY, FAILED), время создания и завершения, текст "
                    + "ошибки. Сводки и геометрии здесь нет - за ними GET /api/solutions/{id} и "
                    + "GET /api/solutions/{id}/geojson. Если датасета нет или по нему не было расчётов, "
                    + "возвращается пустой список.")
    @ApiResponse(responseCode = "200", description = "Расчёты по датасету, новые сверху",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(name = "Готовый и упавший расчёт",
                            value = "[\n"
                                    + "  {\"id\": \"3f2a1c7e-8b41-4d9a-9f3c-2b1e4c5d6a70\", \"mode\": \"2d\", "
                                    + "\"status\": \"READY\",\n"
                                    + "   \"created_at\": \"2026-09-29T09:12:41.105+00:00\", "
                                    + "\"finished_at\": \"2026-09-29T09:13:02.555+00:00\", \"error\": null},\n"
                                    + "  {\"id\": \"7c0b55d1-2a64-49f8-bd0e-1c7fa9e3b012\", \"mode\": \"2d\", "
                                    + "\"status\": \"FAILED\",\n"
                                    + "   \"created_at\": \"2026-09-29T08:41:02.330+00:00\", "
                                    + "\"finished_at\": \"2026-09-29T08:41:02.512+00:00\",\n"
                                    + "   \"error\": \"в датасете нет точек подключения ОКС\"}\n"
                                    + "]")))
    @GetMapping("/api/datasets/{id}/solutions")
    public List<Map<String, Object>> solutions(
            @Parameter(description = "Идентификатор датасета (UUID)", required = true,
                    example = "b1d4c9a2-5e77-4a10-8c33-9f0b2d7e4451")
            @PathVariable UUID id) {
        return repository.listByDataset(id);
    }

    @Operation(summary = "Статус расчёта и сводка по вариантам",
            description = "Основной метод опроса: пока статус PROCESSING, поле summary пустое; по READY в "
                    + "summary лежит сводка расчёта, по FAILED в error - текст ошибки.\n\n"
                    + "Поля сводки: elapsedMs - время расчёта в миллисекундах; oksCount - число точек "
                    + "подключения ОКС; totalFlowTph - суммарный расход, т/ч; existingNetworkComponents - "
                    + "сколько несвязных частей у существующей сети; diagnostics - замечания по данным "
                    + "(тип ограничения без правил, ОКС вне своего полигона, камера не на линии сети, "
                    + "несвязные части существующей сети, выбранный угол сетки квартала); timeBudgetS - "
                    + "фактически использованный бюджет времени; modes - список режимов трассировки, у "
                    + "каждого mode (economy, installation), mode_name и свои варианты; variants - оставлено "
                    + "для совместимости и равно вариантам экономичного режима; depthModes и depthVariants - "
                    + "то же для режима с глубиной (есть, только если включён heat.depth.enabled).\n\n"
                    + "Поля варианта: variant_id (e1..e3, m1..m3), rank - место по S внутри режима, strategy - "
                    + "какой стратегией построен вариант, score - показатель S, calculated_cost - расчётная "
                    + "стоимость с учётом штрафов, construction_cost - стоимость строительства, pipe_cost - "
                    + "стоимость труб, chamber_construction_cost - стоимость новых камер, "
                    + "existing_chamber_tie_in_count - число врезок в существующие камеры, "
                    + "unconnected_penalty - штраф за неподключённые ОКС, new_network_length - длина новой "
                    + "сети в метрах, new_chambers - число новых камер, tie_ins - число врезок в "
                    + "существующую сеть, unconnected - сколько ОКС не подключено, validation_errors - "
                    + "нарушения правил самопроверки, manual_review - места, требующие ручной проработки "
                    + "(в основном по глубине), notes - пояснения по построению.")
    @ApiResponse(responseCode = "200", description = "Расчёт найден: статус и, если он готов, сводка",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                    @ExampleObject(name = "Расчёт идёт", value = "{\n"
                            + "  \"id\": \"3f2a1c7e-8b41-4d9a-9f3c-2b1e4c5d6a70\",\n"
                            + "  \"dataset_id\": \"b1d4c9a2-5e77-4a10-8c33-9f0b2d7e4451\",\n"
                            + "  \"mode\": \"2d\",\n"
                            + "  \"status\": \"PROCESSING\",\n"
                            + "  \"created_at\": \"2026-09-29T09:12:41.105+00:00\",\n"
                            + "  \"finished_at\": null,\n"
                            + "  \"error\": null,\n"
                            + "  \"summary\": null\n"
                            + "}"),
                    @ExampleObject(name = "Расчёт готов", value = "{\n"
                            + "  \"id\": \"3f2a1c7e-8b41-4d9a-9f3c-2b1e4c5d6a70\",\n"
                            + "  \"dataset_id\": \"b1d4c9a2-5e77-4a10-8c33-9f0b2d7e4451\",\n"
                            + "  \"mode\": \"2d\",\n"
                            + "  \"status\": \"READY\",\n"
                            + "  \"created_at\": \"2026-09-29T09:12:41.105+00:00\",\n"
                            + "  \"finished_at\": \"2026-09-29T09:13:02.555+00:00\",\n"
                            + "  \"error\": null,\n"
                            + "  \"summary\": {\n"
                            + "    \"elapsedMs\": 21450,\n"
                            + "    \"oksCount\": 17,\n"
                            + "    \"totalFlowTph\": 488.72,\n"
                            + "    \"existingNetworkComponents\": 2,\n"
                            + "    \"diagnostics\": [\"существующая сеть состоит из 2 несвязных частей\",\n"
                            + "      \"монтажный режим: сетка квартала под 20.5°\"],\n"
                            + "    \"timeBudgetS\": 60.0,\n"
                            + "    \"modes\": [\n"
                            + "      {\"mode\": \"economy\", \"mode_name\": \"Экономичный\", \"variants\": [\n"
                            + "        {\"variant_id\": \"e1\", \"rank\": 1, "
                            + "\"strategy\": \"largest-flow-first+polish\", \"score\": 13.66261,\n"
                            + "         \"calculated_cost\": 274393318, \"construction_cost\": 274393318,\n"
                            + "         \"pipe_cost\": 224393318, \"chamber_construction_cost\": 50000000,\n"
                            + "         \"existing_chamber_tie_in_count\": 0, \"unconnected_penalty\": 0,\n"
                            + "         \"new_network_length\": 1993.2, \"new_chambers\": 10, "
                            + "\"tie_ins\": 4, \"unconnected\": 0,\n"
                            + "         \"validation_errors\": [], \"manual_review\": [], \"notes\": []}\n"
                            + "      ]},\n"
                            + "      {\"mode\": \"installation\", \"mode_name\": \"Монтажный\", \"variants\": [\n"
                            + "        {\"variant_id\": \"m1\", \"rank\": 1, \"strategy\": \"nearest-first\", "
                            + "\"score\": 15.01505,\n"
                            + "         \"calculated_cost\": 305380450, \"construction_cost\": 305380450,\n"
                            + "         \"pipe_cost\": 249380450, \"chamber_construction_cost\": 51000000,\n"
                            + "         \"existing_chamber_tie_in_count\": 1, \"unconnected_penalty\": 0,\n"
                            + "         \"new_network_length\": 2154.8, \"new_chambers\": 11, "
                            + "\"tie_ins\": 4, \"unconnected\": 0,\n"
                            + "         \"validation_errors\": [], \"manual_review\": [], \"notes\": []}\n"
                            + "      ]}\n"
                            + "    ],\n"
                            + "    \"variants\": [\n"
                            + "      {\"variant_id\": \"e1\", \"rank\": 1, "
                            + "\"strategy\": \"largest-flow-first+polish\", \"score\": 13.66261,\n"
                            + "       \"calculated_cost\": 274393318, \"construction_cost\": 274393318,\n"
                            + "       \"pipe_cost\": 224393318, \"chamber_construction_cost\": 50000000,\n"
                            + "       \"existing_chamber_tie_in_count\": 0, \"unconnected_penalty\": 0,\n"
                            + "       \"new_network_length\": 1993.2, \"new_chambers\": 10, "
                            + "\"tie_ins\": 4, \"unconnected\": 0,\n"
                            + "       \"validation_errors\": [], \"manual_review\": [], \"notes\": []}\n"
                            + "    ],\n"
                            + "    \"depthModes\": [\n"
                            + "      {\"mode\": \"economy\", \"mode_name\": \"Экономичный\", \"variants\": [\n"
                            + "        {\"variant_id\": \"e1\", \"rank\": 1, "
                            + "\"strategy\": \"largest-flow-first+polish\", \"score\": 13.87955,\n"
                            + "         \"calculated_cost\": 282140900, \"construction_cost\": 282140900,\n"
                            + "         \"pipe_cost\": 232140900, \"chamber_construction_cost\": 50000000,\n"
                            + "         \"existing_chamber_tie_in_count\": 0, \"unconnected_penalty\": 0,\n"
                            + "         \"new_network_length\": 1993.2, \"new_chambers\": 10, "
                            + "\"tie_ins\": 4, \"unconnected\": 0,\n"
                            + "         \"validation_errors\": [],\n"
                            + "         \"manual_review\": [\"ребро 12: у gas_pipeline не выполнить "
                            + "вертикальное правило с уклоном ≤ 0.10 - требует ручной проработки\"],\n"
                            + "         \"notes\": []}\n"
                            + "      ]}\n"
                            + "    ],\n"
                            + "    \"depthVariants\": [\n"
                            + "      {\"variant_id\": \"e1\", \"rank\": 1, "
                            + "\"strategy\": \"largest-flow-first+polish\", \"score\": 13.87955,\n"
                            + "       \"calculated_cost\": 282140900, \"construction_cost\": 282140900,\n"
                            + "       \"pipe_cost\": 232140900, \"chamber_construction_cost\": 50000000,\n"
                            + "       \"existing_chamber_tie_in_count\": 0, \"unconnected_penalty\": 0,\n"
                            + "       \"new_network_length\": 1993.2, \"new_chambers\": 10, "
                            + "\"tie_ins\": 4, \"unconnected\": 0,\n"
                            + "       \"validation_errors\": [],\n"
                            + "       \"manual_review\": [\"ребро 12: у gas_pipeline не выполнить "
                            + "вертикальное правило с уклоном ≤ 0.10 - требует ручной проработки\"],\n"
                            + "       \"notes\": []}\n"
                            + "    ]\n"
                            + "  }\n"
                            + "}"),
                    @ExampleObject(name = "Расчёт не удался", value = "{\n"
                            + "  \"id\": \"7c0b55d1-2a64-49f8-bd0e-1c7fa9e3b012\",\n"
                            + "  \"dataset_id\": \"b1d4c9a2-5e77-4a10-8c33-9f0b2d7e4451\",\n"
                            + "  \"mode\": \"2d\",\n"
                            + "  \"status\": \"FAILED\",\n"
                            + "  \"created_at\": \"2026-09-29T08:41:02.330+00:00\",\n"
                            + "  \"finished_at\": \"2026-09-29T08:41:02.512+00:00\",\n"
                            + "  \"error\": \"в датасете нет точек подключения ОКС\",\n"
                            + "  \"summary\": null\n"
                            + "}")}))
    @ApiResponse(responseCode = "404", description = "Расчёта с таким id нет", content = @Content)
    @GetMapping("/api/solutions/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @Parameter(description = "Идентификатор расчёта (UUID) из ответа на запуск расчёта, "
                    + "не идентификатор датасета", required = true,
                    example = "3f2a1c7e-8b41-4d9a-9f3c-2b1e4c5d6a70")
            @PathVariable UUID id) {
        Map<String, Object> s = repository.find(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    @Operation(summary = "Выгрузить результат расчёта: GeoJSON по разделу 7 ТЗ",
            description = "Один FeatureCollection в WGS 84 со всеми вариантами всех посчитанных режимов: "
                    + "варианты различаются по variant_id (e1..e3 - экономичный режим, m1..m3 - монтажный), "
                    + "у каждого варианта есть объект variant_summary с routing_mode и routing_mode_name.\n\n"
                    + "Типы объектов в выгрузке: heat_network - участок новой сети (LineString) с полями "
                    + "start_node_id, end_node_id, flow_tph, diameter, length, laying_method (base или "
                    + "special для спецперехода), depth_start, depth_end, cost; heat_chamber - камера "
                    + "(Point) с chamber_role = tie_in (врезка в существующую сеть) или branch "
                    + "(разветвление); technical_node - узел на границе спецперехода (Point); crossing - "
                    + "пересечение с существующей коммуникацией (только в режиме с глубиной); "
                    + "variant_summary - сводка варианта (геометрия null): score, calculated_cost, "
                    + "new_network_length, unconnected_oks_ids, validation_errors, manual_review и прочее.\n\n"
                    + "Ответ 404 приходит и когда расчёта нет, и когда он ещё не готов или упал, и когда "
                    + "запрошен mode=depth, а режим с глубиной был выключен: сначала стоит посмотреть статус "
                    + "через GET /api/solutions/{id}.")
    @ApiResponse(responseCode = "200", description = "Готовая выгрузка в формате GeoJSON",
            content = @Content(mediaType = "application/geo+json", examples = {
                    @ExampleObject(name = "Режим 2d", value = "{\n"
                            + "  \"type\": \"FeatureCollection\",\n"
                            + "  \"name\": \"heat_routing_all_modes\",\n"
                            + "  \"features\": [\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"e1_ch_3\", \"object_type\": \"heat_chamber\", "
                            + "\"variant_id\": \"e1\",\n"
                            + "       \"diameter\": 400, \"cost\": 5000000, \"chamber_role\": \"tie_in\", "
                            + "\"tie_in_line_id\": 412},\n"
                            + "     \"geometry\": {\"type\": \"Point\", "
                            + "\"coordinates\": [37.638462, 55.697639]}},\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"e1_net_1\", \"object_type\": \"heat_network\", "
                            + "\"variant_id\": \"e1\",\n"
                            + "       \"start_node_id\": 17, \"end_node_id\": \"e1_ch_3\", "
                            + "\"flow_tph\": 12.4, \"diameter\": 80,\n"
                            + "       \"length\": 110.67, \"laying_method\": \"base\", "
                            + "\"depth_start\": null, \"depth_end\": null,\n"
                            + "       \"cost\": 9244265},\n"
                            + "     \"geometry\": {\"type\": \"LineString\", \"coordinates\": "
                            + "[[37.639538, 55.696852], [37.638462, 55.697639]]}},\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"e1_tn_2\", "
                            + "\"object_type\": \"technical_node\", \"variant_id\": \"e1\"},\n"
                            + "     \"geometry\": {\"type\": \"Point\", "
                            + "\"coordinates\": [37.638544, 55.697579]}},\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"e1_summary\", "
                            + "\"object_type\": \"variant_summary\", \"variant_id\": \"e1\",\n"
                            + "       \"rank\": 1, \"routing_mode\": \"economy\", "
                            + "\"routing_mode_name\": \"Экономичный\",\n"
                            + "       \"construction_cost\": 274393318, "
                            + "\"chamber_construction_cost\": 50000000,\n"
                            + "       \"existing_chamber_tie_in_count\": 0, "
                            + "\"existing_chamber_tie_in_cost\": 0,\n"
                            + "       \"unconnected_penalty\": 0, \"calculated_cost\": 274393318, "
                            + "\"new_network_length\": 1993.2,\n"
                            + "       \"score\": 13.6626, \"manual_review\": [], "
                            + "\"unconnected_oks_ids\": [],\n"
                            + "       \"strategy\": \"largest-flow-first+polish\", "
                            + "\"unconnected_reasons\": {},\n"
                            + "       \"validation_errors\": [], \"notes\": []},\n"
                            + "     \"geometry\": null}\n"
                            + "  ]\n"
                            + "}"),
                    @ExampleObject(name = "Режим depth", value = "{\n"
                            + "  \"type\": \"FeatureCollection\",\n"
                            + "  \"name\": \"heat_routing_depth\",\n"
                            + "  \"features\": [\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"e1_net_4\", \"object_type\": \"heat_network\", "
                            + "\"variant_id\": \"e1\",\n"
                            + "       \"start_node_id\": \"e1_tn_2\", \"end_node_id\": \"e1_ch_3\", "
                            + "\"flow_tph\": 47.7,\n"
                            + "       \"diameter\": 150, \"length\": 8.44, "
                            + "\"laying_method\": \"special\", \"depth_start\": 3.5,\n"
                            + "       \"depth_end\": 3.5, \"cost\": 1168754, "
                            + "\"special_restriction_types\": \"gas_pipeline\",\n"
                            + "       \"k_spec\": 1.25, \"k_depth\": 1.05},\n"
                            + "     \"geometry\": {\"type\": \"LineString\", \"coordinates\": "
                            + "[[37.638544, 55.697579, -3.5],\n"
                            + "       [37.638462, 55.697639, -3.5]]}},\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"e1_cr_1_12\", \"object_type\": \"crossing\", "
                            + "\"variant_id\": \"e1\",\n"
                            + "       \"restriction_type\": \"gas_pipeline\", \"passage\": \"below\", "
                            + "\"passage_name\": \"снизу\",\n"
                            + "       \"depth\": 3.5, \"z\": -3.5, \"vertical_clearance\": 0.3,\n"
                            + "       \"vertical_clearance_required\": 0.2, \"diameter\": 150},\n"
                            + "     \"geometry\": {\"type\": \"Point\", "
                            + "\"coordinates\": [37.638503, 55.697609]}}\n"
                            + "  ]\n"
                            + "}")}))
    @ApiResponse(responseCode = "404", description = "Расчёта нет, он ещё не готов или в этом режиме "
            + "выгрузки не было", content = @Content)
    @GetMapping(value = "/api/solutions/{id}/geojson", produces = "application/geo+json")
    public ResponseEntity<String> geojson(
            @Parameter(description = "Идентификатор расчёта (UUID), статус которого стал READY", required = true,
                    example = "3f2a1c7e-8b41-4d9a-9f3c-2b1e4c5d6a70")
            @PathVariable UUID id,
            @Parameter(description = "true - отдать ответ файлом-вложением (заголовок Content-Disposition, "
                    + "имя вида heat-routing-<id>-2d.geojson или -depth.geojson), false - отдать тело ответа "
                    + "как есть, для просмотра в браузере или на карте",
                    schema = @Schema(type = "boolean", defaultValue = "false"))
            @RequestParam(defaultValue = "false") boolean download,
            @Parameter(description = "Какую выгрузку отдать. 2d - основная: плановая трассировка, координаты "
                    + "только lon/lat, поля depth_start и depth_end пустые. depth - режим с глубиной: у "
                    + "координат появляется отметка Z (поверхность земли Z = 0, глубже - меньше), у участков "
                    + "заполнены depth_start, depth_end и k_depth, добавлены точки crossing с passage "
                    + "(above или below) и vertical_clearance, а проблемные места собраны в разделе "
                    + "manual_review сводки варианта. Варианты режима с глубиной ранжируются отдельно, "
                    + "поэтому порядок e1..e3 может отличаться от режима 2d",
                    schema = @Schema(type = "string", defaultValue = "2d", allowableValues = {"2d", "depth"}))
            @RequestParam(defaultValue = "2d") String mode) {
        boolean depth = "depth".equalsIgnoreCase(mode);
        String result = repository.result(id, depth);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType("application/geo+json"));
        if (download) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heat-routing-" + id + (depth ? "-depth" : "-2d") + ".geojson\"");
        }
        return b.body(result);
    }

    @Operation(summary = "Входные объекты датасета в WGS 84 для карты",
            description = "Отдаёт то, что было принято при загрузке: существующую сеть heat_network, камеры "
                    + "heat_chamber, источники source, точки подключения oks_connection_point и ограничения "
                    + "restriction. Метод сделан для отрисовки подложки на карте, а не для выгрузки данных: "
                    + "объекты сети, камеры, источники и ОКС отдаются все, а ограничения - только в районе "
                    + "расчёта (габарит всех остальных объектов датасета, расширенный на буфер "
                    + "heat.geometry.search-area-buffer-m, по умолчанию 200 м), всего отдаётся не больше "
                    + "50 000 объектов.\n\n"
                    + "В properties каждого объекта лежат исходные свойства из загруженного файла без "
                    + "изменений (id, object_type, restriction_type, flow_tph у ОКС, diameter у сети и "
                    + "прочие атрибуты входа), геометрия перепроецирована в WGS 84 с точностью 7 знаков. "
                    + "Для неизвестного датасета возвращается FeatureCollection без объектов.")
    @ApiResponse(responseCode = "200", description = "Входные объекты в формате GeoJSON",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Сеть, ОКС и ограничение", value = "{\n"
                            + "  \"type\": \"FeatureCollection\",\n"
                            + "  \"features\": [\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": 412, \"object_type\": \"heat_network\", "
                            + "\"diameter\": 400},\n"
                            + "     \"geometry\": {\"type\": \"LineString\", \"coordinates\": "
                            + "[[37.637239, 55.695914], [37.638462, 55.697639]]}},\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": 17, "
                            + "\"object_type\": \"oks_connection_point\", \"flow_tph\": 12.4},\n"
                            + "     \"geometry\": {\"type\": \"Point\", "
                            + "\"coordinates\": [37.639538, 55.696852]}},\n"
                            + "    {\"type\": \"Feature\",\n"
                            + "     \"properties\": {\"id\": \"r-1024\", \"object_type\": \"restriction\", "
                            + "\"restriction_type\": \"road\"},\n"
                            + "     \"geometry\": {\"type\": \"Polygon\", \"coordinates\": "
                            + "[[[37.638577, 55.700519], [37.638755, 55.700481],\n"
                            + "       [37.635778, 55.695954], [37.638577, 55.700519]]]}}\n"
                            + "  ]\n"
                            + "}")))
    @GetMapping(value = "/api/datasets/{id}/geojson", produces = "application/geo+json")
    public Map<String, Object> input(
            @Parameter(description = "Идентификатор датасета (UUID), объекты которого нужно показать",
                    required = true, example = "b1d4c9a2-5e77-4a10-8c33-9f0b2d7e4451")
            @PathVariable UUID id) throws IOException {
        List<Object> features = new ArrayList<>();
        for (Map<String, Object> row : repository.inputFeatures(id, props.getGeometry().getSearchAreaBufferM(), MAP_LIMIT)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("properties", mapper.readTree((String) row.get("props")));
            f.put("geometry", mapper.readTree((String) row.get("geometry")));
            features.add(f);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        return fc;
    }
}
