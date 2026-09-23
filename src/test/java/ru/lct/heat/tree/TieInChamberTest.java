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

/** R-TIE-02: рядом со свободной существующей камерой новая камера-врезка не строится. */
class TieInChamberTest {

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);

    private Coordinate c(double x, double y) {
        return new Coordinate(414000 + x, 6173000 + y);
    }

    /** Сеть по оси X, ОКС на высоте 120 м; существующая камера в dx метрах от естественной врезки. */
    private Solver.Candidate solve(double chamberDx, int occupiedLines) throws Exception {
        Scenario s = new Scenario();
        s.sources.add(gf.createPoint(c(-300, 0)));
        // две линии стыкуются в камере: это 2 занятых примыкания из 4
        s.network.add(new Scenario.NetworkLine(new SrcId("n1", false), 400,
                gf.createLineString(new Coordinate[] {c(-300, 0), c(chamberDx, 0)})));
        if (occupiedLines > 1) {
            s.network.add(new Scenario.NetworkLine(new SrcId("n2", false), 400,
                    gf.createLineString(new Coordinate[] {c(chamberDx, 0), c(300, 0)})));
        }
        s.chambers.add(new Scenario.Chamber(new SrcId("ch1", false), gf.createPoint(c(chamberDx, 0))));
        s.restrictions.add(new Scenario.RestrictionFeature(new SrcId("b1", false), "oks",
                gf.createPolygon(new Coordinate[] {c(-10, 120), c(10, 120), c(10, 140), c(-10, 140), c(-10, 120)})));
        s.oks.add(new Scenario.Oks(new SrcId("1", true), 30.0, gf.createPoint(c(0, 125))));

        HeatProperties props = TestSupport.props();
        props.getSolver().setTimeBudgetS(3);
        Context ctx = new Context(s, new Rules(props), 32637);
        List<Solver.Candidate> variants = new Solver(ctx).solve();
        assertThat(new Validator(ctx).validate(variants.get(0).net, variants.get(0).eval)).isEmpty();
        return variants.get(0);
    }

    @Test
    void usesExistingChamberWithinTenMeters() throws Exception {
        // камера в 5 м от точки, куда трасса вышла бы сама
        Solver.Candidate best = solve(5, 2);
        long existing = best.net.nodes.values().stream().filter(n -> n.kind == Net.Kind.EXISTING_CHAMBER).count();
        long newTieIns = best.net.nodes.values().stream().filter(n -> n.root && n.kind == Net.Kind.NEW_CHAMBER).count();
        assertThat(existing).as("врезка в существующую камеру").isEqualTo(1);
        assertThat(newTieIns).as("новых камер-врезок быть не должно").isZero();
        assertThat(best.eval.existingTieInCount).isEqualTo(1);
        assertThat(best.eval.chamberCost).isZero();
    }

    @Test
    void buildsNewChamberWhenExistingIsFar() throws Exception {
        // камера в 120 м: правило 10 м не действует, выгоднее своя камера на линии
        Solver.Candidate best = solve(120, 2);
        long newTieIns = best.net.nodes.values().stream().filter(n -> n.root && n.kind == Net.Kind.NEW_CHAMBER).count();
        assertThat(newTieIns).isEqualTo(1);
        assertThat(best.eval.existingTieInCount).isZero();
    }
}
