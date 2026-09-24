package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;

import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;
import ru.lct.heat.route.Goal;
import ru.lct.heat.route.ObstacleLayer;
import ru.lct.heat.route.TargetSet;

/**
 * Цели на уже построенной новой сети: ветвление на ребре (новая камера) или в узле со свободным
 * примыканием. Цена узла - полное приращение S: камера + удорожание вышележащих участков,
 * у которых растёт расход и, возможно, ДУ (компромисс из раздела 6.4 rules.md).
 */
final class TreeTargets implements TargetSet {

    private static final int K_SEGMENTS = 6;
    private static final int K_NODES = 4;
    private static final double END_CLEARANCE = 0.5;

    private static final class Seg {
        final LineSegment seg;
        final Net.Edge edge;
        final int index;

        Seg(LineSegment seg, Net.Edge edge, int index) {
            this.seg = seg;
            this.edge = edge;
            this.index = index;
        }
    }

    private static final ItemDistance DIST = (a, b) -> {
        Object ia = a.getItem();
        Object ib = b.getItem();
        Seg s = (Seg) (ia instanceof Seg ? ia : ib);
        Coordinate c = (Coordinate) (ia instanceof Coordinate ? ia : ib);
        return s.seg.distance(c);
    };

    private final Net net;
    private final Context ctx;
    private final Rules rules;
    private final ObstacleLayer layer;
    private final double flow;
    private final int dnNew;
    private final STRtree index = new STRtree();
    private final int segCount;
    private final List<Net.Node> attachable = new ArrayList<>();
    private final Map<Integer, Double> deltaUp = new HashMap<>();
    private final Map<Integer, Integer> edgeNewDn = new HashMap<>();

    TreeTargets(Net net, Context ctx, ObstacleLayer layer, double flow, int dnNew) {
        this.net = net;
        this.ctx = ctx;
        this.rules = ctx.rules;
        this.layer = layer;
        this.flow = flow;
        this.dnNew = dnNew;
        int count = 0;
        for (Net.Edge e : net.edges.values()) {
            edgeNewDn.put(e.id, newDn(e));
            for (int i = e.fromOks ? 1 : 0; i + 1 < e.path.size(); i++) {
                LineSegment s = new LineSegment(e.path.get(i), e.path.get(i + 1));
                if (s.getLength() < 2 * END_CLEARANCE + 0.1) {
                    continue;
                }
                index.insert(new Envelope(s.p0, s.p1), new Seg(s, e, i));
                count++;
            }
        }
        segCount = count;
        index.build();
        for (Net.Node n : net.nodes.values()) {
            if (n.kind != Net.Kind.OKS && hasCapacity(n)) {
                attachable.add(n);
            }
        }
    }

    private int newDn(Net.Edge e) {
        int byFlow = rules.minDnForFlow(e.flow + flow);
        return Math.max(Math.max(e.dn, byFlow < 0 ? e.dn : byFlow), dnNew);
    }

    private boolean hasCapacity(Net.Node n) {
        int children = net.childEdges(n.id).size();
        switch (n.kind) {
            case NEW_CHAMBER:
                // врезка: примыкания линий в точке (насквозь = 2, стык - по концам); ветвление: 1 вверх + до 3
                return n.root ? ctx.tieAt(n.c).occupancy + children + 1 <= rules.maxConnections()
                        : children + 1 <= rules.maxConnections() - 1;
            case EXISTING_CHAMBER:
                return ctx.network.occupancy[n.chamber] + children + 1 <= rules.maxConnections();
            default:
                return false;
        }
    }

    /** Приращение S от узла вверх до врезки при росте расхода на flow. */
    private double deltaUp(int nodeId) {
        Double cached = deltaUp.get(nodeId);
        if (cached != null) {
            return cached;
        }
        Net.Node node = net.nodes.get(nodeId);
        double d = chamberDelta(node, -1, 0);
        Net.Edge pe = net.parentEdge(nodeId);
        if (pe != null) {
            int nd = edgeNewDn.get(pe.id);
            d += pe.length() * (rules.scorePerMeter(nd, 1) - rules.scorePerMeter(pe.dn, 1));
            d += deltaUp(pe.parent);
        }
        deltaUp.put(nodeId, d);
        return d;
    }

