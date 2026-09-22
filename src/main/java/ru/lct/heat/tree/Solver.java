package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Blockers;
import ru.lct.heat.route.Context;
import ru.lct.heat.route.ExistingTargets;
import ru.lct.heat.route.Goal;
import ru.lct.heat.route.ObstacleLayer;
import ru.lct.heat.route.Router;
import ru.lct.heat.route.TargetSet;

/**
 * Построение сети: жадная вставка ОКС с полной ценой ΔS (раздел 3 docs/architecture.md),
 * локальный поиск «удалить и вставить заново», несколько запусков с разным порядком,
 * отбор до 3 содержательно разных вариантов.
 */
public final class Solver {

    private static final Logger log = LoggerFactory.getLogger(Solver.class);
    private static final int MAX_EXPANSIONS = 200_000;

    /** Готовый вариант. */
    public static final class Candidate {
        public final Net net;
        public final Eval eval;
        public final String strategy;

        Candidate(Net net, Eval eval, String strategy) {
            this.net = net;
            this.eval = eval;
            this.strategy = strategy;
        }
    }

    private final Context ctx;
    private final Rules rules;
    private final Sizer sizer;
    private final Evaluator evaluator;
    private final HeatProperties.Solver cfg;
    private final double maxTurnCos;

    public Solver(Context ctx) {
        this.ctx = ctx;
        this.rules = ctx.rules;
        this.sizer = new Sizer(ctx);
        this.evaluator = new Evaluator(ctx);
        this.cfg = rules.props().getSolver();
        this.maxTurnCos = Math.cos(Math.toRadians(rules.props().getGeometry().getMaxTurnAngleDeg())) - 1e-9;
    }

    public Evaluator evaluator() {
        return evaluator;
    }

    // ---------------------------------------------------------------- вставка и удаление

    /** Подключает ОКС k к сети (существующей или новой). false - маршрут не найден. */
    boolean insert(Net net, int k, List<double[]> forbidden) {
        Scenario.Oks oks = ctx.scenario.oks.get(k);
        int dn = rules.minDnForFlow(oks.flow);
        if (dn < 0) {
            net.unconnected.put(k, "расход " + oks.flow + " т/ч больше пропускной способности максимального ДУ");
            return false;
        }
        List<Context.Port> ports = ctx.ports(k, dn);
        if (ports.isEmpty()) {
            net.unconnected.put(k, "нет допустимого выхода из здания: точка окружена запретными зонами");
            return false;
        }
        ObstacleLayer layer = ctx.layer(dn);
        TargetSet targets = ExistingTargets.union(
                new ExistingTargets(ctx, dn, usedExistingChambers(net), forbidden),
                new TreeTargets(net, ctx, layer, oks.flow, dn));
        Blockers blockers = new Blockers(net.segments(null));
        for (Context.Port port : ports) {
            Router.Result r = new Router(layer, MAX_EXPANSIONS).route(Router.Start.of(port), targets, blockers);
            if (r != null) {
                if (port.note != null) {
                    net.notes.add("ОКС " + oks.id + ": " + port.note);
                }
                attach(net, k, port, r);
                net.unconnected.remove(k);
                return true;
            }
        }
        net.unconnected.put(k, "допустимый маршрут до сети не найден ни из одного выхода здания");
        return false;
    }

    private void attach(Net net, int k, Context.Port port, Router.Result r) {
        Net.Node target;
        Goal g = r.goal;
        switch (g.kind) {
            case NEW_ON_LINE:
                target = net.addNode(Net.Kind.NEW_CHAMBER, g.point);
                target.root = true;
                target.line = g.ref;
                break;
            case EXISTING_CHAMBER:
                target = net.existingChamberRoot(g.ref);
                if (target == null) {
                    target = net.addNode(Net.Kind.EXISTING_CHAMBER, g.point);
                    target.root = true;
                    target.chamber = g.ref;
                }
                break;
            case SPLIT_EDGE:
                target = net.split(g.ref, g.point);
                break;
            case TREE_NODE:
                target = net.nodes.get(g.ref);
                break;
            default:
                throw new IllegalStateException("неожиданная цель " + g.kind);
        }
        Net.Node leaf = net.addNode(Net.Kind.OKS, port.p);
        leaf.oks = k;
        Net.Edge e = net.addEdge(leaf.id, target.id, new ArrayList<>(r.path));
        e.fromOks = port.fixed;
        e.dn = rules.minDnForFlow(ctx.scenario.oks.get(k).flow);
        e.flow = ctx.scenario.oks.get(k).flow;
    }

