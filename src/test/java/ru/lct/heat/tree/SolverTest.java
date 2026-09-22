package ru.lct.heat.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.lct.heat.TestSupport;
import ru.lct.heat.route.Context;

class SolverTest {

    private static Context ctx;

    @BeforeAll
    static void load() throws Exception {
        ctx = TestSupport.context("/lct-dataset.geojson");
        ctx.rules.props().getSolver().setTimeBudgetS(20);
    }

    @Test
    void configIsBound() {
        assertThat(ctx.rules.pipes()).hasSize(18);
        assertThat(ctx.rules.minDnForFlow(24.87)).isEqualTo(125);
        assertThat(ctx.rules.newChamberCost(400)).isEqualTo(5_000_000);
        assertThat(ctx.rules.restriction("social_area").getOffset()).isEqualTo(1.0);
        assertThat(ctx.rules.offset(ctx.rules.restriction("oks"), 600)).isEqualTo(7.0);
    }

    @Test
    void solvesCompetitionDataset() {
        long t0 = System.currentTimeMillis();
        Solver solver = new Solver(ctx);
        List<Solver.Candidate> variants = solver.solve();
        long ms = System.currentTimeMillis() - t0;

        System.out.println("время: " + ms + " мс, диагностика: " + ctx.diagnostics);
        for (Solver.Candidate c : variants) {
            Eval e = c.eval;
            System.out.printf("%s: S=%.4f C=%.0f L=%.1f камер=%.0f врезок в сущ.=%d неподкл=%s невалидных=%d заметки=%s%n",
                    c.strategy, e.score, e.calculatedCost, e.length, e.chamberCost, e.existingTieInCount,
                    c.net.unconnected, e.invalidSegments, c.net.notes);
        }
        Validator validator = new Validator(ctx);
        for (Solver.Candidate c : variants) {
            List<String> errors = validator.validate(c.net, c.eval);
            System.out.println(c.strategy + " проверка: " + errors);
            assertThat(errors).as(c.strategy).isEmpty();
        }
        assertThat(variants).isNotEmpty();
        Solver.Candidate best = variants.get(0);
        assertThat(best.eval.invalidSegments).isZero();
        assertThat(best.net.unconnected).isEmpty();
    }
}
