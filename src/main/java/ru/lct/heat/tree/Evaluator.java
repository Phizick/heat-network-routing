package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;

import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;
import ru.lct.heat.route.ObstacleLayer;
import ru.lct.heat.route.SegInfo;

/** Считает стоимость и S варианта, заодно режет рёбра на участки base/special. */
public final class Evaluator {

    private final Context ctx;
    private final Rules rules;

    public Evaluator(Context ctx) {
        this.ctx = ctx;
        this.rules = ctx.rules;
    }

    /** Исключения проверки для отрезка i ребра: свой полигон ОКС и коридор линии врезки. */
    int ownExemption(Net net, Net.Edge e, int i) {
        if (i != 0 || !e.fromOks) {
            return -1;
        }
        Net.Node child = net.nodes.get(e.child);
        return ctx.ownPolygon[child.oks];
    }

    int[] lineExemption(Net net, Net.Edge e, int i) {
        if (i != e.path.size() - 2) {
            return null;
        }
        Net.Node parent = net.nodes.get(e.parent);
        if (parent.kind == Net.Kind.EXISTING_CHAMBER) {
            return ctx.network.chamberLines[parent.chamber];
        }
        if (parent.line >= 0) {
            return new int[] {parent.line};
        }
        return null;
    }

    public SegInfo check(Net net, Net.Edge e, int i) {
        ObstacleLayer layer = ctx.layer(e.dn);
        return layer.check(e.path.get(i), e.path.get(i + 1), ownExemption(net, e, i), lineExemption(net, e, i));
    }

    public Eval evaluate(Net net) {
        Eval ev = new Eval();
        for (Net.Edge e : net.edges.values()) {
            List<Eval.Part> parts = new ArrayList<>();
            List<Coordinate> current = new ArrayList<>();
            current.add(e.path.get(0));
            double currentLen = 0;
            for (int i = 0; i + 1 < e.path.size(); i++) {
                Coordinate a = e.path.get(i);
                Coordinate b = e.path.get(i + 1);
                SegInfo info = check(net, e, i);
                if (info == null) {
                    ev.invalidSegments++;
                    ev.problems.add("участок ребра " + e.id + " (ДУ " + e.dn + ") нарушает ограничения");
                    info = new SegInfo(a.distance(b), List.of());
                }
                for (SegInfo.Part p : info.parts()) {
                    Coordinate from = along(a, b, p.from, info.length);
                    Coordinate to = along(a, b, p.to, info.length);
                    if (!p.special()) {
                        current.add(to);
                        currentLen += p.length();
                        continue;
                    }
                    if (currentLen > 1e-6) {
                        parts.add(part(current, false, 1.0, "", currentLen, e.dn));
                    }
                    List<Coordinate> sp = new ArrayList<>();
                    sp.add(from);
                    sp.add(to);
                    parts.add(part(sp, true, p.k, p.types, p.length(), e.dn));
                    current = new ArrayList<>();
                    current.add(to);
                    currentLen = 0;
                }
            }
            if (currentLen > 1e-6) {
                parts.add(part(current, false, 1.0, "", currentLen, e.dn));
            }
            for (Eval.Part p : parts) {
                ev.pipeCost += p.cost;
                ev.length += p.length;
            }
            ev.parts.put(e.id, parts);
        }

        for (Net.Node n : net.nodes.values()) {
            if (n.kind == Net.Kind.NEW_CHAMBER) {
                int maxDn = n.line >= 0 ? ctx.scenario.network.get(n.line).dn : 0;
                for (Net.Edge e : net.edges.values()) {
                    if (e.child == n.id || e.parent == n.id) {
                        maxDn = Math.max(maxDn, e.dn);
                    }
                }
                double cost = rules.newChamberCost(maxDn);
                ev.chamberCost += cost;
                ev.chamberCosts.put(n.id, cost);
                ev.chamberDn.put(n.id, maxDn);
            } else if (n.kind == Net.Kind.EXISTING_CHAMBER) {
                int count = net.childEdges(n.id).size();
                ev.existingTieInCount += count;
                ev.existingTieInCost += count * rules.existingTieInCost();
            }
        }
        for (Integer k : net.unconnected.keySet()) {
            ev.unconnectedPenalty += rules.unconnectedPenalty(ctx.scenario.oks.get(k).flow);
        }
        ev.constructionCost = ev.pipeCost + ev.chamberCost + ev.existingTieInCost;
        ev.calculatedCost = ev.constructionCost + ev.unconnectedPenalty;
        ev.score = rules.score(ev.calculatedCost, ev.length);
        return ev;
    }

    private Eval.Part part(List<Coordinate> coords, boolean special, double k, String types, double len, int dn) {
        return new Eval.Part(coords, special, k, types, len, len * rules.costPerM(dn) * k);
    }

    private static Coordinate along(Coordinate a, Coordinate b, double t, double len) {
        if (len < 1e-12) {
            return new Coordinate(a);
        }
        double f = t / len;
        return new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
    }
}
