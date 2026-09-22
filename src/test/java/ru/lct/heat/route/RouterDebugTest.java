package ru.lct.heat.route;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;

import ru.lct.heat.TestSupport;
import ru.lct.heat.model.Scenario;

class RouterDebugTest {

    @Test
    void debugUnconnected() throws Exception {
        Context ctx = TestSupport.context("/lct-dataset.geojson");
        for (int k = 0; k < ctx.scenario.oks.size(); k++) {
            Scenario.Oks o = ctx.scenario.oks.get(k);
            int dn = ctx.rules.minDnForFlow(o.flow);
            ObstacleLayer layer = ctx.layer(dn);
            Context.Port port = ctx.port(k, dn);
            ExistingTargets t = new ExistingTargets(ctx, dn, new int[ctx.scenario.chambers.size()], List.of());
            Router.Result r = port == null ? null : new Router(layer, 200_000).route(Router.Start.of(port), t, Blockers.NONE);
            Point p = o.geom;
            System.out.printf("ОКС %s dn=%d own=%d порт=%s E=%s blockedE=%s dist=%.1f узлов=%d маршрут=%s%n",
                    o.id, dn, ctx.ownPolygon[k], port != null, port == null ? null : port.e,
                    port == null ? null : layer.isBlocked(port.e), t.minDistance(p.getCoordinate()), layer.nodeCount(),
                    r == null ? "НЕТ" : String.format("%.1f м, %d точек, %s", len(r.path), r.path.size(), r.goal.kind));
            if (r == null && port != null) {
                int visible = layer.neighborsOf(port.e).size();
                System.out.println("   видимых узлов из E: " + visible);
                List<Goal> goals = new java.util.ArrayList<>();
                t.candidates(port.e, goals);
                for (Goal g : goals) {
                    System.out.println("   цель " + g.kind + " " + g.point + " check=" + (layer.check(port.e, g.point, -1, g.exemptLines) != null));
                }
            }
        }
    }

    private static double len(List<Coordinate> path) {
        double l = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            l += path.get(i).distance(path.get(i + 1));
        }
        return l;
    }
}
