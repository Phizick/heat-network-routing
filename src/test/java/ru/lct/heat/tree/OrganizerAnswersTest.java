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
 * Случаи из ответов организаторов, которых нет в конкурсном датасете, но они возможны в проверочном:
 * дорога, заданная линией; неизвестный тип ограничения; врезка рядом с местом пересечения линии.
 */
class OrganizerAnswersTest {

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);

    private Coordinate c(double x, double y) {
        return new Coordinate(414000 + x, 6173000 + y);
    }

    private Scenario base() {
        Scenario s = new Scenario();
        s.sources.add(gf.createPoint(c(-300, 0)));
        s.network.add(new Scenario.NetworkLine(new SrcId("n1", false), 400,
                gf.createLineString(new Coordinate[] {c(-300, 0), c(300, 0)})));
        s.chambers.add(new Scenario.Chamber(new SrcId("ch1", false), gf.createPoint(c(-300, 0))));
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("b1", false), "oks",
                gf.createPolygon(new Coordinate[] {c(-10, 120), c(10, 120), c(10, 140), c(-10, 140), c(-10, 120)})));
        s.oks.add(new Scenario.Oks(new SrcId("1", true), 30.0, gf.createPoint(c(0, 125))));
        return s;
    }

    private Solver.Candidate solve(Scenario s) throws Exception {
        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(3);
        Context ctx = new Context(s, new Rules(props), 32637);
        List<Solver.Candidate> variants = new Solver(ctx).solve();
        assertThat(new Validator(ctx).validate(variants.get(0).net, variants.get(0).eval))
                .as("нарушения правил").isEmpty();
        return variants.get(0);
    }

    @Test
    void roadAsLineGetsSpecialZoneFromCentreLine() throws Exception {
        Scenario s = base();
        // дорога задана линией, а не полигоном: спецучасток отмеряется от неё ±3 м
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("r1", false), "road",
                gf.createLineString(new Coordinate[] {c(-400, 60), c(400, 60)})));
        Solver.Candidate best = solve(s);
        assertThat(best.net.unconnected).isEmpty();
        List<Eval.Part> special = best.eval.parts.values().stream().flatMap(List::stream)
                .filter(p -> p.special).collect(java.util.stream.Collectors.toList());
        assertThat(special).as("спецпереход через дорогу-линию").isNotEmpty();
        Eval.Part road = special.get(0);
        assertThat(road.types).contains("road");
        assertThat(road.k).isEqualTo(1.60);
        // при переходе под прямым углом полоса ±3 м даёт около 6 м, под углом - длиннее
        assertThat(road.length).isBetween(6.0 - 0.5, 6.0 * 1.45);
    }

    @Test
    void unknownRestrictionTypeIsIgnored() throws Exception {
        Scenario s = base();
        // тип без правил в таблице 2: по ответу организаторов игнорируем, трасса идёт прямо через него
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("x1", false), "metro_entrance",
                gf.createPolygon(new Coordinate[] {c(-30, 40), c(30, 40), c(30, 80), c(-30, 80), c(-30, 40)})));
        Solver.Candidate best = solve(s);
        assertThat(best.net.unconnected).isEmpty();
        double length = best.eval.length;
        // без запрета трасса почти прямая: от точки до сети около 125 м
        assertThat(length).isLessThan(150.0);
    }

    @Test
    void tieInKeepsDistanceFromCrossing() throws Exception {
        Scenario s = new Scenario();
        s.sources.add(gf.createPoint(c(-300, 0)));
        // «шпилька»: две ветки одной линии в 1,5 м друг от друга. Врезка в дальнюю ветку означала бы
        // пересечение ближней в полутора метрах от врезки - по ответу организаторов так нельзя
        s.network.add(new Scenario.NetworkLine(new SrcId("n1", false), 400, gf.createLineString(new Coordinate[] {
                c(-300, 0), c(300, 0), c(300, 1.5), c(-300, 1.5)})));
        s.chambers.add(new Scenario.Chamber(new SrcId("ch1", false), gf.createPoint(c(-300, 0))));
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("b1", false), "oks",
                gf.createPolygon(new Coordinate[] {c(-10, 100), c(10, 100), c(10, 120), c(-10, 120), c(-10, 100)})));
        s.oks.add(new Scenario.Oks(new SrcId("1", true), 20.0, gf.createPoint(c(0, 105))));

        Solver.Candidate best = solve(s);
        assertThat(best.net.unconnected).isEmpty();
        double minDistance = Double.MAX_VALUE;
        int crossings = 0;
        for (Net.Node n : best.net.nodes.values()) {
            if (!n.root) {
                continue;
            }
            for (Net.Edge e : best.net.edges.values()) {
                org.locationtech.jts.geom.Geometry path = gf.createLineString(e.path.toArray(new Coordinate[0]));
                for (Scenario.NetworkLine line : s.network) {
                    org.locationtech.jts.geom.Geometry x = path.intersection(line.geom);
                    for (int g = 0; g < x.getNumGeometries(); g++) {
                        double d = x.getGeometryN(g).getCoordinate().distance(n.c);
                        if (d > 1e-6) {
                            crossings++;
                            minDistance = Math.min(minDistance, d);
                        }
                    }
                }
            }
        }
        System.out.printf("ВРЕЗКА: пересечений %d, ближайшее в %.2f м%n", crossings,
                minDistance == Double.MAX_VALUE ? -1 : minDistance);
        assertThat(minDistance).as("расстояние от врезки до пересечения той же линии").isGreaterThanOrEqualTo(2.0);
    }
}
