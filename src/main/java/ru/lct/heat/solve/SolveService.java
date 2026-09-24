package ru.lct.heat.solve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.dataset.DatasetRepository;
import ru.lct.heat.geo.Projection;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Context;
import ru.lct.heat.tree.Eval;
import ru.lct.heat.tree.Solver;
import ru.lct.heat.tree.Solver;
import ru.lct.heat.tree.Validator;

/** Запуск расчёта по загруженному датасету: сценарий из PostGIS → солвер → проверка → GeoJSON. */
@Service
public class SolveService {

    private static final Logger log = LoggerFactory.getLogger(SolveService.class);

    private final SolutionRepository solutions;
    private final DatasetRepository datasets;
    private final HeatProperties props;
    private final ObjectMapper mapper;
    private final ThreadPoolTaskExecutor solveExecutor;
    private final RoutingEngine engine;

    public SolveService(SolutionRepository solutions, DatasetRepository datasets, HeatProperties props,
                        ObjectMapper mapper, ThreadPoolTaskExecutor solveExecutor, RoutingEngine engine) {
        this.engine = engine;
        this.solutions = solutions;
        this.datasets = datasets;
        this.props = props;
        this.mapper = mapper;
        this.solveExecutor = solveExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        solutions.failInterrupted();
    }

    public UUID start(UUID datasetId, double budgetSeconds) {
        Map<String, Object> dataset = datasets.find(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("датасет не найден");
        }
        if (!DatasetRepository.Status.READY.name().equals(dataset.get("status"))) {
            throw new IllegalStateException("датасет ещё не загружен: статус " + dataset.get("status"));
        }
        UUID id = UUID.randomUUID();
        solutions.create(id, datasetId);
        solveExecutor.execute(() -> run(id, datasetId, budgetSeconds));
        return id;
    }

    private void run(UUID id, UUID datasetId, double budgetSeconds) {
        long started = System.currentTimeMillis();
        try {
            int srid = new Projection(props.getCrs().getCalc()).calcSrid();
            Scenario scenario = solutions.loadScenario(datasetId, props.getGeometry().getSearchAreaBufferM(), srid);
            if (scenario.oks.isEmpty()) {
                throw new IllegalStateException("в датасете нет точек подключения ОКС");
            }
            if (scenario.network.isEmpty()) {
                throw new IllegalStateException("в датасете нет существующей сети heat_network");
            }
            double budget = budgetSeconds > 0 ? budgetSeconds : props.getSolver().getTimeBudgetS();
            Context ctx = engine.context(scenario, srid);

            List<HeatProperties.Mode> modes = new ArrayList<>();
            modes.add(HeatProperties.Mode.ECONOMY);
            if (props.getInstallation().isEnabled()) {
                modes.add(HeatProperties.Mode.INSTALLATION);
            }
            List<RoutingEngine.ModeResult> results = new ArrayList<>();
            List<Map<String, Object>> modeSummaries = new ArrayList<>();
            for (HeatProperties.Mode mode : modes) {
                RoutingEngine.ModeResult r = engine.run(ctx, mode, budget);
                results.add(r);
                modeSummaries.add(engine.summary(r));
                log.info("расчёт {}: режим {} - {} вариантов", id, mode, r.variants.size());
            }

            String json = mapper.writeValueAsString(engine.export(ctx, results));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("elapsedMs", System.currentTimeMillis() - started);
            summary.put("oksCount", ctx.scenario.oks.size());
            summary.put("totalFlowTph", ctx.scenario.oks.stream().mapToDouble(o -> o.flow).sum());
            summary.put("existingNetworkComponents", ctx.network.components);
            summary.put("diagnostics", ctx.diagnostics);
            summary.put("timeBudgetS", budget);
            summary.put("modes", modeSummaries);
            // совместимость: variants - экономичный режим
            summary.put("variants", modeSummaries.get(0).get("variants"));

            String depthJson = null;
            if (props.getDepth().isEnabled()) {
                // режим с глубиной: отдельный файл и своё ранжирование (R-VAR-04)
                GeoJsonExporter exporter = new GeoJsonExporter(ctx, props.getCrs().getCalc());
                List<Object> depthFeatures = new ArrayList<>();
                List<Map<String, Object>> depthSummaries = new ArrayList<>();
                for (RoutingEngine.ModeResult r : results) {
                    Solver.DepthResult depth = new Solver(ctx, budget, r.mode).depthVariants(r.variants);
                    Map<Integer, List<String>> depthValidation = new HashMap<>();
                    for (int v = 0; v < depth.variants.size(); v++) {
                        List<String> errors = new ArrayList<>();
                        for (int i = 0; i < r.variants.size(); i++) {
                            if (r.variants.get(i).net == depth.variants.get(v).net) {
                                errors.addAll(r.validation.get(i));
                            }
                        }
                        errors.addAll(depth.problems.get(v));
                        depthValidation.put(v, errors);
                    }
                    exporter.addVariants(depthFeatures, depth.variants, depthValidation, r.mode);
                    depthSummaries.add(engine.summary(new RoutingEngine.ModeResult(r.mode, depth.variants, depthValidation)));
                }
                depthJson = mapper.writeValueAsString(exporter.collection("heat_routing_depth", depthFeatures));
                summary.put("depthModes", depthSummaries);
                summary.put("depthVariants", depthSummaries.get(0).get("variants"));
            }
            solutions.markReady(id, summary, json, depthJson);
            log.info("расчёт {}: {} режимов за {} мс", id, results.size(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("расчёт {}: ошибка", id, e);
            solutions.markFailed(id, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
