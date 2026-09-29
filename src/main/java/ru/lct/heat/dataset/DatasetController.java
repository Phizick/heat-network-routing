package ru.lct.heat.dataset;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.lct.heat.config.OpenApiConfig;

@RestController
@RequestMapping("/api/datasets")
@Tag(name = OpenApiConfig.TAG_DATASETS_NAME, description = OpenApiConfig.TAG_DATASETS_DESCRIPTION)
public class DatasetController {

    private final DatasetService service;
    private final DatasetRepository repository;

    public DatasetController(DatasetService service, DatasetRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Operation(summary = "Загрузить GeoJSON FeatureCollection (до 3 ГБ), разбор идёт в фоне",
            description = "Загрузка потоковая: файл сразу переносится на временный диск сервиса и целиком в "
                    + "память не читается, multipart-буфер Tomcat удаляется после ответа. Ограничение входа - "
                    + "3 ГБ (лимит multipart в конфигурации 4 ГБ).\n\n"
                    + "Разбор идёт в фоне, в отдельном пуле (по умолчанию 2 потока), поэтому ответ приходит "
                    + "сразу: 202 с выданным id и статусом PROCESSING. Статус надо опрашивать через "
                    + "GET /api/datasets/{id}, пока он не станет READY или FAILED; при перезапуске сервиса "
                    + "незавершённые загрузки помечаются FAILED с ошибкой «обработка прервана перезапуском "
                    + "сервиса».\n\n"
                    + "Что проверяется при разборе (диагностика данных):\n"
                    + "- CRS входа: ожидается CRS84 или EPSG:4326, иначе предупреждение UNEXPECTED_CRS, "
                    + "координаты всё равно читаются как lon/lat;\n"
                    + "- структура объекта: элемент features является объектом (NOT_A_FEATURE), есть properties "
                    + "(NO_PROPERTIES), есть id (NO_ID), есть object_type (NO_OBJECT_TYPE);\n"
                    + "- object_type из набора source, heat_network, heat_chamber, oks_connection_point, "
                    + "restriction; незнакомый тип - предупреждение UNKNOWN_OBJECT_TYPE, объект сохраняется, "
                    + "но в расчёте не участвует;\n"
                    + "- повтор id внутри одного object_type - предупреждение DUPLICATE_ID;\n"
                    + "- у restriction обязателен restriction_type (NO_RESTRICTION_TYPE); тип, для которого нет "
                    + "правил в конфигурации, - предупреждение UNKNOWN_RESTRICTION_TYPE;\n"
                    + "- у oks_connection_point обязателен положительный flow_tph (ошибка BAD_FLOW), у "
                    + "heat_network ожидается положительный diameter (предупреждение BAD_DIAMETER);\n"
                    + "- геометрия: разбирается (BAD_GEOMETRY), непустая (EMPTY_GEOMETRY), тип допустим для "
                    + "object_type (WRONG_GEOMETRY_TYPE: точки для source, heat_chamber и "
                    + "oks_connection_point, линии для heat_network, линии и полигоны для restriction), "
                    + "координаты в диапазоне lon/lat (COORDS_OUT_OF_RANGE);\n"
                    + "- валидность после перепроецирования в расчётную CRS: невалидная геометрия "
                    + "исправляется автоматически (GEOMETRY_FIXED), неисправимая отбрасывается "
                    + "(INVALID_GEOMETRY).\n\n"
                    + "Объекты с ошибкой (severity = ERROR) отбрасываются, с предупреждением (WARN) "
                    + "принимаются. В отчёт report попадают: sourceCrs, счётчики featuresRead, featuresStored, "
                    + "featuresRejected, разбивка objectTypeCounts и restrictionTypeCounts, суммарный расход "
                    + "ОКС totalFlowTph, охват bboxWgs84 в виде [minLon, minLat, maxLon, maxLat], счётчики "
                    + "проблем issueCounts в формате «severity:code» и до 500 примеров issueSamples "
                    + "(issueSamplesTruncated = true, если примеры обрезаны).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Файл принят, разбор запущен в фоне",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(name = "Принято", value = "{\n"
                                    + "  \"id\": \"6f6c2a7e-6c1b-4a0a-9b5b-6e9a1c2d3e4f\",\n"
                                    + "  \"status\": \"PROCESSING\"\n"
                                    + "}"))),
            @ApiResponse(responseCode = "400",
                    description = "Файл не передан или пустой. Некорректный GeoJSON на этом шаге не виден: он "
                            + "обнаруживается при фоновом разборе и приводит к статусу FAILED с текстом ошибки "
                            + "в поле error",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(name = "Пустой файл",
                                    value = "{\n  \"error\": \"пустой файл\"\n}")))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @Parameter(description = "Файл GeoJSON FeatureCollection в WGS 84 (lon/lat, CRS84 или EPSG:4326), "
                    + "поле формы file, тип запроса multipart/form-data. Размер - до 3 ГБ, файл читается "
                    + "потоком. Расширение значения не имеет, важно содержимое: объект с type = "
                    + "FeatureCollection и массивом features",
                    required = true,
                    schema = @Schema(type = "string", format = "binary"))
            @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "пустой файл"));
        }
        UUID id = service.upload(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("id", id, "status", DatasetRepository.Status.PROCESSING.name()));
    }

    @Operation(summary = "Список загрузок",
            description = "Все загрузки, новые сверху (сортировка по created_at по убыванию). По каждой: id, "
                    + "имя исходного файла name, статус status (PROCESSING, READY, FAILED), размер файла в "
                    + "байтах file_size, время создания created_at, время завершения разбора finished_at "
                    + "(null, пока разбор идёт) и текст ошибки error для FAILED. Отчёт диагностики в список не "
                    + "входит, его отдаёт GET /api/datasets/{id}.")
    @ApiResponse(responseCode = "200", description = "Список загрузок",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = @ExampleObject(name = "Три загрузки", value = "[\n"
                            + "  {\n"
                            + "    \"id\": \"6f6c2a7e-6c1b-4a0a-9b5b-6e9a1c2d3e4f\",\n"
                            + "    \"name\": \"case_1.geojson\",\n"
                            + "    \"status\": \"READY\",\n"
                            + "    \"file_size\": 734003200,\n"
                            + "    \"created_at\": \"2026-09-29T09:12:44.117+00:00\",\n"
                            + "    \"finished_at\": \"2026-09-29T09:15:02.408+00:00\",\n"
                            + "    \"error\": null\n"
                            + "  },\n"
                            + "  {\n"
                            + "    \"id\": \"b1d4e9c0-2f3a-4c58-8d1e-7a0b5c6d7e8f\",\n"
                            + "    \"name\": \"case_2.geojson\",\n"
                            + "    \"status\": \"PROCESSING\",\n"
                            + "    \"file_size\": 1288490188,\n"
                            + "    \"created_at\": \"2026-09-29T09:20:11.002+00:00\",\n"
                            + "    \"finished_at\": null,\n"
                            + "    \"error\": null\n"
                            + "  },\n"
                            + "  {\n"
                            + "    \"id\": \"c2e5f0d1-3a4b-4d69-9e2f-8b1c6d7e8f90\",\n"
                            + "    \"name\": \"broken.geojson\",\n"
                            + "    \"status\": \"FAILED\",\n"
                            + "    \"file_size\": 1536,\n"
                            + "    \"created_at\": \"2026-09-29T09:22:30.441+00:00\",\n"
                            + "    \"finished_at\": \"2026-09-29T09:22:30.489+00:00\",\n"
                            + "    \"error\": \"в файле нет массива features\"\n"
                            + "  }\n"
                            + "]")))
    @GetMapping
    public List<Map<String, Object>> list() {
        return repository.list();
    }

    @Operation(summary = "Статус загрузки и отчёт диагностики",
            description = "Карточка одной загрузки: id, name, status (PROCESSING, READY, FAILED), file_size, "
                    + "created_at, finished_at, error и отчёт report.\n\n"
                    + "Пока status = PROCESSING, report и finished_at равны null - метод опрашивают до "
                    + "перехода в READY или FAILED. При READY в report лежит диагностика разбора: исходная CRS "
                    + "sourceCrs, сколько объектов прочитано, принято и отброшено (featuresRead, "
                    + "featuresStored, featuresRejected), счётчики по типам objectTypeCounts и "
                    + "restrictionTypeCounts, суммарный расход точек подключения totalFlowTph в т/ч, охват "
                    + "принятых объектов bboxWgs84 = [minLon, minLat, maxLon, maxLat], счётчики проблем "
                    + "issueCounts (ключ - «ERROR:CODE» или «WARN:CODE») и примеры issueSamples с номером "
                    + "объекта во входном файле featureIndex, его srcId, objectType, severity, code и "
                    + "сообщением message. При FAILED разбор не дошёл до конца: report = null, причина в "
                    + "error, а частично загруженные объекты из базы удалены.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Загрузка найдена",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = {
                            @ExampleObject(name = "Разбор завершён (READY)", value = "{\n"
                                    + "  \"id\": \"6f6c2a7e-6c1b-4a0a-9b5b-6e9a1c2d3e4f\",\n"
                                    + "  \"name\": \"case_1.geojson\",\n"
                                    + "  \"status\": \"READY\",\n"
                                    + "  \"file_size\": 734003200,\n"
                                    + "  \"created_at\": \"2026-09-29T09:12:44.117+00:00\",\n"
                                    + "  \"finished_at\": \"2026-09-29T09:15:02.408+00:00\",\n"
                                    + "  \"error\": null,\n"
                                    + "  \"report\": {\n"
                                    + "    \"sourceCrs\": \"urn:ogc:def:crs:OGC:1.3:CRS84\",\n"
                                    + "    \"featuresRead\": 128340,\n"
                                    + "    \"featuresStored\": 128297,\n"
                                    + "    \"featuresRejected\": 43,\n"
                                    + "    \"objectTypeCounts\": {\n"
                                    + "      \"heat_chamber\": 214,\n"
                                    + "      \"heat_network\": 1802,\n"
                                    + "      \"oks_connection_point\": 37,\n"
                                    + "      \"restriction\": 126240,\n"
                                    + "      \"source\": 4\n"
                                    + "    },\n"
                                    + "    \"restrictionTypeCounts\": {\n"
                                    + "      \"gas_pipeline\": 812,\n"
                                    + "      \"heat_network\": 96,\n"
                                    + "      \"oks\": 121720,\n"
                                    + "      \"park\": 58,\n"
                                    + "      \"power_cable\": 1140,\n"
                                    + "      \"road\": 2304,\n"
                                    + "      \"tram_tracks\": 74,\n"
                                    + "      \"water\": 36\n"
                                    + "    },\n"
                                    + "    \"totalFlowTph\": 412.75,\n"
                                    + "    \"bboxWgs84\": [37.55012, 55.62103, 37.72488, 55.78951],\n"
                                    + "    \"issueCounts\": {\n"
                                    + "      \"ERROR:BAD_FLOW\": 2,\n"
                                    + "      \"ERROR:WRONG_GEOMETRY_TYPE\": 41,\n"
                                    + "      \"WARN:DUPLICATE_ID\": 9,\n"
                                    + "      \"WARN:GEOMETRY_FIXED\": 17\n"
                                    + "    },\n"
                                    + "    \"issueSamples\": [\n"
                                    + "      {\n"
                                    + "        \"featureIndex\": 517,\n"
                                    + "        \"srcId\": \"1042\",\n"
                                    + "        \"objectType\": \"oks_connection_point\",\n"
                                    + "        \"severity\": \"ERROR\",\n"
                                    + "        \"code\": \"BAD_FLOW\",\n"
                                    + "        \"message\": \"flow_tph отсутствует или не положительное "
                                    + "число: null\"\n"
                                    + "      },\n"
                                    + "      {\n"
                                    + "        \"featureIndex\": 20418,\n"
                                    + "        \"srcId\": \"58311\",\n"
                                    + "        \"objectType\": \"restriction\",\n"
                                    + "        \"severity\": \"WARN\",\n"
                                    + "        \"code\": \"GEOMETRY_FIXED\",\n"
                                    + "        \"message\": \"невалидная геометрия исправлена автоматически\"\n"
                                    + "      }\n"
                                    + "    ],\n"
                                    + "    \"issueSamplesTruncated\": false\n"
                                    + "  }\n"
                                    + "}"),
                            @ExampleObject(name = "Разбор идёт (PROCESSING)", value = "{\n"
                                    + "  \"id\": \"b1d4e9c0-2f3a-4c58-8d1e-7a0b5c6d7e8f\",\n"
                                    + "  \"name\": \"case_2.geojson\",\n"
                                    + "  \"status\": \"PROCESSING\",\n"
                                    + "  \"file_size\": 1288490188,\n"
                                    + "  \"created_at\": \"2026-09-29T09:20:11.002+00:00\",\n"
                                    + "  \"finished_at\": null,\n"
                                    + "  \"error\": null,\n"
                                    + "  \"report\": null\n"
                                    + "}"),
                            @ExampleObject(name = "Разбор не удался (FAILED)", value = "{\n"
                                    + "  \"id\": \"c2e5f0d1-3a4b-4d69-9e2f-8b1c6d7e8f90\",\n"
                                    + "  \"name\": \"broken.geojson\",\n"
                                    + "  \"status\": \"FAILED\",\n"
                                    + "  \"file_size\": 1536,\n"
                                    + "  \"created_at\": \"2026-09-29T09:22:30.441+00:00\",\n"
                                    + "  \"finished_at\": \"2026-09-29T09:22:30.489+00:00\",\n"
                                    + "  \"error\": \"в файле нет массива features\",\n"
                                    + "  \"report\": null\n"
                                    + "}")})),
            @ApiResponse(responseCode = "404", description = "Датасет с таким id не найден, тело ответа пустое",
                    content = @Content)})
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @Parameter(description = "Идентификатор загрузки (UUID), выданный в поле id ответа "
                    + "POST /api/datasets", required = true,
                    example = "6f6c2a7e-6c1b-4a0a-9b5b-6e9a1c2d3e4f")
            @PathVariable UUID id) {
        Map<String, Object> dataset = repository.find(id);
        return dataset == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dataset);
    }

    @Operation(summary = "Состав загруженных данных по БД: счётчики по типам, длина сети, охват",
            description = "Считается запросами к базе по фактически принятым объектам датасета, поэтому может "
                    + "отличаться от отчёта разбора, где учтён и отброшенный вход. Возвращаются три блока:\n"
                    + "- counts: количество объектов в разрезе object_type и restriction_type (поля "
                    + "object_type, restriction_type, count), отсортированное по этим же полям. "
                    + "restriction_type заполнен только у ограничений, у остальных типов null;\n"
                    + "- heatNetworkLengthM: суммарная длина существующей сети, то есть объектов "
                    + "object_type = heat_network, в метрах расчётной CRS (EPSG:32637), округление до 0,1; "
                    + "если таких объектов нет - 0;\n"
                    + "- extentCalc: охват территории - габаритный прямоугольник всех объектов датасета в "
                    + "расчётной CRS: min_x, min_y, max_x, max_y в метрах. Если принятых объектов нет, поля "
                    + "равны null.\n\n"
                    + "Метод полезен как проверка перед расчётом: видно, есть ли источники и точки "
                    + "подключения, какие ограничения пришли и в какой области лежат данные.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Состав данных датасета",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(name = "Состав данных", value = "{\n"
                                    + "  \"counts\": [\n"
                                    + "    {\"object_type\": \"heat_chamber\", \"restriction_type\": null, "
                                    + "\"count\": 214},\n"
                                    + "    {\"object_type\": \"heat_network\", \"restriction_type\": null, "
                                    + "\"count\": 1802},\n"
                                    + "    {\"object_type\": \"oks_connection_point\", "
                                    + "\"restriction_type\": null, \"count\": 37},\n"
                                    + "    {\"object_type\": \"restriction\", "
                                    + "\"restriction_type\": \"gas_pipeline\", \"count\": 812},\n"
                                    + "    {\"object_type\": \"restriction\", \"restriction_type\": \"oks\", "
                                    + "\"count\": 121720},\n"
                                    + "    {\"object_type\": \"restriction\", \"restriction_type\": \"road\", "
                                    + "\"count\": 2304},\n"
                                    + "    {\"object_type\": \"source\", \"restriction_type\": null, "
                                    + "\"count\": 4}\n"
                                    + "  ],\n"
                                    + "  \"heatNetworkLengthM\": 48213.6,\n"
                                    + "  \"extentCalc\": {\n"
                                    + "    \"min_x\": 408694.8,\n"
                                    + "    \"min_y\": 6164856.1,\n"
                                    + "    \"max_x\": 420044.3,\n"
                                    + "    \"max_y\": 6183388.8\n"
                                    + "  }\n"
                                    + "}"))),
            @ApiResponse(responseCode = "404", description = "Датасет с таким id не найден, тело ответа пустое",
                    content = @Content)})
    @GetMapping("/{id}/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @Parameter(description = "Идентификатор загрузки (UUID). Состав данных имеет смысл смотреть после "
                    + "перехода статуса в READY", required = true,
                    example = "6f6c2a7e-6c1b-4a0a-9b5b-6e9a1c2d3e4f")
            @PathVariable UUID id) {
        if (repository.find(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.summary(id));
    }
}
