package ru.lct.heat.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.locationtech.jts.geom.Coordinate;

/**
 * A* по графу видимости с состоянием «(предыдущая вершина, вершина)»:
 * так ограничивается угол поворота ≤ 90° (R-GEO-02). Цена - вклад в S при ДУ слоя.
 */
public final class Router {

    public static final class Start {
        /** Первая точка трассы (ОКС или узел сети). */
        final Coordinate p;
        /** Точка, с которой начинается свободный поиск (конец финального отрезка ОКС) или p. */
        final Coordinate e;
        /** Есть фиксированный отрезок p→e: задаёт направление для проверки первого поворота. */
        final boolean fixed;
        final int own;

        public Start(Coordinate p, Coordinate e, boolean fixed, int own) {
            this.p = p;
            this.e = e;
            this.fixed = fixed;
            this.own = own;
        }

        public static Start of(Context.Port port) {
            return new Start(port.p, port.e, port.fixed, port.own);
        }

        public static Start free(Coordinate p) {
            return new Start(p, p, false, -1);
        }
    }

    public static final class Result {
        /** Трасса от старта до цели, первая точка - p старта. */
        public final List<Coordinate> path;
        public final Goal goal;
        public final double score;

        Result(List<Coordinate> path, Goal goal, double score) {
            this.path = path;
            this.goal = goal;
            this.score = score;
        }
    }

    private static final class State implements Comparable<State> {
        final int node;
        final int prev;
        final double g;
        final double f;
        final State parent;
        final Goal goal;

        State(int node, int prev, double g, double f, State parent, Goal goal) {
            this.node = node;
            this.prev = prev;
            this.g = g;
            this.f = f;
            this.parent = parent;
            this.goal = goal;
        }

        @Override
        public int compareTo(State o) {
            return Double.compare(f, o.f);
        }
    }

    private final ObstacleLayer layer;
    private final double maxTurnCos;
    private final double turnPenalty;
    private final int maxExpansions;

    public Router(ObstacleLayer layer, int maxExpansions) {
        this.layer = layer;
        this.maxTurnCos = Math.cos(Math.toRadians(layer.rules.props().getGeometry().getMaxTurnAngleDeg())) - 1e-9;
        this.turnPenalty = layer.rules.props().getGeometry().getTurnPenaltyM() * layer.rules.scorePerMeter(layer.dn, 1.0);
        this.maxExpansions = maxExpansions;
    }

    public Result route(Start start, TargetSet targets, Blockers blockers) {
        int n = layer.nodeCount();
        final int startNode = n;
        final int preNode = n + 1;
        double perMeter = layer.rules.scorePerMeter(layer.dn, 1.0);
        double minNode = targets.minNodeScore();

        double g0 = 0;
        if (start.fixed) {
            SegInfo first = layer.check(start.p, start.e, start.own, null);
            if (first == null || blockers.blocks(start.p, start.e, true, false)) {
                return null;
            }
            g0 = layer.segScore(first);
        }

        PriorityQueue<State> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        open.add(new State(startNode, preNode, g0, g0 + targets.minDistance(start.e) * perMeter + minNode, null, null));
        List<Goal> goals = new ArrayList<>();
        int expansions = 0;

        while (!open.isEmpty()) {
            State s = open.poll();
            if (s.goal != null) {
                return new Result(reconstruct(s, start, n), s.goal, s.g);
            }
            long key = (long) s.prev * (n + 2) + s.node;
            Double known = best.get(key);
            if (known != null && known < s.g - 1e-12) {
                continue;
            }
            if (++expansions > maxExpansions) {
                return null;
            }
            Coordinate v = coord(s.node, start, n);
            Coordinate from = s.prev == preNode ? (start.fixed ? start.p : null) : coord(s.prev, start, n);
            // касаться сети можно только в стартовом узле (перетрассировка от камеры) и в цели
            boolean atStart = s.node == startNode && !start.fixed;

            goals.clear();
            targets.candidates(v, goals);
            for (Goal goal : goals) {
                if (from != null && !turnOk(from, v, goal.point)) {
                    continue;
                }
                SegInfo info = layer.check(v, goal.point, -1, goal.exemptLines);
                if (info == null || blockers.blocks(v, goal.point, atStart, true)) {
                    continue;
                }
                double g = s.g + layer.segScore(info) + goal.nodeScore + (from != null && turned(from, v, goal.point) ? turnPenalty : 0);
                open.add(new State(-1, s.node, g, g, s, goal));
            }

            List<ObstacleLayer.Edge> edges = s.node == startNode ? layer.neighborsOf(v) : layer.neighbors(s.node);
            for (ObstacleLayer.Edge e : edges) {
                Coordinate w = layer.nodes.get(e.to);
                if (from != null && !turnOk(from, v, w)) {
                    continue;
                }
                if (blockers.blocks(v, w, atStart, false)) {
                    continue;
                }
                double g = s.g + e.cost + (from != null && turned(from, v, w) ? turnPenalty : 0);
                long k2 = (long) s.node * (n + 2) + e.to;
                Double old = best.get(k2);
                if (old != null && old <= g) {
                    continue;
                }
                best.put(k2, g);
                open.add(new State(e.to, s.node, g, g + targets.minDistance(w) * perMeter + minNode, s, null));
            }
        }
        return null;
    }

    private Coordinate coord(int node, Start start, int n) {
        return node == n ? start.e : layer.nodes.get(node);
    }

    private List<Coordinate> reconstruct(State goalState, Start start, int n) {
        List<Coordinate> path = new ArrayList<>();
        path.add(goalState.goal.point);
        for (State s = goalState.parent; s != null; s = s.parent) {
            path.add(coord(s.node, start, n));
        }
        if (start.fixed) {
            path.add(start.p);
        }
        Collections.reverse(path);
        return path;
    }

    /** Угол отклонения от продолжения предыдущего отрезка ≤ максимума. */
    boolean turnOk(Coordinate a, Coordinate v, Coordinate b) {
        return cosTurn(a, v, b) >= maxTurnCos;
    }

    private static boolean turned(Coordinate a, Coordinate v, Coordinate b) {
        return cosTurn(a, v, b) < Math.cos(Math.toRadians(2));
    }

    static double cosTurn(Coordinate a, Coordinate v, Coordinate b) {
        double x1 = v.x - a.x;
        double y1 = v.y - a.y;
        double x2 = b.x - v.x;
        double y2 = b.y - v.y;
        double l = Math.hypot(x1, y1) * Math.hypot(x2, y2);
        return l < 1e-12 ? 1 : (x1 * x2 + y1 * y2) / l;
    }
}
