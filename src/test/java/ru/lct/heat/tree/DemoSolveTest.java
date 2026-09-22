package ru.lct.heat.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import ru.lct.heat.TestSupport;
import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;
import ru.lct.heat.solve.GeoJsonExporter;

/** Демо-набор со спецпереходами: все ОКС подключены, правила соблюдены, есть special-участки. */
class DemoSolveTest {

    @Test
    void demoDatasetHasSpecialCrossings() throws Exception {
        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(15);
        Context ctx = new Context(TestSupport.scenario("/static/demo-dataset.geojson", props), new Rules(props), 32637);
        List<Solver.Candidate> variants = new Solver(ctx).solve();
        Validator validator = new Validator(ctx);
        Map<Integer, List<String>> validation = new HashMap<>();
        for (int v = 0; v < variants.size(); v++) {
            validation.put(v, validator.validate(variants.get(v).net, variants.get(v).eval));
            System.out.println("ДЕМО вариант " + variants.get(v).strategy + " S=" + variants.get(v).eval.score
                    + " невалидных=" + variants.get(v).eval.invalidSegments + " заметки=" + variants.get(v).net.notes
                    + " проблемы=" + variants.get(v).eval.problems);
        }
        new ObjectMapper().writeValue(new File("target/demo-solution.geojson"),
                new GeoJsonExporter(ctx, "EPSG:32637").export(variants, validation));
        for (int v = 0; v < variants.size(); v++) {
            assertThat(validation.get(v)).as(variants.get(v).strategy).isEmpty();
        }
        Solver.Candidate best = variants.get(0);
        long special = best.eval.parts.values().stream().flatMap(List::stream).filter(p -> p.special).count();
        System.out.printf("ДЕМО: S=%.3f, спецучастков=%d, неподкл=%s%n", best.eval.score, special, best.net.unconnected);
        assertThat(best.net.unconnected).isEmpty();
        assertThat(special).isGreaterThan(0);

        // режим с глубиной: под дорогой верх не мельче 1,0 м, у газа выше/ниже с просветом, уклон ≤ 0,1
        Solver.DepthResult depth = new Solver(ctx).depthVariants(variants);
        Eval d = depth.variants.get(0).eval;
        System.out.printf("ДЕМО глубина: S=%.3f (2D %.3f), проблемы=%s%n", d.score, best.eval.score, depth.problems.get(0));
        assertThat(depth.problems.get(0)).isEmpty();
        for (List<Eval.Part> parts : d.parts.values()) {
            for (Eval.Part p : parts) {
                assertThat(p.depthStart).isNotNull();
                assertThat(Math.min(p.depthStart, p.depthEnd)).isGreaterThanOrEqualTo(0.7 - 1e-9);
                if (p.length > 1e-6) {
                    assertThat(Math.abs(p.depthEnd - p.depthStart) / p.length).isLessThanOrEqualTo(0.1 + 0.01);
                }
                if (p.special && p.types.contains("road")) {
                    assertThat(Math.min(p.depthStart, p.depthEnd)).isGreaterThanOrEqualTo(1.0 - 1e-9);
                }
            }
        }
        new ObjectMapper().writeValue(new File("target/demo-solution-depth.geojson"),
                new GeoJsonExporter(ctx, "EPSG:32637").export(depth.variants, new HashMap<>(), "depth"));
    }
}
