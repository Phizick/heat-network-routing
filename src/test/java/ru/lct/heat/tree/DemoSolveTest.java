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
    }
}
