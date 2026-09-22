package ru.lct.heat.route;

import java.util.List;

import org.junit.jupiter.api.Test;

import ru.lct.heat.TestSupport;
import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;

class DemoTimingTest {

    @Test
    void timings() throws Exception {
        HeatProperties props = TestSupport.props();
        long t0 = System.currentTimeMillis();
        Context ctx = new Context(TestSupport.scenario("/static/demo-dataset.geojson", props), new Rules(props), 32637);
        System.out.println("ТАЙМИНГ контекст: " + (System.currentTimeMillis() - t0) + " мс");
        for (int k = 0; k < 4; k++) {
            int dn = ctx.rules.minDnForFlow(ctx.scenario.oks.get(k).flow);
            long t1 = System.currentTimeMillis();
            ObstacleLayer layer = ctx.layer(dn);
            long t2 = System.currentTimeMillis();
            List<Context.Port> ports = ctx.ports(k, dn);
            long t3 = System.currentTimeMillis();
            Router router = new Router(layer, 200_000);
            Router.Result r = ports.isEmpty() ? null : router.route(Router.Start.of(ports.get(0)),
                    new ExistingTargets(ctx, dn, new int[ctx.scenario.chambers.size()], List.of()), Blockers.NONE);
            long t4 = System.currentTimeMillis();
            System.out.printf("ТАЙМИНГ ОКС %s: слой %d мс (узлов %d), порты %d мс (%d), маршрут %d мс -> %s%n",
                    ctx.scenario.oks.get(k).id, t2 - t1, layer.nodeCount(), t3 - t2, ports.size(), t4 - t3,
                    r == null ? "НЕТ (limitHit=" + router.limitHit + ")" : "есть");
        }
    }
}
