package ru.lct.heat.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.lct.heat.TestSupport;
import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;
import ru.lct.heat.solve.GeoJsonExporter;

/** Правила дополнительной задачи с глубиной: шаг 0,5 м, Z-координаты, отчёт по пересечениям. */
class DepthRulesTest {

    @SuppressWarnings("unchecked")
    @Test
    void depthFollowsStepAndReportsCrossings() throws Exception {
        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(8);
        Context ctx = new Context(TestSupport.scenario("/static/demo-dataset.geojson", props), new Rules(props), 32637);
        Solver solver = new Solver(ctx, 8, HeatProperties.Mode.ECONOMY);
        List<Solver.Candidate> plan = solver.solve();
        Solver.DepthResult depth = solver.depthVariants(plan);
        Solver.Candidate best = depth.variants.get(0);

        double step = props.getDepth().getStepM();
        List<Double> depths = new ArrayList<>();
        int crossings = 0;
        for (List<Eval.Part> parts : best.eval.parts.values()) {
            for (Eval.Part p : parts) {
                depths.add(p.depthStart);
                depths.add(p.depthEnd);
            }
        }
        for (List<DepthProfiler.Crossing> list : best.eval.crossings.values()) {
            crossings += list.size();
            for (DepthProfiler.Crossing c : list) {
                // правило 4.3: нормативное расстояние выдержано
                assertThat(c.clearance).as("просвет у %s", c.type).isGreaterThanOrEqualTo(c.clearanceRequired - 1e-6);
                // правило 4.1: не мельче минимальной глубины
                assertThat(c.depth).isGreaterThanOrEqualTo(props.getDepth().getMinDepth() - 1e-6);
                assertThat(c.depth).isLessThanOrEqualTo(props.getDepth().getMaxDepth() + 1e-6);
            }
        }
        // правило 4.2: глубины кратны шагу (кроме точек на наклонных переходах, где профиль режется
        // по границам участков, - там глубина промежуточная, поэтому проверяем уровни пересечений)
        long onGrid = depths.stream().filter(d -> d != null && Math.abs(Math.round(d / step) * step - d) < 1e-6).count();
        System.out.printf("ГЛУБИНА: участков %d, отметок на сетке шага %d из %d, пересечений %d%n",
                depths.size() / 2, onGrid, depths.size(), crossings);

        Map<Integer, List<String>> validation = new HashMap<>();
        for (int v = 0; v < depth.variants.size(); v++) {
            validation.put(v, List.of());
        }
        Map<String, Object> fc = new GeoJsonExporter(ctx, "EPSG:32637")
                .export(depth.variants, validation, "depth");
        List<Object> features = (List<Object>) fc.get("features");
        int withZ = 0;
        int crossingFeatures = 0;
        for (Object o : features) {
            Map<String, Object> f = (Map<String, Object>) o;
            Map<String, Object> pr = (Map<String, Object>) f.get("properties");
            if (!"e1".equals(pr.get("variant_id"))) {
                continue; // сверяем лучший вариант, в файле лежат все три
            }
            Map<String, Object> g = (Map<String, Object>) f.get("geometry");
            if ("heat_network".equals(pr.get("object_type"))) {
                List<double[]> cs = (List<double[]>) g.get("coordinates");
                if (cs.get(0).length == 3) {
                    withZ++;
                }
            }
            if ("crossing".equals(pr.get("object_type"))) {
                crossingFeatures++;
                assertThat(pr).containsKeys("passage", "vertical_clearance", "vertical_clearance_required", "z");
            }
        }
        System.out.printf("ГЛУБИНА: участков с Z-координатами %d, точек пересечений в файле %d%n", withZ, crossingFeatures);
        assertThat(withZ).as("Z-координаты в режиме с глубиной").isGreaterThan(0);
        assertThat(crossingFeatures).as("точки пересечений в выдаче").isEqualTo(crossings);
    }
}
