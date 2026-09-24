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
import ru.lct.heat.geo.Projection;
import ru.lct.heat.ingest.IngestProcessor;
import ru.lct.heat.ingest.IngestReport;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.model.ScenarioAssembler;
import ru.lct.heat.route.Context;

/**
 * Расчёт «файл на входе - файл на выходе», без базы и без ожидания статуса.
 * Три точки входа: экономичный режим, монтажный режим и оба сразу.
 */
@RestController
@Tag(name = "Трассировка одним запросом", description = "Загрузка GeoJSON и получение результата в ответе")
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

    @Operation(summary = "Экономичный режим: минимум показателя S. Ответ - GeoJSON с вариантами")
    @PostMapping(value = "/api/routing/economy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/geo+json")
    public ResponseEntity<String> economy(@RequestParam("file") MultipartFile file,
                                          @RequestParam(defaultValue = "0") double budgetSeconds) throws IOException {
        return run(file, budgetSeconds, List.of(Mode.ECONOMY));
    }

    @Operation(summary = "Монтажный режим: углы 45/90 по сетке квартала, вдоль улиц, без параллельных прокладок")
    @PostMapping(value = "/api/routing/installation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/geo+json")
    public ResponseEntity<String> installation(@RequestParam("file") MultipartFile file,
                                               @RequestParam(defaultValue = "0") double budgetSeconds) throws IOException {
        return run(file, budgetSeconds, List.of(Mode.INSTALLATION));
    }

    @Operation(summary = "Оба режима в одном файле: у каждого варианта проставлен routing_mode")
    @PostMapping(value = "/api/routing/all", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/geo+json")
    public ResponseEntity<String> all(@RequestParam("file") MultipartFile file,
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
