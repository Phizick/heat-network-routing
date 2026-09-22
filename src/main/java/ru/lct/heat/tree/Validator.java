package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygonal;

import ru.lct.heat.config.HeatProperties.Restriction;
import ru.lct.heat.config.HeatProperties.Rule;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Context;

/**
 * Независимая проверка готового варианта по правилам (коды R-... из docs/rules.md).
 * Считает по исходной геометрии ограничений, а не по расширенным зонам оптимизатора,
 * поэтому ловит и ошибки самого оптимизатора.
 */
public final class Validator {

    /** Допуск на численные погрешности, м. */
    private static final double TOL = 0.02;

    private final Context ctx;
    private final Rules rules;

    public Validator(Context ctx) {
        this.ctx = ctx;
        this.rules = ctx.rules;
    }

    public List<String> validate(Net net, Eval eval) {
        List<String> out = new ArrayList<>();
        structure(net, out);
        angles(net, out);
        crossings(net, out);
        clearances(net, eval, out);
        sizing(net, out);
        return out;
    }

    private void structure(Net net, List<String> out) {
        Map<Integer, Integer> parents = new HashMap<>();
        for (Net.Edge e : net.edges.values()) {
            if (!net.nodes.containsKey(e.child) || !net.nodes.containsKey(e.parent)) {
                out.add("R-TOP-02: ребро " + e.id + " ссылается на несуществующий узел");
                continue;
            }
            if (parents.put(e.child, e.parent) != null) {
                out.add("R-TOP-02: у узла " + e.child + " больше одного пути к врезке");
            }
            if (e.path.get(0).distance(net.nodes.get(e.child).c) > 1e-6
                    || e.path.get(e.path.size() - 1).distance(net.nodes.get(e.parent).c) > 1e-6) {
                out.add("R-OUT-01: концы ребра " + e.id + " не совпадают с узлами");
            }
        }
        for (Net.Node n : net.nodes.values()) {
            if (n.root && parents.containsKey(n.id)) {
                out.add("R-TOP-02: врезка " + n.id + " имеет ребро вверх");
            }
            if (!n.root && !parents.containsKey(n.id)) {
                out.add("R-TOP-02: узел " + n.id + " не связан с врезкой");
            }
            // цикл: подъём должен закончиться во врезке
            int cur = n.id;
            int steps = 0;
            while (parents.containsKey(cur) && steps++ <= net.nodes.size()) {
                cur = parents.get(cur);
            }
            if (steps > net.nodes.size()) {
                out.add("R-TOP-02: цикл через узел " + n.id);
            }
            int children = net.childEdges(n.id).size();
            int connections;
            switch (n.kind) {
                case NEW_CHAMBER:
                    connections = children + (n.root ? ctx.tieAt(n.c).occupancy : 1);
                    if (!n.root && children < 2) {
                        out.add("R-TOP-04: камера " + n.id + " без разветвления");
                    }
                    break;
                case EXISTING_CHAMBER:
                    connections = children + ctx.network.occupancy[n.chamber];
                    break;
                default:
                    connections = 1;
                    if (children > 0) {
                        out.add("R-TOP-08: ОКС " + n.oks + " не лист");
                    }
            }
            if (connections > rules.maxConnections()) {
                out.add("R-TOP-05: у узла " + n.id + " " + connections + " примыканий");
            }
        }
        // R-TIE-02: новая врезка в 10 м от существующей камеры, где хватает свободных примыканий
        double radius = rules.props().getChambers().getExistingSnapRadiusM();
        for (Net.Node n : net.nodes.values()) {
            if (!n.root || n.kind != Net.Kind.NEW_CHAMBER) {
                continue;
            }
            int children = net.childEdges(n.id).size();
            for (int c = 0; c < ctx.scenario.chambers.size(); c++) {
                double d = ctx.scenario.chambers.get(c).geom.getCoordinate().distance(n.c);
                if (d > radius) {
                    continue;
                }
                Net.Node used = net.existingChamberRoot(c);
                int busy = ctx.network.occupancy[c] + (used == null ? 0 : net.childEdges(used.id).size());
                if (busy + children <= rules.maxConnections()) {
                    out.add(String.format("R-TIE-02: новая врезка в %.1f м от камеры %s со свободными примыканиями",
                            d, ctx.scenario.chambers.get(c).id));
                }
            }
        }
        Set<Integer> connected = new HashSet<>();
        for (Net.Node n : net.nodes.values()) {
            if (n.kind == Net.Kind.OKS) {
                connected.add(n.oks);
            }
        }
        for (int k = 0; k < ctx.scenario.oks.size(); k++) {
            if (!connected.contains(k) && !net.unconnected.containsKey(k)) {
                out.add("R-UNC-02: ОКС " + ctx.scenario.oks.get(k).id + " не подключена и не указана в неподключённых");
            }
        }
    }

