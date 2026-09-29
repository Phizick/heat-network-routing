package ru.lct.heat.solve;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.config.HeatProperties.Mode;
import ru.lct.heat.config.OpenApiConfig;
import ru.lct.heat.geo.Projection;
import ru.lct.heat.ingest.IngestProcessor;
import ru.lct.heat.ingest.IngestReport;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.model.ScenarioAssembler;
import ru.lct.heat.route.Context;

/**
 * Расчёт «файл на входе - файл на выходе», без базы и без ожидания статуса.
 * Три точки входа: экономичный режим, монтажный режим и оба сразу.
 *
 * <p>Вход - GeoJSON FeatureCollection в EPSG:4326: существующая теплосеть, точки подключения ОКС
 * с расходом flow_tph и пространственные ограничения. Выход - GeoJSON по разделу 7 ТЗ
 * (участки heat_network, камеры heat_chamber, узлы technical_node, сводки variant_summary)
 * плюс поле summary в конце коллекции: время расчёта, статистика разбора входа и сводка по режимам.
 */
@RestController
@Tag(name = OpenApiConfig.TAG_ROUTING_NAME, description = OpenApiConfig.TAG_ROUTING_DESCRIPTION)
public class RoutingController {

    private static final Logger log = LoggerFactory.getLogger(RoutingController.class);

    private final RoutingEngine engine;
    private final HeatProperties props;
    private final ObjectMapper mapper;

    public RoutingController(RoutingEngine engine, HeatProperties props, ObjectMapper mapper) {
        this.engine = engine;
        this.props = props;
        this.mapper = mapper;
    }

