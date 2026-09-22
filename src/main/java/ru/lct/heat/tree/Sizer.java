package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;

/**
 * Расходы и ДУ (раздел 4 rules.md): расход = сумма ОКС в поддереве, ДУ минимальный по Q_max,
 * не убывает к врезке, предельная длина проверяется по каждому пути ОКС → врезка.
 */
final class Sizer {

    private final Context ctx;
    private final Rules rules;

    Sizer(Context ctx) {
        this.ctx = ctx;
        this.rules = ctx.rules;
    }

    void size(Net net) {
        Map<Integer, List<Net.Edge>> children = new HashMap<>();
        for (Net.Edge e : net.edges.values()) {
            children.computeIfAbsent(e.parent, k -> new ArrayList<>()).add(e);
        }
        for (Net.Edge e : net.edges.values()) {
            e.flow = -1;
        }
        for (Net.Edge e : net.edges.values()) {
            flow(net, e, children);
        }
        for (Net.Edge e : net.edges.values()) {
            e.dn = 0;
        }
        for (Net.Edge e : net.edges.values()) {
            baseDn(e, children);
        }
        fixLengths(net, children);
    }

    private double flow(Net net, Net.Edge e, Map<Integer, List<Net.Edge>> children) {
        if (e.flow >= 0) {
            return e.flow;
        }
        Net.Node child = net.nodes.get(e.child);
        double f = child.kind == Net.Kind.OKS ? ctx.scenario.oks.get(child.oks).flow : 0;
        for (Net.Edge c : children.getOrDefault(e.child, List.of())) {
            f += flow(net, c, children);
        }
        e.flow = f;
        return f;
    }

    private int baseDn(Net.Edge e, Map<Integer, List<Net.Edge>> children) {
        if (e.dn > 0) {
            return e.dn;
        }
        int dn = rules.minDnForFlow(e.flow);
        if (dn < 0) {
            dn = rules.pipes().get(rules.pipes().size() - 1).getDn();
        }
        for (Net.Edge c : children.getOrDefault(e.child, List.of())) {
            dn = Math.max(dn, baseDn(c, children));
        }
        e.dn = dn;
        return dn;
    }

    /** Пути ОКС → врезка списками рёбер снизу вверх. */
    private List<List<Net.Edge>> paths(Net net) {
        Map<Integer, Net.Edge> up = new HashMap<>();
        for (Net.Edge e : net.edges.values()) {
            up.put(e.child, e);
        }
        List<List<Net.Edge>> out = new ArrayList<>();
        for (Net.Node n : net.nodes.values()) {
            if (n.kind != Net.Kind.OKS) {
                continue;
            }
            List<Net.Edge> path = new ArrayList<>();
            Net.Edge e = up.get(n.id);
            while (e != null && path.size() <= net.edges.size()) {
                path.add(e);
                e = up.get(e.parent);
            }
            out.add(path);
        }
        return out;
    }

    /** Суммарное превышение L_max по всем путям (R-LEN-01..04). */
    double excess(Net net) {
        double total = 0;
        for (List<Net.Edge> path : paths(net)) {
            total += pathExcess(path);
        }
        return total;
    }

    private double pathExcess(List<Net.Edge> path) {
        double total = 0;
        int i = 0;
        while (i < path.size()) {
            int dn = path.get(i).dn;
            double len = 0;
            int j = i;
            while (j < path.size() && path.get(j).dn == dn) {
                len += path.get(j).length();
                j++;
            }
            total += Math.max(0, len - rules.lMax(dn));
            i = j;
        }
        return total;
    }

    private void fixLengths(Net net, Map<Integer, List<Net.Edge>> children) {
        int guard = 0;
        double excess = excess(net);
        while (excess > 1e-6 && guard++ < 200) {
            // ищем первый нарушающий фрагмент и пробуем поднять ДУ одного из его рёбер или всего фрагмента
            List<Net.Edge> run = firstViolatingRun(net);
            if (run == null) {
                break;
            }
            Map<Integer, Integer> bestState = null;
            double bestCost = Double.MAX_VALUE;
            double bestExcess = excess;
            List<List<Net.Edge>> options = new ArrayList<>();
            for (Net.Edge e : run) {
                options.add(List.of(e));
            }
            options.add(run);
            for (List<Net.Edge> option : options) {
                Map<Integer, Integer> saved = snapshot(net);
                boolean ok = true;
                for (Net.Edge e : option) {
                    int next = rules.nextDn(e.dn);
                    if (next < 0) {
                        ok = false;
                        break;
                    }
                    e.dn = next;
                }
                if (ok) {
                    propagateUp(net);
                    double ex = excess(net);
                    double cost = pipeCost(net);
                    if (ex < excess - 1e-6 && (ex < bestExcess - 1e-6 || (Math.abs(ex - bestExcess) < 1e-6 && cost < bestCost))) {
                        bestExcess = ex;
                        bestCost = cost;
                        bestState = snapshot(net);
                    }
                }
                restore(net, saved);
            }
            if (bestState == null) {
                net.notes.add("предельная длина: не удалось устранить превышение подбором ДУ");
                break;
            }
            restore(net, bestState);
            excess = bestExcess;
        }
    }

    private List<Net.Edge> firstViolatingRun(Net net) {
        for (List<Net.Edge> path : paths(net)) {
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
                    return new ArrayList<>(path.subList(i, j));
                }
                i = j;
            }
        }
        return null;
    }

    /** Монотонность ДУ к врезке (R-DN-04). */
    private void propagateUp(Net net) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Net.Edge e : net.edges.values()) {
                for (Net.Edge c : net.edges.values()) {
                    if (c.parent == e.child && c.dn > e.dn) {
                        e.dn = c.dn;
                        changed = true;
                    }
                }
            }
        }
    }

    private double pipeCost(Net net) {
        double c = 0;
        for (Net.Edge e : net.edges.values()) {
            c += e.length() * rules.costPerM(e.dn);
        }
        return c;
    }

    private static Map<Integer, Integer> snapshot(Net net) {
        Map<Integer, Integer> m = new HashMap<>();
        net.edges.values().forEach(e -> m.put(e.id, e.dn));
        return m;
    }

    private static void restore(Net net, Map<Integer, Integer> m) {
        net.edges.values().forEach(e -> e.dn = m.get(e.id));
    }
}