    private void angles(Net net, List<String> out) {
        double maxDeg = rules.props().getGeometry().getMaxTurnAngleDeg();
        for (Net.Edge e : net.edges.values()) {
            for (int i = 1; i + 1 < e.path.size(); i++) {
                Coordinate a = e.path.get(i - 1);
                Coordinate v = e.path.get(i);
                Coordinate b = e.path.get(i + 1);
                double ang = Math.toDegrees(Math.abs(Math.atan2(
                        (v.x - a.x) * (b.y - v.y) - (v.y - a.y) * (b.x - v.x),
                        (v.x - a.x) * (b.x - v.x) + (v.y - a.y) * (b.y - v.y))));
                if (ang > maxDeg + 1e-6) {
                    out.add(String.format("R-GEO-02: поворот %.1f° на ребре %d", ang, e.id));
                }
            }
        }
    }

    private void crossings(Net net, List<String> out) {
        List<Net.Edge> edges = new ArrayList<>(net.edges.values());
        for (int x = 0; x < edges.size(); x++) {
            for (int y = x + 1; y < edges.size(); y++) {
                Net.Edge a = edges.get(x);
                Net.Edge b = edges.get(y);
                Set<Coordinate> shared = new HashSet<>();
                for (int n : new int[] {a.child, a.parent}) {
                    if (n == b.child || n == b.parent) {
                        shared.add(net.nodes.get(n).c);
                    }
                }
                for (int i = 0; i + 1 < a.path.size(); i++) {
                    LineSegment sa = new LineSegment(a.path.get(i), a.path.get(i + 1));
                    for (int j = 0; j + 1 < b.path.size(); j++) {
                        LineSegment sb = new LineSegment(b.path.get(j), b.path.get(j + 1));
                        if (sa.distance(sb) > 1e-7) {
                            continue;
                        }
                        Coordinate p = sa.intersection(sb);
                        boolean atShared = false;
                        for (Coordinate c : shared) {
                            if (p != null && p.distance(c) < 1e-6) {
                                atShared = true;
                            }
                        }
                        if (!atShared) {
                            out.add("R-TOP-06: рёбра " + a.id + " и " + b.id + " пересекаются вне общего узла");
                        }
                    }
                }
            }
        }
    }

    private void clearances(Net net, Eval eval, List<String> out) {
        for (Net.Edge e : net.edges.values()) {
            double half = rules.width(e.dn) / 2;
            Net.Node parent = net.nodes.get(e.parent);
            Net.Node child = net.nodes.get(e.child);
            int own = child.kind == Net.Kind.OKS && e.fromOks ? ctx.ownPolygon[child.oks] : -1;
            List<Eval.Part> parts = eval.parts.getOrDefault(e.id, List.of());
            for (int pi = 0; pi < parts.size(); pi++) {
                Eval.Part part = parts.get(pi);
                LineString geom = ctx.gf.createLineString(part.coords.toArray(new Coordinate[0]));
                if (part.special) {
                    if (part.coords.size() != 2) {
                        out.add("R-RST-05: спецпроход ребра " + e.id + " не один прямой отрезок");
                    }
                    continue;
                }
                boolean nearSpecial = (pi > 0 && parts.get(pi - 1).special) || (pi + 1 < parts.size() && parts.get(pi + 1).special);
                for (int idx : ctx.restrictionsInArea) {
                    Scenario.RestrictionFeature r = ctx.scenario.restrictions.get(idx);
                    Restriction rule = rules.restriction(r.type);
                    double need = rules.offset(rule, e.dn) + half + rule.getOwnWidth() / 2;
                    if (!geom.getEnvelopeInternal().intersects(expand(r.geom, need))) {
                        continue;
                    }
                    if (rule.getRule() == Rule.SPECIAL && !(r.geom instanceof Polygonal) && nearSpecial) {
                        // допущение 3: коридор линейной коммуникации пересекается насквозь рядом со спецучастком
                        continue;
                    }
                    LineString checked = geom;
                    if (idx == own) {
                        // R-OKS-02: отступ до своего здания не действует только на финальном отрезке
                        checked = restAfterFirstSegment(part, pi);
                        if (checked == null) {
                            continue;
                        }
                    }
                    double d = checked.distance(r.geom);
                    if (d + TOL < need) {
                        out.add(String.format("R-RST-02: ребро %d (ДУ %d) в %.2f м от %s %s, нужно %.2f м",
                                e.id, e.dn, d, r.type, r.id, need));
                    }
                }
                int[] tieLines = parent.kind == Net.Kind.EXISTING_CHAMBER ? ctx.network.chamberLines[parent.chamber]
                        : parent.root ? ctx.tieAt(parent.c).lines : new int[0];
                for (int li = 0; li < ctx.scenario.network.size(); li++) {
                    Scenario.NetworkLine line = ctx.scenario.network.get(li);
                    if (nearSpecial) {
                        continue;
                    }
                    double need = rules.offset(rules.props().getExistingNetworkCrossing(), e.dn) + half + rules.width(line.dn) / 2;
                    LineString checked = geom;
                    if (pi == parts.size() - 1 && contains(tieLines, li)) {
                        // подход к точке врезки: отступ до своей линии не действует только на последних метрах
                        double keep = part.length - ru.lct.heat.route.ObstacleLayer.approachLimit(need + 0.4);
                        if (keep <= 1e-6) {
                            continue;
                        }
                        checked = ctx.gf.createLineString(DepthProfiler.slice(part.coords, 0, keep).toArray(new Coordinate[0]));
                    }
                    double d = checked.distance(line.geom);
                    if (d + TOL < need) {
                        out.add(String.format("R-RST-03: ребро %d (ДУ %d) в %.2f м от сети %s, нужно %.2f м", e.id, e.dn, d, line.id, need));
                    }
                }
            }
        }
    }

