package ru.lct.heat.solve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.config.HeatProperties.Mode;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Context;
import ru.lct.heat.tree.Eval;
import ru.lct.heat.tree.Solver;
import ru.lct.heat.tree.Validator;

/**
 * Расчёт одного сценария в одном или нескольких режимах трассировки.
 * Экономичный - минимум показателя S. Монтажный - углы 45/90 по сетке квартала,
 * вдоль улиц, без параллельных прокладок.
 */
@Component
public class RoutingEngine {

    /** Результат одного режима: варианты, их проверка и сводка. */
    public static final class ModeResult {
        public final Mode mode;
        public final List<Solver.Candidate> variants;
        public final Map<Integer, List<String>> validation;

        ModeResult(Mode mode, List<Solver.Candidate> variants, Map<Integer, List<String>> validation) {
            this.mode = mode;
            this.variants = variants;
            this.validation = validation;
        }
    }

    private final HeatProperties props;

    public RoutingEngine(HeatProperties props) {
        this.props = props;
    }

    public Context context(Scenario scenario, int srid) {
        return new Context(scenario, new Rules(props), srid);
    }

    public ModeResult run(Context ctx, Mode mode, double budgetSeconds) {
        List<Solver.Candidate> variants = new Solver(ctx, budgetSeconds, mode).solve();
        Validator validator = new Validator(ctx);
        Map<Integer, List<String>> validation = new HashMap<>();
        for (int v = 0; v < variants.size(); v++) {
            validation.put(v, validator.validate(variants.get(v).net, variants.get(v).eval));
        }
        return new ModeResult(mode, variants, validation);
    }

    /** Выгрузка одного или нескольких режимов в один FeatureCollection. */
    public Map<String, Object> export(Context ctx, List<ModeResult> results) {
        GeoJsonExporter exporter = new GeoJsonExporter(ctx, props.getCrs().getCalc());
        List<Object> features = new ArrayList<>();
        for (ModeResult r : results) {
            exporter.addVariants(features, r.variants, r.validation, r.mode);
        }
        String name = results.size() == 1
                ? "heat_routing_" + results.get(0).mode.name().toLowerCase()
                : "heat_routing_all_modes";
        return exporter.collection(name, features);
    }

    /** Сводка по режиму для API и карты. */
    public Map<String, Object> summary(ModeResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", r.mode.name().toLowerCase());
        m.put("mode_name", GeoJsonExporter.modeName(r.mode));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int v = 0; v < r.variants.size(); v++) {
            Solver.Candidate c = r.variants.get(v);
            Eval e = c.eval;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("variant_id", GeoJsonExporter.modePrefix(r.mode) + (v + 1));
            x.put("rank", v + 1);
            x.put("strategy", c.strategy);
            x.put("score", e.score);
            x.put("calculated_cost", Math.round(e.calculatedCost));
            x.put("construction_cost", Math.round(e.constructionCost));
            x.put("pipe_cost", Math.round(e.pipeCost));
            x.put("chamber_construction_cost", Math.round(e.chamberCost));
            x.put("existing_chamber_tie_in_count", e.existingTieInCount);
            x.put("unconnected_penalty", Math.round(e.unconnectedPenalty));
            x.put("new_network_length", Math.round(e.length * 100) / 100.0);
            x.put("new_chambers", e.chamberCosts.size());
            x.put("tie_ins", c.net.nodes.values().stream().filter(n -> n.root).count());
            x.put("unconnected", c.net.unconnected.size());
            x.put("validation_errors", r.validation.get(v));
            x.put("notes", new ArrayList<>(c.net.notes));
            list.add(x);
        }
        m.put("variants", list);
        return m;
    }
}
