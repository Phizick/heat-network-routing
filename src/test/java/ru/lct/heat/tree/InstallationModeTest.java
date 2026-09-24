package ru.lct.heat.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import ru.lct.heat.TestSupport;
import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;
import ru.lct.heat.solve.GeoJsonExporter;

/** Монтажный режим: углы 45/90 по сетке квартала, вдоль улиц, без параллельных прокладок. */
class InstallationModeTest {

    @Test
    void buildsGridAlignedNetwork() throws Exception {
        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(15);
        Context ctx = new Context(TestSupport.scenario("/lct-dataset.geojson", props), new Rules(props), 32637);
        long t0 = System.currentTimeMillis();
        List<Solver.Candidate> variants = new Solver(ctx, 15, HeatProperties.Mode.INSTALLATION).solve();
        long ms = System.currentTimeMillis() - t0;
        Validator validator = new Validator(ctx);
        Map<Integer, List<String>> validation = new HashMap<>();
        for (int v = 0; v < variants.size(); v++) {
            validation.put(v, validator.validate(variants.get(v).net, variants.get(v).eval));
        }
        new ObjectMapper().writeValue(new File("target/installation.geojson"),
                new GeoJsonExporter(ctx, "EPSG:32637").export(variants, validation, "2d"));

        Solver.Candidate best = variants.get(0);
        double grid = Math.toDegrees(ctx.gridAngle());
        // углы поворотов: считаем, сколько из них кратны 45°
        int total = 0;
        int aligned = 0;
        for (Net.Edge e : best.net.edges.values()) {
            for (int i = 1; i + 1 < e.path.size(); i++) {
                Coordinate a = e.path.get(i - 1);
                Coordinate b = e.path.get(i);
                Coordinate c = e.path.get(i + 1);
                double ang = Math.abs(Math.toDegrees(Math.atan2(
                        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x),
                        (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y))));
                total++;
                double mod = Math.abs(ang % 45);
                if (Math.min(mod, 45 - mod) < 2) {
                    aligned++;
                }
            }
        }
        // перемычки: короткие участки не по сетке, которыми ветка добирает до узла
        int stubs = 0;
        double stubLength = 0;
        double offGrid = 0;
        for (Net.Edge e : best.net.edges.values()) {
            ru.lct.heat.route.ObstacleLayer layer = ctx.layer(e.dn, HeatProperties.Mode.INSTALLATION);
            for (int i = e.fromOks ? 1 : 0; i + 1 < e.path.size(); i++) {
                Coordinate a = e.path.get(i);
                Coordinate b = e.path.get(i + 1);
                if (layer.gridAligned(a, b)) {
                    continue;
                }
                offGrid += a.distance(b);
                if (a.distance(b) <= layer.gridStep() * 1.5) {
                    stubs++;
                    stubLength += a.distance(b);
                }
            }
        }
        System.out.printf("МОНТАЖ: перемычек %d на %.0f м, вне сетки всего %.0f м%n", stubs, stubLength, offGrid);
        System.out.printf("МОНТАЖ: %d мс, S=%.3f, L=%.0f м, неподкл=%d, нарушений=%d, сетка %.1f°, поворотов %d из них кратны 45°: %d%n",
                ms, best.eval.score, best.eval.length, best.net.unconnected.size(),
                validation.get(0).size(), grid, total, aligned);
        validation.get(0).stream().limit(5).forEach(x -> System.out.println("   " + x));
        assertThat(variants).isNotEmpty();
    }
}
