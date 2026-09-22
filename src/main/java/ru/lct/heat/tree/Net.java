package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineSegment;

/**
 * Новая сеть: лес деревьев (R-TOP-02). Рёбра направлены от ребёнка к родителю (к врезке),
 * путь ребра хранится в том же направлении.
 */
public final class Net {

    public enum Kind { OKS, NEW_CHAMBER, EXISTING_CHAMBER }

    public static final class Node {
        public final int id;
        public final Kind kind;
        public Coordinate c;
        public int oks = -1;
        public int chamber = -1;
        /** Для новой камеры-врезки: линия существующей сети, иначе -1. */
        public int line = -1;
        public boolean root;

        Node(int id, Kind kind, Coordinate c) {
            this.id = id;
            this.kind = kind;
            this.c = c;
        }

        Node copy() {
            Node n = new Node(id, kind, c);
            n.oks = oks;
            n.chamber = chamber;
            n.line = line;
            n.root = root;
            return n;
        }
    }

    public static final class Edge {
        public final int id;
        public int child;
        public int parent;
        public List<Coordinate> path;
        public int dn;
        public double flow;
        /** Первый отрезок - финальный отрезок ОКС (R-OKS-02), его не делим и не спрямляем. */
        public boolean fromOks;

        Edge(int id, int child, int parent, List<Coordinate> path) {
            this.id = id;
            this.child = child;
            this.parent = parent;
            this.path = path;
        }

        Edge copy() {
            Edge e = new Edge(id, child, parent, new ArrayList<>(path));
            e.dn = dn;
            e.flow = flow;
            e.fromOks = fromOks;
            return e;
        }

        public double length() {
            double l = 0;
            for (int i = 0; i + 1 < path.size(); i++) {
                l += path.get(i).distance(path.get(i + 1));
            }
            return l;
        }
    }

    public final Map<Integer, Node> nodes = new LinkedHashMap<>();
    public final Map<Integer, Edge> edges = new LinkedHashMap<>();
    /** ОКС, для которых маршрут не найден, и причина. */
    public final Map<Integer, String> unconnected = new LinkedHashMap<>();
    public final Set<String> notes = new LinkedHashSet<>();
    private int nextId = 1;

    public Net copy() {
        Net n = new Net();
        nodes.forEach((k, v) -> n.nodes.put(k, v.copy()));
        edges.forEach((k, v) -> n.edges.put(k, v.copy()));
        n.unconnected.putAll(unconnected);
        n.notes.addAll(notes);
        n.nextId = nextId;
        return n;
    }

    /** Принять состояние другой сети целиком (включая счётчик id, чтобы id не повторялись). */
    public void assign(Net o) {
        nodes.clear();
        nodes.putAll(o.nodes);
        edges.clear();
        edges.putAll(o.edges);
        unconnected.clear();
        unconnected.putAll(o.unconnected);
        notes.clear();
        notes.addAll(o.notes);
        nextId = o.nextId;
    }

    public Node addNode(Kind kind, Coordinate c) {
        Node n = new Node(nextId++, kind, c);
        nodes.put(n.id, n);
        return n;
    }

    public Edge addEdge(int child, int parent, List<Coordinate> path) {
        Edge e = new Edge(nextId++, child, parent, path);
        edges.put(e.id, e);
        return e;
    }

    public Edge parentEdge(int node) {
        for (Edge e : edges.values()) {
            if (e.child == node) {
                return e;
            }
        }
        return null;
    }

    public List<Edge> childEdges(int node) {
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges.values()) {
            if (e.parent == node) {
                out.add(e);
            }
        }
        return out;
    }

    public Node oksNode(int oks) {
        for (Node n : nodes.values()) {
            if (n.kind == Kind.OKS && n.oks == oks) {
                return n;
            }
        }
        return null;
    }

    public Node existingChamberRoot(int chamber) {
        for (Node n : nodes.values()) {
            if (n.kind == Kind.EXISTING_CHAMBER && n.chamber == chamber) {
                return n;
            }
        }
        return null;
    }

    /** Все отрезки новой сети (для запрета пересечений). */
    public List<LineSegment> segments(Integer skipEdge) {
        List<LineSegment> out = new ArrayList<>();
        for (Edge e : edges.values()) {
            if (skipEdge != null && e.id == skipEdge) {
                continue;
            }
            for (int i = 0; i + 1 < e.path.size(); i++) {
                out.add(new LineSegment(e.path.get(i), e.path.get(i + 1)));
            }
        }
        return out;
    }

    /** Делит ребро в точке x (лежит на его пути): возвращает новую камеру ветвления. */
    public Node split(int edgeId, Coordinate x) {
        Edge e = edges.get(edgeId);
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < e.path.size(); i++) {
            double d = new LineSegment(e.path.get(i), e.path.get(i + 1)).distance(x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        Node chamber = addNode(Kind.NEW_CHAMBER, x);
        List<Coordinate> lower = new ArrayList<>(e.path.subList(0, best + 1));
        lower.add(x);
        List<Coordinate> upper = new ArrayList<>();
        upper.add(x);
        upper.addAll(e.path.subList(best + 1, e.path.size()));
        Edge up = addEdge(chamber.id, e.parent, upper);
        up.dn = e.dn;
        up.flow = e.flow;
        e.parent = chamber.id;
        e.path = lower;
        return chamber;
    }

    /** Число линейных участков, примыкающих к узлу (R-TOP-05), без учёта существующих линий. */
    public int newConnections(int node) {
        int n = 0;
        for (Edge e : edges.values()) {
            if (e.child == node || e.parent == node) {
                n++;
            }
        }
        return n;
    }
}