    /** Рост стоимости камеры в узле, если максимальный ДУ примыканий вырастет. */
    private double chamberDelta(Net.Node node, int extraEdge, int extraDn) {
        if (node.kind != Net.Kind.NEW_CHAMBER) {
            return 0;
        }
        int before = 0;
        int after = dnNew;
        for (Net.Edge e : net.edges.values()) {
            if (e.child == node.id || e.parent == node.id) {
                before = Math.max(before, e.dn);
                after = Math.max(after, edgeNewDn.getOrDefault(e.id, e.dn));
            }
        }
        if (node.root) {
            int lineDn = ctx.tieAt(node.c).maxDn;
            before = Math.max(before, lineDn);
            after = Math.max(after, lineDn);
        }
        after = Math.max(after, extraDn);
        return rules.costScore(rules.newChamberCost(after) - rules.newChamberCost(before));
    }

    @Override
    public void candidates(Coordinate from, List<Goal> out) {
        if (segCount > 0) {
            Object[] near = index.nearestNeighbour(new Envelope(from), from, DIST, Math.min(K_SEGMENTS, segCount));
            for (Object o : near) {
                addSplit((Seg) o, from, out);
            }
        }
        if (!attachable.isEmpty()) {
            List<Net.Node> sorted = new ArrayList<>(attachable);
            sorted.sort((a, b) -> Double.compare(a.c.distance(from), b.c.distance(from)));
            for (int i = 0; i < Math.min(K_NODES, sorted.size()); i++) {
                Net.Node n = sorted.get(i);
                double score = deltaUp(n.id);
                int[] exempt = null;
                if (n.kind == Net.Kind.EXISTING_CHAMBER) {
                    score += rules.costScore(rules.existingTieInCost());
                    exempt = ctx.network.chamberLines[n.chamber];
                } else if (n.root) {
                    exempt = ctx.tieAt(n.c).lines;
                }
                out.add(new Goal(Goal.Kind.TREE_NODE, n.c, score, n.id, exempt));
            }
        }
    }

    private void addSplit(Seg s, Coordinate from, List<Goal> out) {
        double len = s.seg.getLength();
        double t = s.seg.projectionFactor(from) * len;
        t = Math.max(END_CLEARANCE, Math.min(len - END_CLEARANCE, t));
        Coordinate x = s.seg.pointAlong(t / len);
        if (layer.isBlocked(x) || ctx.layer(ctx.maxDn, layer.mode).isBlocked(x)) {
            // камера не может стоять в спецпереходе или зоне отступа, в том числе после роста ДУ
            return;
        }
        Net.Edge e = s.edge;
        int nd = edgeNewDn.get(e.id);
        double upper = x.distance(s.seg.p1);
        for (int i = s.index + 1; i + 1 < e.path.size(); i++) {
            upper += e.path.get(i).distance(e.path.get(i + 1));
        }
        double score = rules.costScore(rules.newChamberCost(Math.max(nd, dnNew)))
                + upper * (rules.scorePerMeter(nd, 1) - rules.scorePerMeter(e.dn, 1))
                + chamberDelta(net.nodes.get(e.parent), -1, 0)
                + deltaUpFromEdgeParent(e);
        out.add(new Goal(Goal.Kind.SPLIT_EDGE, x, score, e.id, null));
    }

    private double deltaUpFromEdgeParent(Net.Edge e) {
        // deltaUp(parent) уже включает камеру в parent; не считаем её дважды
        Net.Node parent = net.nodes.get(e.parent);
        return deltaUp(e.parent) - chamberDelta(parent, -1, 0);
    }

    @Override
    public double minDistance(Coordinate from) {
        double best = Double.MAX_VALUE;
        if (segCount > 0) {
            Seg s = (Seg) index.nearestNeighbour(new Envelope(from), from, DIST);
            best = s.seg.distance(from);
        }
        for (Net.Node n : attachable) {
            best = Math.min(best, n.c.distance(from));
        }
        return best;
    }

    @Override
    public double minNodeScore() {
        return 0;
    }
}
