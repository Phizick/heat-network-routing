package ru.lct.heat.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
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
 * Синтетический сценарий со спецпереходами (в конкурсном датасете их нет):
 * сеть внизу, выше - дорога (полоса), газопровод, здание ОКС наверху.
 * Трасса обязана пересечь дорогу одним прямым отрезком под углом ≥ 45° с зоной полигон + 3 м.
 */
class SpecialCrossingTest {

    private static final double X0 = 414000;
    private static final double Y0 = 6173000;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);

    private Coordinate c(double x, double y) {
        return new Coordinate(X0 + x, Y0 + y);
    }

    private Scenario scenario(boolean withGas, boolean diagonalRoad) {
        Scenario s = new Scenario();
        s.sources.add(gf.createPoint(c(-300, 0)));
        s.network.add(new Scenario.NetworkLine(new SrcId("n1", false), 400,
                gf.createLineString(new Coordinate[] {c(-300, 0), c(300, 0)})));
        s.chambers.add(new Scenario.Chamber(new SrcId("ch1", false), gf.createPoint(c(-300, 0))));
        // здание ОКС 20×20, точка внутри; при диагональной дороге здание выше неё
        double by = diagonalRoad ? 330 : 100;
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("b1", false), "oks",
                gf.createPolygon(new Coordinate[] {c(-10, by), c(10, by), c(10, by + 20), c(-10, by + 20), c(-10, by)})));
        s.oks.add(new Scenario.Oks(new SrcId("1", true), 30.0, gf.createPoint(c(0, by + 5))));
        if (diagonalRoad) {
            // дорога под 30° к оси: пересекать поперёк можно, вдоль - нельзя
            s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("r1", false), "road",
                    gf.createPolygon(new Coordinate[] {c(-400, 20), c(400, 480), c(400, 495), c(-400, 35), c(-400, 20)})));
        } else {
            s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("r1", false), "road",
                    gf.createPolygon(new Coordinate[] {c(-400, 40), c(400, 40), c(400, 55), c(-400, 55), c(-400, 40)})));
        }
        if (withGas) {
            s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("g1", false), "gas_pipeline",
                    gf.createLineString(new Coordinate[] {c(-400, 75), c(400, 75)})));
        }
        return s;
    }

    private List<Solver.Candidate> solve(Scenario s) throws Exception {
        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(3);
        props.getSolver().setGreedyRestarts(2);
        Context ctx = new Context(s, new Rules(props), 32637);
        List<Solver.Candidate> variants = new Solver(ctx).solve();
        Validator validator = new Validator(ctx);
        for (Solver.Candidate cand : variants) {
            assertThat(validator.validate(cand.net, cand.eval)).as(cand.strategy).isEmpty();
        }
        return variants;
    }

    @Test
    void crossesRoadAndGasWithSpecialParts() throws Exception {
        List<Solver.Candidate> variants = solve(scenario(true, false));
        Solver.Candidate best = variants.get(0);
        assertThat(best.net.unconnected).isEmpty();
        assertThat(best.eval.invalidSegments).isZero();
        List<Eval.Part> parts = best.eval.parts.values().iterator().next();
        List<Eval.Part> special = parts.stream().filter(p -> p.special).collect(java.util.stream.Collectors.toList());
        assertThat(special).extracting(p -> p.types).contains("road", "gas_pipeline");
        Eval.Part road = special.stream().filter(p -> p.types.contains("road")).findFirst().orElseThrow();
        // дорога 15 м + 3 м с каждой стороны, при переходе под прямым углом
        assertThat(road.length).isBetween(21.0 - 1e-6, 21.0 * 1.42);
        assertThat(road.k).isEqualTo(1.60);
        Eval.Part gas = special.stream().filter(p -> p.types.contains("gas")).findFirst().orElseThrow();
        assertThat(gas.length).isBetween(4.0 - 1e-6, 4.0 + 1e-6);
        assertThat(gas.k).isEqualTo(1.25);
    }

    @Test
    void diagonalRoadIsCrossedAtLeast45Degrees() throws Exception {
        List<Solver.Candidate> variants = solve(scenario(false, true));
        Solver.Candidate best = variants.get(0);
        assertThat(best.net.unconnected).isEmpty();
        Eval.Part road = best.eval.parts.values().iterator().next().stream()
                .filter(p -> p.special).findFirst().orElseThrow();
        Coordinate a = road.coords.get(0);
        Coordinate b = road.coords.get(1);
        double routeDeg = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        double roadDeg = Math.toDegrees(Math.atan2(460, 800));
        double diff = Math.abs(((routeDeg - roadDeg) % 180 + 180) % 180);
        double acute = Math.min(diff, 180 - diff);
        assertThat(acute).isGreaterThanOrEqualTo(45 - 1e-6);
    }
}