    @Operation(summary = "Экономичный режим: минимум показателя S. Ответ - GeoJSON с вариантами",
            description = "Считает трассировку новых участков от точек подключения ОКС до существующей "
                    + "теплосети с минимумом показателя S = 0,7 * C / 25 000 000 + 0,3 * L / 100 "
                    + "(C - расчётная стоимость в рублях, L - длина новой сети в метрах).\n\n"
                    + "Трасса ведётся по самому дешёвому допустимому коридору в обход запретов: "
                    + "углы и направления ничем не ограничены, привязки к сетке квартала и к улицам нет - "
                    + "этим маршрут отличается от POST /api/routing/installation, который даёт "
                    + "технологичную, но обычно более длинную и более дорогую трассу.\n\n"
                    + "В ответ попадают до 3 существенно различных вариантов с id e1, e2, e3 "
                    + "(ранжированы по S, вариант с рангом 1 - лучший): участки heat_network с ДУ, расходом, "
                    + "длиной, способом прокладки и стоимостью; новые камеры heat_chamber "
                    + "(chamber_role = tie_in - врезка в существующую сеть, branch - ответвление); "
                    + "узлы technical_node на границах спецпереходов; сводка variant_summary по каждому "
                    + "варианту. В конец FeatureCollection добавлено поле summary: время расчёта, "
                    + "статистика разбора входа, диагностика данных и сводка по режиму.\n\n"
                    + "Время ответа - разбор входного файла плюс бюджет оптимизатора (по умолчанию 45 с), "
                    + "то есть обычно около минуты на файл в десятки тысяч объектов.\n\n"
                    + "Выгрузка двумерная: depth_start и depth_end приходят null, точек crossing нет, "
                    + "раздел manual_review в сводке варианта пустой. Профиль по глубине, пересечения "
                    + "коммуникаций и заполненный manual_review даёт асинхронный путь: POST /api/datasets, "
                    + "POST /api/datasets/{id}/solve и затем GET /api/solutions/{id}/geojson?mode=depth.\n\n"
                    + "Маршрут предпочтителен, когда нужен минимум стоимости и длины, а требования "
                    + "к монтажной геометрии не предъявляются.")
    @ApiResponse(responseCode = "200",
            description = "Результат расчёта: один FeatureCollection в WGS 84 со всеми вариантами "
                    + "экономичного режима (id вариантов начинаются с e) и полем summary в конце коллекции. "
                    + "Ответ отдаётся как файл: Content-Disposition attachment, "
                    + "heat-routing-economy.geojson.",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Фрагмент выгрузки (сокращён)", value = "{"
                            + "\"type\":\"FeatureCollection\",\"name\":\"heat_routing_economy\",\"features\":["
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"e1_ch_1\","
                            + "\"object_type\":\"heat_chamber\",\"variant_id\":\"e1\",\"diameter\":400,"
                            + "\"cost\":5000000,\"chamber_role\":\"tie_in\",\"tie_in_line_id\":127},"
                            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.635112,55.700338]}},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"e1_net_1\","
                            + "\"object_type\":\"heat_network\",\"variant_id\":\"e1\",\"start_node_id\":1,"
                            + "\"end_node_id\":\"e1_ch_1\",\"flow_tph\":24.87,\"diameter\":125,"
                            + "\"length\":142.53,\"laying_method\":\"base\",\"depth_start\":null,"
                            + "\"depth_end\":null,\"cost\":13864606},\"geometry\":{\"type\":\"LineString\","
                            + "\"coordinates\":[[37.634405404,55.699481064],[37.635112,55.700338]]}},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"e1_summary\","
                            + "\"object_type\":\"variant_summary\",\"variant_id\":\"e1\",\"rank\":1,"
                            + "\"routing_mode\":\"economy\",\"routing_mode_name\":\"Экономичный\","
                            + "\"construction_cost\":87020000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"existing_chamber_tie_in_cost\":0,"
                            + "\"unconnected_penalty\":0,\"calculated_cost\":87020000,"
                            + "\"new_network_length\":812.44,\"score\":4.8739,\"manual_review\":[],"
                            + "\"unconnected_oks_ids\":[],\"strategy\":\"nearest-first+polish\","
                            + "\"unconnected_reasons\":{},\"validation_errors\":[],\"notes\":[]},"
                            + "\"geometry\":null}],"
                            + "\"summary\":{\"elapsedMs\":46231,\"featuresRead\":144,"
                            + "\"featuresStored\":144,\"featuresRejected\":0,"
                            + "\"issueCounts\":{\"WARN:UNKNOWN_RESTRICTION_TYPE\":1},"
                            + "\"diagnostics\":[\"restriction 5001: тип fence без правил в таблице 2, "
                            + "игнорируется\"],"
                            + "\"timeBudgetS\":45.0,\"modes\":[{\"mode\":\"economy\","
                            + "\"mode_name\":\"Экономичный\",\"variants\":[{\"variant_id\":\"e1\","
                            + "\"rank\":1,\"strategy\":\"nearest-first+polish\",\"score\":4.87388,"
                            + "\"calculated_cost\":87020000,\"construction_cost\":87020000,"
                            + "\"pipe_cost\":79020000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"unconnected_penalty\":0,"
                            + "\"new_network_length\":812.44,\"new_chambers\":2,\"tie_ins\":1,"
                            + "\"unconnected\":0,\"validation_errors\":[],\"manual_review\":[],"
                            + "\"notes\":[]}]}]}}")))
    @ApiResponse(responseCode = "400",
            description = "Файл не передан или пуст, либо во входе нет точек подключения ОКС "
                    + "или нет существующей сети. Тело - JSON с полем error.",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Нет обязательных объектов",
                            value = "{\"error\":\"нужны точки подключения ОКС и существующая сеть\"}")))
    @ApiResponse(responseCode = "500",
            description = "Ошибка расчёта: испорченный GeoJSON, недопустимая геометрия или нехватка памяти "
                    + "на слишком крупном файле. Тело - стандартный ответ Spring об ошибке.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(name = "Ошибка расчёта",
                            value = "{\"timestamp\":\"2026-05-18T09:14:03.118+00:00\",\"status\":500,"
                                    + "\"error\":\"Internal Server Error\","
                                    + "\"path\":\"/api/routing/economy\"}")))
    @PostMapping(value = "/api/routing/economy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/geo+json")
    public ResponseEntity<String> economy(
            @Parameter(description = "Входной GeoJSON FeatureCollection, поле multipart-формы file. "
                    + "Система координат - только EPSG:4326 (WGS 84), координаты в порядке долгота, широта. "
                    + "Состав: существующая теплосеть, точки подключения ОКС с расходом flow_tph в т/ч и "
                    + "пространственные ограничения (здания, дороги, трамвай, газ, кабель, вода, ж/д, парки). "
                    + "Обязательны хотя бы одна точка подключения ОКС и хотя бы один участок существующей "
                    + "сети, иначе ответ 400. Предел загрузки - 4 ГБ (настройка multipart), но этот путь "
                    + "отвечает синхронно и держит сценарий в памяти, поэтому для файлов в сотни мегабайт "
                    + "и больше лучше асинхронный путь POST /api/datasets.")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Бюджет времени оптимизатора на каждый режим, с. 0 или пусто - значение "
                    + "из конфигурации (heat.solver.time-budget-s, по умолчанию 45). Значение больше 600 "
                    + "урезается до 600. Расчёт всегда возвращает корректный результат: по истечении бюджета "
                    + "перебор прерывается и выгружаются лучшие найденные к этому моменту варианты, "
                    + "проверенные по правилам. Бюджет влияет только на качество (показатель S), "
                    + "а не на корректность выгрузки.", example = "45")
            @RequestParam(defaultValue = "0") double budgetSeconds) throws IOException {
        return run(file, budgetSeconds, List.of(Mode.ECONOMY));
    }

    @Operation(summary = "Монтажный режим: углы 45/90 по сетке квартала, вдоль улиц, без параллельных прокладок",
            description = "Считает трассировку с монтажными ограничениями: повороты только на 45 и 90 "
                    + "градусов по сетке квартала (шаг сетки 8 м, направление сетки берётся по преобладающему "
                    + "направлению застройки или задаётся настройкой), трассы тянутся вдоль улиц, "
                    + "прокладка параллельно существующей сети штрафуется.\n\n"
                    + "Отбор вариантов идёт по тому же показателю S = 0,7 * C / 25 000 000 + 0,3 * L / 100, "
                    + "но из-за ортогональной геометрии трасса обычно длиннее и дороже, чем в "
                    + "POST /api/routing/economy: S у монтажных вариантов, как правило, выше, "
                    + "зато трассу проще вынести в проект и на монтаж.\n\n"
                    + "В ответ попадают до 3 существенно различных вариантов с id m1, m2, m3 "
                    + "(ранжированы по S): участки heat_network, новые камеры heat_chamber "
                    + "(chamber_role = tie_in или branch), узлы technical_node на границах спецпереходов и "
                    + "сводка variant_summary с routing_mode = installation. В конец FeatureCollection "
                    + "добавлено поле summary: время расчёта, статистика разбора входа, диагностика "
                    + "(в том числе выбранный угол сетки квартала) и сводка по режиму.\n\n"
                    + "Время ответа - разбор входного файла плюс бюджет оптимизатора (по умолчанию 45 с), "
                    + "то есть обычно около минуты на файл в десятки тысяч объектов.\n\n"
                    + "Выгрузка двумерная: depth_start и depth_end приходят null, точек crossing нет, "
                    + "раздел manual_review в сводке варианта пустой - профиль по глубине даёт только "
                    + "выгрузка GET /api/solutions/{id}/geojson?mode=depth асинхронного пути.\n\n"
                    + "Маршрут предпочтителен, когда результат идёт в проектную документацию и монтаж: "
                    + "нужны ортогональные трассы вдоль улиц, а не минимальная стоимость.")
    @ApiResponse(responseCode = "200",
            description = "Результат расчёта: один FeatureCollection в WGS 84 со всеми вариантами "
                    + "монтажного режима (id вариантов начинаются с m) и полем summary в конце коллекции. "
                    + "Ответ отдаётся как файл: Content-Disposition attachment, "
                    + "heat-routing-installation.geojson.",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Фрагмент выгрузки (сокращён)", value = "{"
                            + "\"type\":\"FeatureCollection\",\"name\":\"heat_routing_installation\","
                            + "\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":\"m1_ch_1\","
                            + "\"object_type\":\"heat_chamber\",\"variant_id\":\"m1\",\"diameter\":400,"
                            + "\"cost\":5000000,\"chamber_role\":\"tie_in\",\"tie_in_line_id\":127},"
                            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.635026,55.700338]}},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"m1_net_1\","
                            + "\"object_type\":\"heat_network\",\"variant_id\":\"m1\",\"start_node_id\":1,"
                            + "\"end_node_id\":\"m1_ch_1\",\"flow_tph\":24.87,\"diameter\":125,"
                            + "\"length\":156.2,\"laying_method\":\"base\",\"depth_start\":null,"
                            + "\"depth_end\":null,\"cost\":15194355},\"geometry\":{\"type\":\"LineString\","
                            + "\"coordinates\":[[37.634405404,55.699481064],[37.635026,55.699481064],"
                            + "[37.635026,55.700338]]}},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"m1_summary\","
                            + "\"object_type\":\"variant_summary\",\"variant_id\":\"m1\",\"rank\":1,"
                            + "\"routing_mode\":\"installation\",\"routing_mode_name\":\"Монтажный\","
                            + "\"construction_cost\":94005000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"existing_chamber_tie_in_cost\":0,"
                            + "\"unconnected_penalty\":0,\"calculated_cost\":94005000,"
                            + "\"new_network_length\":884.1,\"score\":5.2844,\"manual_review\":[],"
                            + "\"unconnected_oks_ids\":[],\"strategy\":\"largest-flow-first+polish\","
                            + "\"unconnected_reasons\":{},\"validation_errors\":[],\"notes\":[]},"
                            + "\"geometry\":null}],"
                            + "\"summary\":{\"elapsedMs\":47905,\"featuresRead\":144,"
                            + "\"featuresStored\":144,\"featuresRejected\":0,\"issueCounts\":{},"
                            + "\"diagnostics\":[\"монтажный режим: сетка квартала под 20.5°\"],"
                            + "\"timeBudgetS\":45.0,\"modes\":[{\"mode\":\"installation\","
                            + "\"mode_name\":\"Монтажный\",\"variants\":[{\"variant_id\":\"m1\","
                            + "\"rank\":1,\"strategy\":\"largest-flow-first+polish\",\"score\":5.28444,"
                            + "\"calculated_cost\":94005000,\"construction_cost\":94005000,"
                            + "\"pipe_cost\":86005000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"unconnected_penalty\":0,"
                            + "\"new_network_length\":884.1,\"new_chambers\":2,\"tie_ins\":1,"
                            + "\"unconnected\":0,\"validation_errors\":[],\"manual_review\":[],"
                            + "\"notes\":[]}]}]}}")))
    @ApiResponse(responseCode = "400",
            description = "Файл не передан или пуст, либо во входе нет точек подключения ОКС "
                    + "или нет существующей сети. Тело - JSON с полем error.",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Пустой файл",
                            value = "{\"error\":\"пустой файл\"}")))
    @ApiResponse(responseCode = "500",
            description = "Ошибка расчёта: испорченный GeoJSON, недопустимая геометрия или нехватка памяти "
                    + "на слишком крупном файле. Тело - стандартный ответ Spring об ошибке.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(name = "Ошибка расчёта",
                            value = "{\"timestamp\":\"2026-05-18T09:14:03.118+00:00\",\"status\":500,"
                                    + "\"error\":\"Internal Server Error\","
                                    + "\"path\":\"/api/routing/installation\"}")))
    @PostMapping(value = "/api/routing/installation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/geo+json")
    public ResponseEntity<String> installation(
            @Parameter(description = "Входной GeoJSON FeatureCollection, поле multipart-формы file. "
                    + "Система координат - только EPSG:4326 (WGS 84), координаты в порядке долгота, широта. "
                    + "Состав: существующая теплосеть, точки подключения ОКС с расходом flow_tph в т/ч и "
                    + "пространственные ограничения (здания, дороги, трамвай, газ, кабель, вода, ж/д, парки). "
                    + "Обязательны хотя бы одна точка подключения ОКС и хотя бы один участок существующей "
                    + "сети, иначе ответ 400. Предел загрузки - 4 ГБ (настройка multipart), но этот путь "
                    + "отвечает синхронно и держит сценарий в памяти, поэтому для файлов в сотни мегабайт "
                    + "и больше лучше асинхронный путь POST /api/datasets.")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Бюджет времени оптимизатора на каждый режим, с. 0 или пусто - значение "
                    + "из конфигурации (heat.solver.time-budget-s, по умолчанию 45). Значение больше 600 "
                    + "урезается до 600. Расчёт всегда возвращает корректный результат: по истечении бюджета "
                    + "перебор прерывается и выгружаются лучшие найденные к этому моменту варианты, "
                    + "проверенные по правилам. Бюджет влияет только на качество (показатель S), "
                    + "а не на корректность выгрузки.", example = "45")
            @RequestParam(defaultValue = "0") double budgetSeconds) throws IOException {
        return run(file, budgetSeconds, List.of(Mode.INSTALLATION));
    }

    @Operation(summary = "Оба режима в одном файле: у каждого варианта проставлен routing_mode",
            description = "Считает оба режима по одному входному файлу за один запрос: сначала экономичный "
                    + "(минимум показателя S = 0,7 * C / 25 000 000 + 0,3 * L / 100), затем монтажный "
                    + "(углы 45 и 90 по сетке квартала, вдоль улиц, без параллельных прокладок). "
                    + "Вход разбирается один раз, поэтому это дешевле двух отдельных запросов, "
                    + "но бюджет времени тратится на каждый режим отдельно.\n\n"
                    + "Время ответа - разбор входного файла плюс два бюджета подряд: при бюджете по "
                    + "умолчанию (45 с на режим) это примерно вдвое дольше, чем POST /api/routing/economy "
                    + "или POST /api/routing/installation.\n\n"
                    + "В ответ попадают варианты обоих режимов в одном FeatureCollection: до 3 экономичных "
                    + "и до 3 монтажных, каждый со своими heat_network, heat_chamber, technical_node и "
                    + "variant_summary. Варианты различаются по variant_id и по полям routing_mode "
                    + "(economy или installation) и routing_mode_name (Экономичный или Монтажный) "
                    + "в variant_summary: id экономичных вариантов начинаются с e (e1, e2, e3), "
                    + "монтажных - с m (m1, m2, m3). Ранг rank считается внутри своего режима, поэтому "
                    + "rank = 1 есть и у e1, и у m1. В поле summary в конце коллекции лежит массив modes - "
                    + "по одной сводке на режим (mode, mode_name и список вариантов).\n\n"
                    + "Выгрузка двумерная: depth_start и depth_end приходят null, точек crossing нет, "
                    + "раздел manual_review в сводке варианта пустой - профиль по глубине даёт только "
                    + "выгрузка GET /api/solutions/{id}/geojson?mode=depth асинхронного пути.\n\n"
                    + "Маршрут предпочтителен, когда нужно сравнить экономику и монтажную технологичность "
                    + "на одних и тех же данных: показать заказчику оба решения и выбрать компромисс.")
    @ApiResponse(responseCode = "200",
            description = "Результат расчёта: один FeatureCollection в WGS 84 с вариантами обоих режимов. "
                    + "Варианты помечены в variant_summary полями routing_mode (economy или installation) "
                    + "и routing_mode_name (Экономичный или Монтажный), id экономичных вариантов начинаются "
                    + "с e, монтажных - с m. В поле summary в конце коллекции массив modes содержит по одной "
                    + "сводке на каждый режим. Ответ отдаётся как файл: Content-Disposition attachment, "
                    + "heat-routing-all-modes.geojson.",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Фрагмент выгрузки (сокращён)", value = "{"
                            + "\"type\":\"FeatureCollection\",\"name\":\"heat_routing_all_modes\","
                            + "\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":\"e1_ch_1\","
                            + "\"object_type\":\"heat_chamber\",\"variant_id\":\"e1\",\"diameter\":400,"
                            + "\"cost\":5000000,\"chamber_role\":\"tie_in\",\"tie_in_line_id\":127},"
                            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.635112,55.700338]}},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"e1_net_1\","
                            + "\"object_type\":\"heat_network\",\"variant_id\":\"e1\",\"start_node_id\":1,"
                            + "\"end_node_id\":\"e1_ch_1\",\"flow_tph\":24.87,\"diameter\":125,"
                            + "\"length\":142.53,\"laying_method\":\"base\",\"depth_start\":null,"
                            + "\"depth_end\":null,\"cost\":13864606},\"geometry\":{\"type\":\"LineString\","
                            + "\"coordinates\":[[37.634405404,55.699481064],[37.635112,55.700338]]}},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"e1_summary\","
                            + "\"object_type\":\"variant_summary\",\"variant_id\":\"e1\",\"rank\":1,"
                            + "\"routing_mode\":\"economy\",\"routing_mode_name\":\"Экономичный\","
                            + "\"construction_cost\":87020000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"existing_chamber_tie_in_cost\":0,"
                            + "\"unconnected_penalty\":0,\"calculated_cost\":87020000,"
                            + "\"new_network_length\":812.44,\"score\":4.8739,\"manual_review\":[],"
                            + "\"unconnected_oks_ids\":[],\"strategy\":\"nearest-first+polish\","
                            + "\"unconnected_reasons\":{},\"validation_errors\":[],\"notes\":[]},"
                            + "\"geometry\":null},"
                            + "{\"type\":\"Feature\",\"properties\":{\"id\":\"m1_summary\","
                            + "\"object_type\":\"variant_summary\",\"variant_id\":\"m1\",\"rank\":1,"
                            + "\"routing_mode\":\"installation\",\"routing_mode_name\":\"Монтажный\","
                            + "\"construction_cost\":94005000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"existing_chamber_tie_in_cost\":0,"
                            + "\"unconnected_penalty\":0,\"calculated_cost\":94005000,"
                            + "\"new_network_length\":884.1,\"score\":5.2844,\"manual_review\":[],"
                            + "\"unconnected_oks_ids\":[],\"strategy\":\"largest-flow-first+polish\","
                            + "\"unconnected_reasons\":{},\"validation_errors\":[],\"notes\":[]},"
                            + "\"geometry\":null}],"
                            + "\"summary\":{\"elapsedMs\":92418,\"featuresRead\":144,"
                            + "\"featuresStored\":144,\"featuresRejected\":0,\"issueCounts\":{},"
                            + "\"diagnostics\":[\"монтажный режим: сетка квартала под 20.5°\"],"
                            + "\"timeBudgetS\":45.0,\"modes\":[{\"mode\":\"economy\","
                            + "\"mode_name\":\"Экономичный\",\"variants\":[{\"variant_id\":\"e1\","
                            + "\"rank\":1,\"strategy\":\"nearest-first+polish\",\"score\":4.87388,"
                            + "\"calculated_cost\":87020000,\"construction_cost\":87020000,"
                            + "\"pipe_cost\":79020000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"unconnected_penalty\":0,"
                            + "\"new_network_length\":812.44,\"new_chambers\":2,\"tie_ins\":1,"
                            + "\"unconnected\":0,\"validation_errors\":[],\"manual_review\":[],"
                            + "\"notes\":[]}]},{\"mode\":\"installation\","
                            + "\"mode_name\":\"Монтажный\",\"variants\":[{\"variant_id\":\"m1\","
                            + "\"rank\":1,\"strategy\":\"largest-flow-first+polish\",\"score\":5.28444,"
                            + "\"calculated_cost\":94005000,\"construction_cost\":94005000,"
                            + "\"pipe_cost\":86005000,\"chamber_construction_cost\":8000000,"
                            + "\"existing_chamber_tie_in_count\":0,\"unconnected_penalty\":0,"
                            + "\"new_network_length\":884.1,\"new_chambers\":2,\"tie_ins\":1,"
                            + "\"unconnected\":0,\"validation_errors\":[],\"manual_review\":[],"
                            + "\"notes\":[]}]}]}}")))
    @ApiResponse(responseCode = "400",
            description = "Файл не передан или пуст, либо во входе нет точек подключения ОКС "
                    + "или нет существующей сети. Тело - JSON с полем error.",
            content = @Content(mediaType = "application/geo+json",
                    examples = @ExampleObject(name = "Нет обязательных объектов",
                            value = "{\"error\":\"нужны точки подключения ОКС и существующая сеть\"}")))
    @ApiResponse(responseCode = "500",
            description = "Ошибка расчёта: испорченный GeoJSON, недопустимая геометрия или нехватка памяти "
                    + "на слишком крупном файле. Тело - стандартный ответ Spring об ошибке.",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(name = "Ошибка расчёта",
                            value = "{\"timestamp\":\"2026-05-18T09:14:03.118+00:00\",\"status\":500,"
                                    + "\"error\":\"Internal Server Error\","
                                    + "\"path\":\"/api/routing/all\"}")))
    @PostMapping(value = "/api/routing/all", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/geo+json")
    public ResponseEntity<String> all(
            @Parameter(description = "Входной GeoJSON FeatureCollection, поле multipart-формы file. "
                    + "Система координат - только EPSG:4326 (WGS 84), координаты в порядке долгота, широта. "
                    + "Состав: существующая теплосеть, точки подключения ОКС с расходом flow_tph в т/ч и "
                    + "пространственные ограничения (здания, дороги, трамвай, газ, кабель, вода, ж/д, парки). "
                    + "Файл разбирается один раз для обоих режимов. Обязательны хотя бы одна точка "
                    + "подключения ОКС и хотя бы один участок существующей сети, иначе ответ 400. "
                    + "Предел загрузки - 4 ГБ (настройка multipart), но этот путь отвечает синхронно, "
                    + "держит сценарий в памяти и считает два режима подряд, поэтому для файлов в сотни "
                    + "мегабайт и больше лучше асинхронный путь POST /api/datasets.")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Бюджет времени оптимизатора на каждый режим, с: указанное значение "
                    + "тратится и на экономичный, и на монтажный режим, поэтому общее время примерно вдвое "
                    + "больше. 0 или пусто - значение из конфигурации (heat.solver.time-budget-s, "
                    + "по умолчанию 45). Значение больше 600 урезается до 600. Расчёт всегда возвращает "
                    + "корректный результат: по истечении бюджета перебор прерывается и выгружаются лучшие "
                    + "найденные к этому моменту варианты, проверенные по правилам. Бюджет влияет только "
                    + "на качество (показатель S), а не на корректность выгрузки.", example = "45")
            @RequestParam(defaultValue = "0") double budgetSeconds) throws IOException {
        return run(file, budgetSeconds, List.of(Mode.ECONOMY, Mode.INSTALLATION));
    }

    private ResponseEntity<String> run(MultipartFile file, double budgetSeconds, List<Mode> modes) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("{\"error\":\"пустой файл\"}");
        }
        long started = System.currentTimeMillis();
        double budget = budgetSeconds > 0 ? Math.min(budgetSeconds, 600) : props.getSolver().getTimeBudgetS();
        Projection projection = new Projection(props.getCrs().getCalc());
        ScenarioAssembler assembler = new ScenarioAssembler(mapper);
        IngestReport report;
        try (InputStream in = new BufferedInputStream(file.getInputStream(), 1 << 16)) {
            report = new IngestProcessor(mapper, projection, props.getIngest().getKnownRestrictionTypes(),
                    props.getIngest().getMaxIssueSamples()).process(in, assembler);
        }
        Scenario scenario = assembler.scenario();
        if (scenario.oks.isEmpty() || scenario.network.isEmpty()) {
            return ResponseEntity.badRequest().body("{\"error\":\"нужны точки подключения ОКС и существующая сеть\"}");
        }
        Context ctx = engine.context(scenario, projection.calcSrid());
        List<RoutingEngine.ModeResult> results = new ArrayList<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Mode mode : modes) {
            RoutingEngine.ModeResult r = engine.run(ctx, mode, budget);
            results.add(r);
            summaries.add(engine.summary(r));
        }
        Map<String, Object> fc = engine.export(ctx, results);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("elapsedMs", System.currentTimeMillis() - started);
        meta.put("featuresRead", report.featuresRead);
        meta.put("featuresStored", report.featuresStored);
        meta.put("featuresRejected", report.featuresRejected);
        meta.put("issueCounts", report.issueCounts);
        meta.put("diagnostics", ctx.diagnostics);
        meta.put("timeBudgetS", budget);
        meta.put("modes", summaries);
        fc.put("summary", meta);
        log.info("расчёт одним запросом: режимы {}, {} мс", modes, System.currentTimeMillis() - started);
        String name = modes.size() == 1 ? modes.get(0).name().toLowerCase() : "all-modes";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heat-routing-" + name + ".geojson\"")
                .body(mapper.writeValueAsString(fc));
    }
}