    private LineString restAfterFirstSegment(Eval.Part part, int partIndex) {
        if (partIndex != 0) {
            return ctx.gf.createLineString(part.coords.toArray(new Coordinate[0]));
        }
        if (part.coords.size() <= 2) {
            return null;
        }
        return ctx.gf.createLineString(part.coords.subList(1, part.coords.size()).toArray(new Coordinate[0]));
    }

    private static org.locationtech.jts.geom.Envelope expand(Geometry g, double d) {
        org.locationtech.jts.geom.Envelope env = new org.locationtech.jts.geom.Envelope(g.getEnvelopeInternal());
        env.expandBy(d + 1);
        return env;
    }

    private void sizing(Net net, List<String> out) {
        Map<Integer, Double> subtree = new HashMap<>();
        for (Net.Edge e : net.edges.values()) {
            double f = subtreeFlow(net, e.child, subtree);
            if (Math.abs(f - e.flow) > 1e-6) {
                out.add("R-FLOW-01: расход ребра " + e.id + " " + e.flow + " вместо " + f);
            }
            int minDn = rules.minDnForFlow(e.flow);
            if (minDn > 0 && e.dn < minDn) {
                out.add("R-DN-01: ДУ " + e.dn + " меньше требуемого " + minDn + " на ребре " + e.id);
            }
            for (Net.Edge c : net.childEdges(e.child)) {
                if (c.dn > e.dn) {
                    out.add("R-DN-04: ДУ убывает к врезке на ребре " + e.id);
                }
            }
        }
        for (Net.Node n : net.nodes.values()) {
            if (n.kind != Net.Kind.OKS) {
                continue;
            }
            List<Net.Edge> path = new ArrayList<>();
            Net.Edge e = net.parentEdge(n.id);
            while (e != null && path.size() <= net.edges.size()) {
                path.add(e);
                e = net.parentEdge(e.parent);
            }
            int i = 0;
            while (i < path.size()) {
                int dn = path.get(i).dn;
                double len = 0;
                int j = i;
                while (j < path.size() && path.get(j).dn == dn) {
                    len += path.get(j).length();
                    j++;
                }
                if (len > rules.lMax(dn) + 1e-6) {
                    out.add(String.format("R-LEN-02: от ОКС %s фрагмент ДУ %d длиной %.1f м > %.0f м",
                            ctx.scenario.oks.get(n.oks).id, dn, len, rules.lMax(dn)));
                }
                i = j;
            }
        }
    }

    private double subtreeFlow(Net net, int node, Map<Integer, Double> memo) {
        Double m = memo.get(node);
        if (m != null) {
            return m;
        }
        Net.Node n = net.nodes.get(node);
        double f = n.kind == Net.Kind.OKS ? ctx.scenario.oks.get(n.oks).flow : 0;
        for (Net.Edge c : net.childEdges(node)) {
            f += subtreeFlow(net, c.child, memo);
        }
        memo.put(node, f);
        return f;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }
}