    private int[] usedExistingChambers(Net net) {
        int[] used = new int[ctx.scenario.chambers.size()];
        for (Net.Node n : net.nodes.values()) {
            if (n.kind == Net.Kind.EXISTING_CHAMBER) {
                used[n.chamber] += net.childEdges(n.id).size();
            }
        }
        return used;
    }

    /** Убирает ОКС и всё, что без неё не нужно: ветку до камеры ветвления, лишнюю камеру, пустую врезку. */
    void remove(Net net, int k) {
        net.unconnected.remove(k);
        Net.Node leaf = net.oksNode(k);
        if (leaf == null) {
            return;
        }
        Net.Edge e = net.parentEdge(leaf.id);
        net.nodes.remove(leaf.id);
        if (e == null) {
            return;
        }
        net.edges.remove(e.id);
        cleanup(net, e.parent);
    }

    private void cleanup(Net net, int nodeId) {
        Net.Node n = net.nodes.get(nodeId);
        if (n == null || n.kind == Net.Kind.OKS) {
            return;
        }
        List<Net.Edge> children = net.childEdges(nodeId);
        if (n.root) {
            if (children.isEmpty()) {
                net.nodes.remove(nodeId);
            }
            return;
        }
        Net.Edge up = net.parentEdge(nodeId);
        if (children.isEmpty()) {
            net.nodes.remove(nodeId);
            if (up != null) {
                net.edges.remove(up.id);
                cleanup(net, up.parent);
            }
        } else if (children.size() == 1 && up != null) {
            // камера без разветвления не нужна: склеиваем два ребра в одно
            Net.Edge c = children.get(0);
            List<Coordinate> merged = new ArrayList<>(c.path);
            merged.addAll(up.path.subList(1, up.path.size()));
            c.path = merged;
            c.parent = up.parent;
            net.edges.remove(up.id);
            net.nodes.remove(nodeId);
            if (!anglesOk(c.path)) {
                reroute(net, c);
            }
        }
    }

    // ---------------------------------------------------------------- ДУ, проверка, перетрассировка

