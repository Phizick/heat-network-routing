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
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Context;
import ru.lct.heat.tree.Eval;
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

    public SolveService(SolutionRepository solutions, DatasetRepository datasets, HeatProperties props,
                        ObjectMapper mapper, ThreadPoolTaskExecutor solveExecutor) {
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

    public UUID start(UUID datasetId) {
        Map<String, Object> dataset = datasets.find(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("датасет не найден");
        }
        if (!DatasetRepository.Status.READY.name().equals(dataset.get("status"))) {
            throw new IllegalStateException("датасет ещё не загружен: статус " + dataset.get("status"));
        }
        UUID id = UUID.randomUUID();
        solutions.create(id, datasetId);
        solveExecutor.execute(() -> run(id, datasetId));
        return id;
    }

    private void run(UUID id, UUID datasetId) {
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
            Context ctx = new Context(scenario, new Rules(props), srid);
            Solver solver = new Solver(ctx);
            List<Solver.Candidate> variants = solver.solve();
            Validator validator = new Validator(ctx);
            Map<Integer, List<String>> validation = new HashMap<>();
            for (int v = 0; v < variants.size(); v++) {
                validation.put(v, validator.validate(variants.get(v).net, variants.get(v).eval));
            }
            Map<String, Object> fc = new GeoJsonExporter(ctx, props.getCrs().getCalc()).export(variants, validation);
            String json = mapper.writeValueAsString(fc);
            solutions.markReady(id, summary(ctx, variants, validation, System.currentTimeMillis() - started), json);
            log.info("расчёт {}: {} вариантов за {} мс", id, variants.size(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("расчёт {}: ошибка", id, e);
            solutions.markFailed(id, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private Map<String, Object> summary(Context ctx, List<Solver.Candidate> variants,
                                        Map<Integer, List<String>> validation, long elapsedMs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("elapsedMs", elapsedMs);
        s.put("oksCount", ctx.scenario.oks.size());
        s.put("totalFlowTph", ctx.scenario.oks.stream().mapToDouble(o -> o.flow).sum());
        s.put("existingNetworkComponents", ctx.network.components);
        s.put("diagnostics", ctx.diagnostics);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int v = 0; v < variants.size(); v++) {
            Solver.Candidate c = variants.get(v);
            Eval e = c.eval;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variant_id", "v" + (v + 1));
            m.put("rank", v + 1);
            m.put("strategy", c.strategy);
            m.put("score", e.score);
            m.put("calculated_cost", Math.round(e.calculatedCost));
            m.put("construction_cost", Math.round(e.constructionCost));
            m.put("pipe_cost", Math.round(e.pipeCost));
            m.put("chamber_construction_cost", Math.round(e.chamberCost));
            m.put("existing_chamber_tie_in_count", e.existingTieInCount);
            m.put("unconnected_penalty", Math.round(e.unconnectedPenalty));
            m.put("new_network_length", Math.round(e.length * 100) / 100.0);
            m.put("new_chambers", e.chamberCosts.size());
            m.put("tie_ins", c.net.nodes.values().stream().filter(n -> n.root).count());
            m.put("unconnected", c.net.unconnected.size());
            m.put("validation_errors", validation.get(v));
            m.put("notes", new ArrayList<>(c.net.notes));
            list.add(m);
        }
        s.put("variants", list);
        return s;
    }
}
