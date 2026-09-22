package ru.lct.heat.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

import ru.lct.heat.TestSupport;
import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.model.Scenario.SrcId;
import ru.lct.heat.route.Context;

/**
 * Нагрузочный сценарий: квартальная застройка 40×40 зданий, дороги между кварталами, 80 ОКС.
 * Запуск: mvn test -Dtest=StressTest -Dstress=true
 */
@EnabledIfSystemProperty(named = "stress", matches = "true")
class StressTest {

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);

    private Coordinate c(double x, double y) {
        return new Coordinate(414000 + x, 6173000 + y);
    }

    @Test
    void largeDistrict() throws Exception {
        Scenario s = new Scenario();
        Random rnd = new Random(7);
        int n = 40;
        double step = 32;
        s.sources.add(gf.createPoint(c(-60, -60)));
        s.network.add(new Scenario.NetworkLine(new SrcId("main", false), 600,
                gf.createLineString(new Coordinate[] {c(-60, -60), c(n * step + 60, -60), c(n * step + 60, n * step + 60)})));
        s.chambers.add(new Scenario.Chamber(new SrcId("ch", false), gf.createPoint(c(-60, -60))));
        int id = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double x = i * step;
                double y = j * step;
                double w = 10 + rnd.nextDouble() * 6;
                s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("b" + (++id), false), "oks",
                        gf.createPolygon(new Coordinate[] {c(x, y), c(x + w, y), c(x + w, y + w), c(x, y + w), c(x, y)})));
                if (rnd.nextDouble() < 0.05) {
                    s.oks.add(new Scenario.Oks(new SrcId(String.valueOf(s.oks.size() + 1), true), 3 + rnd.nextDouble() * 40,
                            gf.createPoint(c(x + w / 2, y + w / 2))));
                }
            }
        }
        // дорога поперёк района
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("road", false), "road",
                gf.createPolygon(new Coordinate[] {c(-100, 20 * step - 9), c(n * step + 100, 20 * step - 9),
                        c(n * step + 100, 20 * step - 1), c(-100, 20 * step - 1), c(-100, 20 * step - 9)})));

        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(60);
        long t0 = System.currentTimeMillis();
        Context ctx = new Context(s, new Rules(props), 32637);
        List<Solver.Candidate> variants = new Solver(ctx).solve();
        long ms = System.currentTimeMillis() - t0;
        Solver.Candidate best = variants.get(0);
        List<String> errors = new Validator(ctx).validate(best.net, best.eval);
        System.out.printf("СТРЕСС: %d зданий, %d ОКС, %d мс, S=%.3f, L=%.0f, неподкл=%d, нарушений=%d, память=%d МБ%n",
                n * n, s.oks.size(), ms, best.eval.score, best.eval.length, best.net.unconnected.size(), errors.size(),
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
        errors.stream().limit(10).forEach(e -> System.out.println("  " + e));
        assertThat(errors).isEmpty();
        assertThat(best.net.unconnected).isEmpty();
    }
}