    /** Подбор ДУ и перепроверка геометрии с новыми отступами, до устойчивого состояния. */
    void sizeAndValidate(Net net) {
        for (int iter = 0; iter < cfg.getMaxSizingIterations(); iter++) {
            sizer.size(net);
            boolean changed = false;
            for (Net.Edge e : new ArrayList<>(net.edges.values())) {
                if (!edgeValid(net, e)) {
                    changed |= reroute(net, e);
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    boolean edgeValid(Net net, Net.Edge e) {
        for (int i = 0; i + 1 < e.path.size(); i++) {
            if (evaluator.check(net, e, i) == null) {
                return false;
            }
        }
        return true;
    }

    /** Перестраивает ребро между его узлами с отступами текущего ДУ. */
    boolean reroute(Net net, Net.Edge e) {
        Net.Node child = net.nodes.get(e.child);
        Net.Node parent = net.nodes.get(e.parent);
        int dn = e.dn > 0 ? e.dn : rules.pipes().get(0).getDn();
        List<Router.Start> starts = new ArrayList<>();
        if (child.kind == Net.Kind.OKS) {
            for (Context.Port port : ctx.ports(child.oks, dn)) {
                starts.add(Router.Start.of(port));
            }
            if (starts.isEmpty()) {
                net.notes.add("ребро " + e.id + ": при ДУ " + dn + " нет выхода из здания, оставлена прежняя трасса");
                return false;
            }
        } else {
            starts.add(Router.Start.free(child.c));
        }
        // врезка, к которой идёт только это ребро, может переехать в другое место сети
        boolean movableRoot = parent.root && parent.kind == Net.Kind.NEW_CHAMBER && net.childEdges(parent.id).size() == 1;
        int[] exempt = parent.kind == Net.Kind.EXISTING_CHAMBER ? ctx.network.chamberLines[parent.chamber]
                : parent.line >= 0 ? new int[] {parent.line} : null;
        TargetSet target = movableRoot
                ? new ExistingTargets(ctx, dn, usedExistingChambers(net), List.of())
                : pointTarget(parent.c, exempt);
        Blockers blockers = new Blockers(net.segments(e.id));
        for (Router.Start start : starts) {
            Router.Result r = new Router(ctx.layer(dn), MAX_EXPANSIONS).route(start, target, blockers);
            if (r == null) {
                continue;
            }
            e.path = new ArrayList<>(r.path);
            if (movableRoot) {
                relocateRoot(net, e, parent, r.goal);
            }
            return true;
        }
        net.notes.add("ребро " + e.id + ": не удалось перестроить при ДУ " + dn);
        return false;
    }

    private void relocateRoot(Net net, Net.Edge e, Net.Node oldRoot, Goal g) {
        if (g.kind == Goal.Kind.NEW_ON_LINE) {
            oldRoot.c = g.point;
            oldRoot.line = g.ref;
            return;
        }
        Net.Node root = net.existingChamberRoot(g.ref);
        if (root == null) {
            root = net.addNode(Net.Kind.EXISTING_CHAMBER, g.point);
            root.root = true;
            root.chamber = g.ref;
        }
        e.parent = root.id;
        net.nodes.remove(oldRoot.id);
    }

    private static TargetSet pointTarget(Coordinate p, int[] exempt) {
        return new TargetSet() {
            @Override
            public void candidates(Coordinate from, List<Goal> out) {
                out.add(new Goal(Goal.Kind.POINT, p, 0, -1, exempt));
            }

            @Override
            public double minDistance(Coordinate from) {
                return from.distance(p);
            }

            @Override
            public double minNodeScore() {
                return 0;
            }
        };
    }

    private boolean anglesOk(List<Coordinate> path) {
        for (int i = 1; i + 1 < path.size(); i++) {
            if (cosTurn(path.get(i - 1), path.get(i), path.get(i + 1)) < maxTurnCos) {
                return false;
            }
        }
        return true;
    }

    private static double cosTurn(Coordinate a, Coordinate v, Coordinate b) {
        double x1 = v.x - a.x;
        double y1 = v.y - a.y;
        double x2 = b.x - v.x;
        double y2 = b.y - v.y;
        double l = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        return l < 1e-12 ? 1 : (x1 * x2 + y1 * y2) / l;
    }

    /** Спрямление (R-GEO-03): убираем вершины, без которых трасса остаётся допустимой. */
    void straighten(Net net) {
        for (Net.Edge e : net.edges.values()) {
            boolean changed = true;
            while (changed) {
                changed = false;
                int first = e.fromOks ? 2 : 1;
                for (int i = first; i + 1 < e.path.size(); i++) {
                    List<Coordinate> trial = new ArrayList<>(e.path);
                    trial.remove(i);
                    if (!anglesOk(trial)) {
                        continue;
                    }
                    List<Coordinate> saved = e.path;
                    e.path = trial;
                    Blockers blockers = new Blockers(net.segments(e.id));
                    boolean ok = evaluator.check(net, e, i - 1) != null
                            && !blockers.blocks(trial.get(i - 1), trial.get(i), i - 1 == 0, i == trial.size() - 1);
                    if (ok) {
                        changed = true;
                        break;
                    }
                    e.path = saved;
                }
            }
            // почти коллинеарные вершины (< 1°) склеиваем
            for (int i = (e.fromOks ? 2 : 1); i + 1 < e.path.size(); ) {
                if (cosTurn(e.path.get(i - 1), e.path.get(i), e.path.get(i + 1)) > Math.cos(Math.toRadians(1))) {
                    List<Coordinate> trial = new ArrayList<>(e.path);
                    trial.remove(i);
                    List<Coordinate> saved = e.path;
                    e.path = trial;
                    if (evaluator.check(net, e, i - 1) != null) {
                        continue;
                    }
                    e.path = saved;
                }
                i++;
            }
        }
    }

    // ---------------------------------------------------------------- стратегии

    private List<Integer> order(String strategy, Random rnd) {
        List<Integer> ids = new ArrayList<>();
        for (int k = 0; k < ctx.scenario.oks.size(); k++) {
            ids.add(k);
        }
        Comparator<Integer> byDist = Comparator.comparingDouble(k -> distToNetwork(k));
        Comparator<Integer> byFlow = Comparator.comparingDouble(k -> -ctx.scenario.oks.get(k).flow);
        switch (strategy) {
            case "nearest-first":
                ids.sort(byDist);
                break;
            case "farthest-first":
                ids.sort(byDist.reversed());
                break;
            case "largest-flow-first":
                ids.sort(byFlow);
                break;
            default:
                Collections.shuffle(ids, rnd);
        }
        return ids;
    }

    private double distToNetwork(int k) {
        Coordinate c = ctx.scenario.oks.get(k).geom.getCoordinate();
        double best = Double.MAX_VALUE;
        for (Scenario.NetworkLine l : ctx.scenario.network) {
            best = Math.min(best, l.geom.distance(ctx.gf.createPoint(c)));
        }
        return best;
    }

    Net greedy(List<Integer> order, List<double[]> forbidden) {
        Net net = new Net();
        for (int k : order) {
            insert(net, k, forbidden);
            sizeAndValidate(net);
        }
        return net;
    }

    /** «Звезда»: каждая ОКС подключается к существующей сети отдельно. */
    Net star(List<double[]> forbidden) {
        Net net = new Net();
        for (int k = 0; k < ctx.scenario.oks.size(); k++) {
            Scenario.Oks oks = ctx.scenario.oks.get(k);
            int dn = rules.minDnForFlow(oks.flow);
            List<Context.Port> ports = dn < 0 ? List.of() : ctx.ports(k, dn);
            if (ports.isEmpty()) {
                net.unconnected.put(k, dn < 0 ? "расход больше пропускной способности максимального ДУ"
                        : "нет допустимого выхода из здания: точка окружена запретными зонами");
                continue;
            }
            TargetSet targets = new ExistingTargets(ctx, dn, usedExistingChambers(net), forbidden);
            Blockers blockers = new Blockers(net.segments(null));
            boolean done = false;
            for (Context.Port port : ports) {
                Router.Result r = new Router(ctx.layer(dn), MAX_EXPANSIONS).route(Router.Start.of(port), targets, blockers);
                if (r != null) {
                    attach(net, k, port, r);
                    done = true;
                    break;
                }
            }
            if (!done) {
                net.unconnected.put(k, "допустимый маршрут до сети не найден ни из одного выхода здания");
            }
        }
        sizeAndValidate(net);
        return net;
    }

    /** Локальный поиск: вынуть ОКС и вставить заново, принять, если S уменьшился. */
    Net improve(Net net, long deadline, Random rnd, List<double[]> forbidden) {
        Net best = net;
        double bestScore = evaluator.evaluate(best).rankScore();
        boolean improved = true;
        while (improved && System.currentTimeMillis() < deadline) {
            improved = false;
            List<Integer> ids = new ArrayList<>();
            for (int k = 0; k < ctx.scenario.oks.size(); k++) {
                ids.add(k);
            }
            Collections.shuffle(ids, rnd);
            for (int k : ids) {
                if (System.currentTimeMillis() >= deadline) {
                    break;
                }
                Net trial = best.copy();
                remove(trial, k);
                sizeAndValidate(trial);
                insert(trial, k, forbidden);
                sizeAndValidate(trial);
                double s = evaluator.evaluate(trial).rankScore();
                if (s < bestScore - 1e-9) {
                    best = trial;
                    bestScore = s;
                    improved = true;
                }
            }
        }
        return best;
    }

    /** Полный расчёт режима 2D: пул решений разными стратегиями и отбор вариантов. */
    public List<Candidate> solve() {
        long started = System.currentTimeMillis();
        long budgetMs = (long) (cfg.getTimeBudgetS() * 1000);
        Random rnd = new Random(cfg.getSeed());
        List<Candidate> pool = new ArrayList<>();
        List<double[]> none = List.of();

        Net star = star(none);
        pool.add(finish(star, "star"));

        List<String> strategies = new ArrayList<>(List.of("nearest-first", "largest-flow-first", "farthest-first"));
        while (strategies.size() < Math.max(3, cfg.getGreedyRestarts())) {
            strategies.add("random-" + strategies.size());
        }
        int runs = strategies.size() + 1;
        for (int i = 0; i < strategies.size(); i++) {
            long now = System.currentTimeMillis();
            long left = started + budgetMs - now;
            if (left <= 0 && i >= 1) {
                break;
            }
            long share = Math.max(left / Math.max(1, runs - i), 2000);
            String strategy = strategies.get(i);
            Net net = greedy(order(strategy, rnd), none);
            net = improve(net, now + share, rnd, none);
            pool.add(finish(net, strategy));
            log.info("стратегия {}: S = {}", strategy, pool.get(pool.size() - 1).eval.score);
        }

        // содержательно другой вариант: запрещаем врезки лучшего решения
        Candidate best = pool.stream().min(Comparator.comparingDouble(c -> c.eval.rankScore())).orElseThrow();
        List<double[]> forbidden = new ArrayList<>();
        for (Net.Node n : best.net.nodes.values()) {
            if (n.root) {
                forbidden.add(new double[] {n.c.x, n.c.y, 30.0});
            }
        }
        if (!forbidden.isEmpty()) {
            Net alt = greedy(order("nearest-first", rnd), forbidden);
            alt = improve(alt, System.currentTimeMillis() + Math.max(2000, budgetMs / 6), rnd, forbidden);
            pool.add(finish(alt, "other-tie-in"));
        }
        return selectVariants(pool);
    }

    private Candidate finish(Net net, String strategy) {
        straighten(net);
        sizeAndValidate(net);
        return new Candidate(net, evaluator.evaluate(net), strategy);
    }

    /** До N вариантов по возрастанию S, каждый отличается от уже выбранных (R-VAR-02). */
    List<Candidate> selectVariants(List<Candidate> pool) {
        List<Candidate> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble(c -> c.eval.rankScore()));
        List<Candidate> out = new ArrayList<>();
        HeatProperties.Variants v = rules.props().getVariants();
        for (Candidate c : sorted) {
            if (out.size() >= v.getMaxCount()) {
                break;
            }
            boolean distinct = true;
            for (Candidate chosen : out) {
                if (difference(c.net, chosen.net, v.getCorridorBufferM()) < v.getMinDifference()) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                out.add(c);
            }
        }
        return out;
    }

    /** 1 - Жаккар коридоров двух сетей: 0 - совпадают, 1 - нигде не пересекаются. */
    double difference(Net a, Net b, double buffer) {
        Geometry ga = corridor(a, buffer);
        Geometry gb = corridor(b, buffer);
        if (ga.isEmpty() && gb.isEmpty()) {
            return 0;
        }
        double inter = ga.intersection(gb).getArea();
        double union = ga.union(gb).getArea();
        return union <= 0 ? 0 : 1 - inter / union;
    }

    private Geometry corridor(Net net, double buffer) {
        List<Geometry> lines = new ArrayList<>();
        for (Net.Edge e : net.edges.values()) {
            LineString ls = ctx.gf.createLineString(e.path.toArray(new Coordinate[0]));
            lines.add(ls);
        }
        if (lines.isEmpty()) {
            return ctx.gf.createPolygon();
        }
        return ctx.gf.buildGeometry(lines).buffer(buffer, 4);
    }
}
