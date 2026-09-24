package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Blockers;
import ru.lct.heat.route.Context;
import ru.lct.heat.route.ExistingNetwork;
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
    /** Перетрассировка ищет между известными узлами: если пути нет, незачем перебирать весь граф. */
    private static final int REROUTE_EXPANSIONS = 40_000;

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
    private final HeatProperties.Mode mode;
    private final Rules rules;
    private final Sizer sizer;
    private final Evaluator evaluator;
    private final HeatProperties.Solver cfg;
    private final double maxTurnCos;

    /** Бюджет времени расчёта, с; 0 - из конфига. */
    private final double budgetOverride;

    public Solver(Context ctx) {
        this(ctx, 0, HeatProperties.Mode.ECONOMY);
    }

    public Solver(Context ctx, double budgetSeconds) {
        this(ctx, budgetSeconds, HeatProperties.Mode.ECONOMY);
    }

    public Solver(Context ctx, double budgetSeconds, HeatProperties.Mode mode) {
        this.budgetOverride = budgetSeconds;
        this.mode = mode;
        this.ctx = ctx;
        this.rules = ctx.rules;
        this.sizer = new Sizer(ctx);
        this.evaluator = new Evaluator(ctx, mode);
        this.cfg = rules.props().getSolver();
        this.maxTurnCos = Math.cos(Math.toRadians(rules.props().getGeometry().getMaxTurnAngleDeg())) - 1e-9;
    }

    public Evaluator evaluator() {
        return evaluator;
    }

    /** Варианты режима с глубиной: те же плановые трассы с профилем глубины, своё ранжирование по S. */
    public static final class DepthResult {
        public final List<Candidate> variants = new ArrayList<>();
        public final List<List<String>> problems = new ArrayList<>();
    }

    public DepthResult depthVariants(List<Candidate> plan) {
        DepthProfiler profiler = new DepthProfiler(ctx, evaluator);
        List<Object[]> scored = new ArrayList<>();
        for (Candidate c : plan) {
            List<String> problems = new ArrayList<>();
            Eval depth = profiler.evaluate(c.net, c.eval, problems);
            scored.add(new Object[] {new Candidate(c.net, depth, c.strategy), problems});
        }
        scored.sort(Comparator.comparingDouble(o -> ((Candidate) o[0]).eval.rankScore()
                + (((List<?>) o[1]).isEmpty() ? 0 : 1000)));
        DepthResult r = new DepthResult();
        for (Object[] o : scored) {
            r.variants.add((Candidate) o[0]);
            @SuppressWarnings("unchecked")
            List<String> problems = (List<String>) o[1];
            r.problems.add(problems);
        }
        return r;
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
        List<Context.Port> ports = ctx.ports(k, dn, mode);
        if (ports.isEmpty()) {
            net.unconnected.put(k, "нет допустимого выхода из здания: точка окружена запретными зонами");
            return false;
        }
        ObstacleLayer layer = ctx.layer(dn, mode);
        TargetSet targets = ExistingTargets.union(
                new ExistingTargets(ctx, dn, usedExistingChambers(net), crossingForbidden(net, forbidden), roots(net, null)),
                new TreeTargets(net, ctx, layer, oks.flow, dn));
        Blockers blockers = new Blockers(net.segments(null));
        boolean limitHit = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            int limit = attempt == 0 ? MAX_EXPANSIONS : MAX_EXPANSIONS * 2;
            for (Context.Port port : ports) {
                Router router = new Router(layer, limit);
                Router.Result r = router.route(Router.Start.of(port), targets, blockers);
                limitHit |= router.limitHit;
                if (r == null) {
                    continue;
                }
                if (port.note != null) {
                    net.notes.add("ОКС " + oks.id + ": " + port.note);
                }
                attach(net, k, port, r);
                net.unconnected.remove(k);
                return true;
            }
            if (!limitHit) {
                break; // граф исчерпан: больший лимит не поможет
            }
        }
        net.unconnected.put(k, limitHit
                ? "поиск маршрута прерван по лимиту раскрытий (граф не исчерпан)"
                : "допустимый маршрут до сети не найден ни из одного выхода здания");
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

    /**
     * Точки, где новая сеть пересекает существующие линии: врезаться ближе
     * chambers.min-distance-to-crossing-m к ним нельзя (ответ организаторов на вопрос 3).
     */
    private List<double[]> crossingForbidden(Net net, List<double[]> base) {
        double radius = rules.props().getChambers().getMinDistanceToCrossingM();
        if (radius <= 0) {
            return base;
        }
        List<double[]> out = new ArrayList<>(base);
        for (LineSegment s : net.segments(null)) {
            Envelope env = new Envelope(s.p0, s.p1);
            for (Object o : ctx.network.segIndex.query(env)) {
                ExistingNetwork.Seg line = (ExistingNetwork.Seg) o;
                Coordinate x = s.intersection(line.seg);
                if (x != null) {
                    out.add(new double[] {x.x, x.y, radius});
                }
            }
        }
        return out;
    }

    private static List<Coordinate> roots(Net net, Integer except) {
        List<Coordinate> out = new ArrayList<>();
        for (Net.Node n : net.nodes.values()) {
            if (n.root && (except == null || n.id != except)) {
                out.add(n.c);
            }
        }
        return out;
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

    /**
     * Подбор ДУ и перепроверка геометрии с новыми отступами, до устойчивого состояния.
     * Перестроение поддерева - дорогая операция, поэтому только на финальной сборке варианта.
     */
    void sizeAndValidate(Net net) {
        sizeAndValidate(net, false);
    }

    private static final int MAX_REBUILDS = 3;

    private void sizeAndValidate(Net net, boolean allowRebuild) {
        int rebuilds = 0;
        for (int iter = 0; iter < cfg.getMaxSizingIterations(); iter++) {
            sizer.size(net);
            boolean changed = false;
            List<Integer> failed = new ArrayList<>();
            for (Net.Edge e : new ArrayList<>(net.edges.values())) {
                if (!net.edges.containsKey(e.id) || edgeValid(net, e)) {
                    continue;
                }
                if (reroute(net, e)) {
                    changed = true;
                } else {
                    failed.add(e.id);
                }
            }
            if (!failed.isEmpty() && allowRebuild && rebuilds < MAX_REBUILDS) {
                rebuilds++;
                // ребро не перестраивается при новом ДУ: вынимаем ОКС его поддерева и вставляем заново
                List<Integer> oks = new ArrayList<>();
                for (int id : failed) {
                    Net.Edge e = net.edges.get(id);
                    if (e != null) {
                        collectOks(net, e.child, oks);
                    }
                }
                for (int k : oks) {
                    remove(net, k);
                }
                sizeAndValidate(net, false);
                for (int k : oks) {
                    insert(net, k, List.of());
                    sizeAndValidate(net, false);
                }
                net.notes.add("перестроено поддерево из " + oks.size() + " ОКС после роста ДУ");
                changed = true;
            }
            if (!changed) {
                return;
            }
        }
    }

    private void collectOks(Net net, int node, List<Integer> out) {
        Net.Node n = net.nodes.get(node);
        if (n == null) {
            return;
        }
        if (n.kind == Net.Kind.OKS && !out.contains(n.oks)) {
            out.add(n.oks);
        }
        for (Net.Edge c : net.childEdges(node)) {
            collectOks(net, c.child, out);
        }
    }

    boolean edgeValid(Net net, Net.Edge e) {
        if (e.checkedPath == e.path && e.checkedDn == e.dn) {
            return true; // трасса и ДУ не менялись с прошлой проверки
        }
        for (int i = 0; i + 1 < e.path.size(); i++) {
            if (evaluator.check(net, e, i) == null) {
                return false;
            }
        }
        e.checkedPath = e.path;
        e.checkedDn = e.dn;
        return true;
    }

    /** Перестраивает ребро между его узлами с отступами текущего ДУ. */
    boolean reroute(Net net, Net.Edge e) {
        if (e.failedPath == e.path && e.failedDn == e.dn) {
            return false; // для этой трассы и ДУ уже искали, пути нет
        }
        Net.Node child = net.nodes.get(e.child);
        Net.Node parent = net.nodes.get(e.parent);
        int dn = e.dn > 0 ? e.dn : rules.pipes().get(0).getDn();
        List<Router.Start> starts = new ArrayList<>();
        if (child.kind == Net.Kind.OKS) {
            for (Context.Port port : ctx.ports(child.oks, dn, mode)) {
                starts.add(Router.Start.of(port));
            }
            if (starts.isEmpty()) {
                return false;
            }
        } else {
            starts.add(Router.Start.free(child.c));
        }
        // врезка, к которой идёт только это ребро, может переехать в другое место сети
        boolean movableRoot = parent.root && parent.kind == Net.Kind.NEW_CHAMBER && net.childEdges(parent.id).size() == 1;
        int[] exempt = parent.kind == Net.Kind.EXISTING_CHAMBER ? ctx.network.chamberLines[parent.chamber]
                : parent.root ? ctx.tieAt(parent.c).lines : null;
        TargetSet target = movableRoot
                ? new ExistingTargets(ctx, dn, usedExistingChambers(net), crossingForbidden(net, List.of()), roots(net, parent.id))
                : pointTarget(parent.c, exempt);
        Blockers blockers = new Blockers(net.segments(e.id));
        for (Router.Start start : starts) {
            Router.Result r = new Router(ctx.layer(dn, mode), REROUTE_EXPANSIONS).route(start, target, blockers);
            if (r == null) {
                continue;
            }
            e.path = new ArrayList<>(r.path);
            if (movableRoot) {
                relocateRoot(net, e, parent, r.goal);
            }
            return true;
        }
        e.failedPath = e.path;
        e.failedDn = e.dn;
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

    /**
     * Спрямление не должно ломать сетку монтажного режима: отрезок после удаления вершины
     * обязан остаться по сетке. Исключение - перемычки у ввода в здание и у точки врезки.
     */
    private boolean straightenAllowed(Net.Edge e, List<Coordinate> trial, int i) {
        if (mode != HeatProperties.Mode.INSTALLATION) {
            return true;
        }
        ObstacleLayer layer = ctx.layer(e.dn, mode);
        Coordinate a = trial.get(i - 1);
        Coordinate b = trial.get(i);
        // либо отрезок остаётся по сетке, либо это короткая перемычка у ввода или у врезки
        return layer.gridAligned(a, b) || a.distance(b) <= layer.gridStep() * 1.5;
    }

    /** Спрямление (R-GEO-03): убираем вершины, без которых трасса остаётся допустимой. */
    void straighten(Net net) {
        for (Net.Edge e : net.edges.values()) {
            straightenEdge(net, e);
        }
    }

    private void straightenEdge(Net net, Net.Edge e) {
        boolean changed = true;
        while (changed) {
            changed = false;
            int first = e.fromOks ? 2 : 1;
            for (int i = first; i + 1 < e.path.size(); i++) {
                List<Coordinate> trial = new ArrayList<>(e.path);
                trial.remove(i);
                if (!anglesOk(trial) || !straightenAllowed(e, trial, i)) {
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
                if (!straightenAllowed(e, trial, i)) {
                    i++;
                    continue;
                }
                List<Coordinate> saved = e.path;
                e.path = trial;
                if (anglesOk(trial) && evaluator.check(net, e, i - 1) != null
                        && !new Blockers(net.segments(e.id)).blocks(trial.get(i - 1), trial.get(i), i - 1 == 0, i == trial.size() - 1)) {
                    continue;
                }
                e.path = saved;
            }
            i++;
        }
    }

    /** Дальше этого узел не переезжает: это была бы уже другая топология, а не подтяжка. */
    private static final double ALIGN_MAX_MOVE_M = 25.0;
    /** Сколько ближайших точек пробовать для одного узла. */
    private static final int ALIGN_CANDIDATES = 6;

    /**
     * Подтяжка узлов: камера ветвления переезжает туда, где продолжение подходящей к ней трассы
     * встречает соседнюю ветку. Убирает «крючки» - короткие перемычки, которыми ветка добирает
     * до узла, стоящего в стороне от её прямой (в монтажном режиме - от её линии сетки).
     * Врезки не трогаем: они привязаны к существующей сети.
     */
    void alignJunctions(Net net) {
        double best = evaluator.evaluate(net).rankScore();
        double before = best;
        long t0 = System.currentTimeMillis();
        int tried = 0;
        int moved = 0;
        for (int id : new ArrayList<>(net.nodes.keySet())) {
            Net.Node node = net.nodes.get(id);
            if (node == null || node.kind != Net.Kind.NEW_CHAMBER || node.root) {
                continue;
            }
            for (Coordinate p : alignCandidates(net, node)) {
                Net trial = net.copy();
                tried++;
                if (!moveNode(trial, id, p)) {
                    continue;
                }
                sizeAndValidate(trial);
                double s = evaluator.evaluate(trial).rankScore();
                if (s < best - 1e-9) {
                    net.assign(trial);
                    best = s;
                    moved++;
                    break;
                }
            }
        }
        log.info("подтяжка узлов: {} попыток, переехало {}, {} мс, S {} -> {}", tried, moved,
                System.currentTimeMillis() - t0, round(before), round(best));
    }

    /** Точки, куда имеет смысл двигать узел: пересечения прямых соседних участков. */
    private List<Coordinate> alignCandidates(Net net, Net.Node node) {
        List<Coordinate[]> lines = new ArrayList<>();
        for (Net.Edge e : incident(net, node.id)) {
            List<Coordinate> out = outward(e, node.id);
            for (int i = 0; i + 1 < Math.min(out.size(), 3); i++) {
                lines.add(new Coordinate[] {out.get(i), out.get(i + 1)});
            }
        }
        List<Coordinate> cands = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                Coordinate x = lineIntersection(lines.get(i), lines.get(j));
                double move = x == null ? 0 : x.distance(node.c);
                if (x != null && move > 0.05 && move <= ALIGN_MAX_MOVE_M) {
                    cands.add(x);
                }
            }
        }
        cands.sort(Comparator.comparingDouble(c -> c.distance(node.c)));
        return cands.subList(0, Math.min(cands.size(), ALIGN_CANDIDATES));
    }

    private List<Net.Edge> incident(Net net, int node) {
        List<Net.Edge> out = new ArrayList<>(net.childEdges(node));
        Net.Edge parent = net.parentEdge(node);
        if (parent != null) {
            out.add(parent);
        }
        return out;
    }

    /** Путь ребра от узла наружу: у родительского ребра он уже в этом порядке, у дочернего - наоборот. */
    private static List<Coordinate> outward(Net.Edge e, int node) {
        if (e.child == node) {
            return e.path;
        }
        List<Coordinate> out = new ArrayList<>(e.path);
        Collections.reverse(out);
        return out;
    }

    private static Coordinate lineIntersection(Coordinate[] a, Coordinate[] b) {
        double x1 = a[1].x - a[0].x;
        double y1 = a[1].y - a[0].y;
        double x2 = b[1].x - b[0].x;
        double y2 = b[1].y - b[0].y;
        double den = x1 * y2 - y1 * x2;
        double scale = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        if (scale < 1e-9 || Math.abs(den) < 0.05 * scale) {
            return null; // почти параллельны: пересечение неустойчиво
        }
        double t = ((b[0].x - a[0].x) * y2 - (b[0].y - a[0].y) * x2) / den;
        return new Coordinate(a[0].x + t * x1, a[0].y + t * y1);
    }

    /** Переносит узел в точку p вместе с концами всех примыкающих участков. false - так нельзя. */
    private boolean moveNode(Net net, int id, Coordinate p) {
        Net.Node node = net.nodes.get(id);
        node.c = new Coordinate(p);
        List<Net.Edge> touched = incident(net, id);
        for (Net.Edge e : touched) {
            if (e.fromOks && e.child == id) {
                return false; // финальный отрезок ввода в здание (R-OKS-02) не двигаем
            }
            List<Coordinate> path = new ArrayList<>(e.path);
            path.set(e.child == id ? 0 : path.size() - 1, node.c);
            // концы пути - это узлы, поэтому при слипании вершин убираем внутреннюю
            for (int i = path.size() - 1; i > 0; i--) {
                if (path.get(i).distance(path.get(i - 1)) < 0.05) {
                    path.remove(i - 1 == 0 ? i : i - 1);
                }
            }
            if (path.size() < 2 || !anglesOk(path)) {
                return false;
            }
            e.path = path;
        }
        for (Net.Edge e : touched) {
            straightenEdge(net, e);
        }
        for (Net.Edge e : touched) {
            if (!anglesOk(e.path) || !edgeValid(net, e) || touches(net, e)) {
                return false;
            }
        }
        return true;
    }

    /** Касается ли ребро других участков новой сети вне своих концевых узлов (R-TOP-06). */
    private boolean touches(Net net, Net.Edge e) {
        Blockers blockers = new Blockers(net.segments(e.id));
        for (int i = 0; i + 1 < e.path.size(); i++) {
            if (blockers.blocks(e.path.get(i), e.path.get(i + 1), i == 0, i + 2 == e.path.size())) {
                return true;
            }
        }
        return false;
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
            // во время вставки только пересчёт расхода и ДУ: перепроверять геометрию после каждой ОКС
            // бессмысленно, ДУ ещё вырастет от следующих подключений
            sizer.size(net);
        }
        sizeAndValidate(net);
        return net;
    }

    /** «Звезда»: каждая ОКС подключается к существующей сети отдельно. */
    Net star(List<double[]> forbidden) {
        Net net = new Net();
        for (int k = 0; k < ctx.scenario.oks.size(); k++) {
            Scenario.Oks oks = ctx.scenario.oks.get(k);
            int dn = rules.minDnForFlow(oks.flow);
            List<Context.Port> ports = dn < 0 ? List.of() : ctx.ports(k, dn, mode);
            if (ports.isEmpty()) {
                net.unconnected.put(k, dn < 0 ? "расход больше пропускной способности максимального ДУ"
                        : "нет допустимого выхода из здания: точка окружена запретными зонами");
                continue;
            }
            TargetSet targets = new ExistingTargets(ctx, dn, usedExistingChambers(net),
                    crossingForbidden(net, forbidden), roots(net, null));
            Blockers blockers = new Blockers(net.segments(null));
            boolean done = false;
            for (Context.Port port : ports) {
                Router.Result r = new Router(ctx.layer(dn, mode), MAX_EXPANSIONS).route(Router.Start.of(port), targets, blockers);
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
        int moves = 0;
        int accepted = 0;
        long t0 = System.currentTimeMillis();
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
                moves++;
                if (s < bestScore - 1e-9) {
                    best = trial;
                    bestScore = s;
                    improved = true;
                    accepted++;
                }
            }
        }
        log.info("локальный поиск: {} ходов, принято {}, {} мс, S={}", moves, accepted, System.currentTimeMillis() - t0, round(bestScore));
        return best;
    }

    /**
     * LNS «разрушить и собрать»: вынуть группу соседних ОКС и вставить в случайном порядке.
     * Выводит из локальных оптимумов, где одиночная перевставка уже ничего не даёт.
     */
    Net ruinAndRecreate(Net net, long deadline, Random rnd, List<double[]> forbidden) {
        Net best = net;
        double bestScore = evaluator.evaluate(best).rankScore();
        int n = ctx.scenario.oks.size();
        if (n < 3) {
            return best;
        }
        int moves = 0;
        int accepted = 0;
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int seed = rnd.nextInt(n);
            Coordinate sc = ctx.scenario.oks.get(seed).geom.getCoordinate();
            List<Integer> ids = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                ids.add(k);
            }
            ids.sort(Comparator.comparingDouble(k -> ctx.scenario.oks.get(k).geom.getCoordinate().distance(sc)));
            List<Integer> group = new ArrayList<>(ids.subList(0, Math.min(n, 2 + rnd.nextInt(4))));
            Net trial = best.copy();
            for (int k : group) {
                remove(trial, k);
            }
            sizeAndValidate(trial);
            Collections.shuffle(group, rnd);
            for (int k : group) {
                insert(trial, k, forbidden);
                sizeAndValidate(trial);
            }
            double s = evaluator.evaluate(trial).rankScore();
            moves++;
            if (s < bestScore - 1e-9) {
                best = trial;
                bestScore = s;
                accepted++;
            }
        }
        log.info("LNS: {} ходов, принято {}, {} мс, S={}", moves, accepted, System.currentTimeMillis() - t0, round(bestScore));
        return best;
    }

    /**
     * Полный расчёт режима 2D: пул решений разными стратегиями и отбор вариантов.
     * Бюджет времени делится так: жадное дерево + локальный поиск для каждой стратегии, пока есть время;
     * «звезда» - только на небольших наборах; остаток - на шлифовку лучшего решения.
     */
    public List<Candidate> solve() {
        long started = System.currentTimeMillis();
        long budgetMs = (long) ((budgetOverride > 0 ? budgetOverride : cfg.getTimeBudgetS()) * 1000);
        long deadline = started + budgetMs;
        Random rnd = new Random(cfg.getSeed());
        List<Candidate> pool = new ArrayList<>();
        List<double[]> none = List.of();
        int n = ctx.scenario.oks.size();

        List<String> strategies = new ArrayList<>(List.of("nearest-first", "largest-flow-first", "farthest-first"));
        // случайные перезапуски идут, пока есть время (не меньше greedy-restarts, не больше 100)
        while (strategies.size() < 100) {
            strategies.add("random-" + strategies.size());
        }
        for (int i = 0; i < strategies.size(); i++) {
            long now = System.currentTimeMillis();
            // перезапуски - до половины бюджета (но хотя бы одна стратегия), дальше другая врезка и шлифовка
            if (i > 0 && now >= started + budgetMs / 2) {
                break;
            }
            String strategy = strategies.get(i);
            Net net = greedy(order(strategy, rnd), none);
            long share = Math.max((started + budgetMs / 2 - System.currentTimeMillis()) / Math.max(2, cfg.getGreedyRestarts() - i), 1000);
            net = improve(net, System.currentTimeMillis() + share, rnd, none);
            pool.add(finish(net, strategy));
        }
        if (n <= 40) {
            pool.add(finish(star(none), "star"));
        }

        // содержательно другой вариант: запрещаем врезки лучшего решения
        Candidate best = pool.stream().min(Comparator.comparingDouble(c -> c.eval.rankScore())).orElseThrow();
        List<double[]> forbidden = new ArrayList<>();
        for (Net.Node node : best.net.nodes.values()) {
            if (node.root) {
                forbidden.add(new double[] {node.c.x, node.c.y, 30.0});
            }
        }
        if (!forbidden.isEmpty()) {
            Net alt = greedy(order("nearest-first", rnd), forbidden);
            alt = improve(alt, Math.max(started + budgetMs * 65 / 100, System.currentTimeMillis() + 1000), rnd, forbidden);
            pool.add(finish(alt, "other-tie-in"));
        }

        // шлифовка лучшего решения оставшимся временем
        best = pool.stream().min(Comparator.comparingDouble(c -> c.eval.rankScore())).orElseThrow();
        if (System.currentTimeMillis() < deadline) {
            Net polished = ruinAndRecreate(best.net.copy(), deadline, rnd, none);
            polished = improve(polished, deadline + 2000, rnd, none);
            Candidate p = finish(polished, best.strategy + "+polish");
            if (p.eval.rankScore() < best.eval.rankScore() - 1e-9) {
                pool.add(p);
            }
        }
        return selectVariants(pool);
    }

    /**
     * R-TIE-02 после локального поиска: если новая врезка в 10 м от существующей камеры, где теперь есть
     * свободные примыкания для всех её участков, участки переподключаются к этой камере.
     */
    void snapTieInsToChambers(Net net) {
        double radius = rules.props().getChambers().getExistingSnapRadiusM();
        for (Net.Node root : new ArrayList<>(net.nodes.values())) {
            if (!root.root || root.kind != Net.Kind.NEW_CHAMBER || !net.nodes.containsKey(root.id)) {
                continue;
            }
            List<Net.Edge> children = net.childEdges(root.id);
            int[] used = usedExistingChambers(net);
            for (int c = 0; c < ctx.scenario.chambers.size(); c++) {
                Coordinate cc = ctx.scenario.chambers.get(c).geom.getCoordinate();
                if (cc.distance(root.c) > radius || ctx.network.occupancy[c] + used[c] + children.size() > rules.maxConnections()) {
                    continue;
                }
                Net trial = net.copy();
                Net.Node target = trial.existingChamberRoot(c);
                if (target == null) {
                    target = trial.addNode(Net.Kind.EXISTING_CHAMBER, cc);
                    target.root = true;
                    target.chamber = c;
                }
                boolean ok = true;
                for (Net.Edge e : trial.childEdges(root.id)) {
                    e.parent = target.id;
                    if (!reroute(trial, e)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    trial.nodes.remove(root.id);
                    net.assign(trial);
                    break;
                }
            }
        }
    }

    private Candidate finish(Net net, String strategy) {
        snapTieInsToChambers(net);
        straighten(net);
        alignJunctions(net);
        straighten(net);
        sizeAndValidate(net, true);
        Eval e = evaluator.evaluate(net);
        log.info("{}: S={} трубы={} камеры={} врезки={} L={} врезок={} неподкл={}", strategy, round(e.score),
                round(e.pipeCost / 1e6), round(e.chamberCost / 1e6), round(e.existingTieInCost / 1e6), round(e.length),
                net.nodes.values().stream().filter(n -> n.root).count(), net.unconnected.size());
        return new Candidate(net, e, strategy);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /**
     * До N вариантов по возрастанию S, каждый отличается от уже выбранных (R-VAR-02).
     * Если разных вариантов не набралось, порог различия ослабляется: лучше показать
     * похожие решения, чем одно.
     */
    List<Candidate> selectVariants(List<Candidate> pool) {
        List<Candidate> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble(c -> c.eval.rankScore()));
        // R-UNC-01: вариант, где не подключено больше ОКС, чем в лучшем, в выдачу не идёт
        int minUnconnected = sorted.stream().mapToInt(c -> c.net.unconnected.size()).min().orElse(0);
        sorted.removeIf(c -> c.net.unconnected.size() > minUnconnected);
        // варианты с нарушениями правил не выдаются, если есть хотя бы один без нарушений
        if (sorted.stream().anyMatch(c -> c.eval.invalidSegments == 0)) {
            sorted.removeIf(c -> c.eval.invalidSegments > 0);
        }
        HeatProperties.Variants v = rules.props().getVariants();
        List<Candidate> out = List.of();
        for (double threshold = v.getMinDifference(); ; threshold /= 2) {
            out = pick(sorted, threshold, v);
            if (out.size() >= Math.min(2, sorted.size()) || threshold < 0.005) {
                break;
            }
        }
        return out;
    }

    private List<Candidate> pick(List<Candidate> sorted, double threshold, HeatProperties.Variants v) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : sorted) {
            if (out.size() >= v.getMaxCount()) {
                break;
            }
            boolean distinct = true;
            for (Candidate chosen : out) {
                if (difference(c.net, chosen.net, v.getCorridorBufferM()) < threshold) {
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
