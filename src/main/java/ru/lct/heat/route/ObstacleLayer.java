package ru.lct.heat.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.config.HeatProperties.Restriction;
import ru.lct.heat.config.HeatProperties.Rule;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;

/**
 * Пространство допустимых положений оси трубы одного ДУ (раздел 5 rules.md):
 * запреты расширены на d_min + w/2 и непроходимы, дороги и линейные коммуникации - «мягкие» зоны,
 * которые можно только пересечь прямым отрезком. Здесь же узлы графа видимости и проверка отрезков.
 */
public final class ObstacleLayer {

    enum SoftKind { POLYGON, LINE }

    static final class Soft {
        SoftKind kind;
        Geometry zone;
        PreparedGeometry prep;
        Geometry core;
        Restriction rule;
        String type;
        int line = -1;
    }

    static final class Edge {
        final int to;
        final double cost;

        Edge(int to, double cost) {
            this.to = to;
            this.cost = cost;
        }
    }

    private static final double NODE_OFFSET = 0.05;

    public final int dn;
    final Rules rules;
    final Context ctx;
    final GeometryFactory gf;
    final double pipeWidth;

    /** Зоны запретов по объектам: для проверки финального отрезка ОКС без своего полигона. */
    final List<Geometry> hardZones = new ArrayList<>();
    final List<Integer> hardOwner = new ArrayList<>();
    final STRtree hardIndex = new STRtree();
    final Geometry hardUnion;
    final PreparedGeometry hardPrep;

    final List<Soft> softs = new ArrayList<>();
    final STRtree softIndex = new STRtree();
    final PreparedGeometry softPrep;

    final List<Coordinate> nodes = new ArrayList<>();
    /** Соседи вершины препятствия по контуру (для отсечения некасательных рёбер) или null. */
    final List<Coordinate[]> nodeRing = new ArrayList<>();
    private final List<List<Edge>> visibility = new ArrayList<>();
    private final STRtree nodeIndex = new STRtree();
    private final double radius;

    ObstacleLayer(Context ctx, int dn) {
        this.ctx = ctx;
        this.rules = ctx.rules;
        this.dn = dn;
        this.gf = ctx.gf;
        this.pipeWidth = rules.width(dn);
        HeatProperties.Geometry gp = rules.props().getGeometry();
        this.radius = gp.getVisibilityRadiusM();
        double tol = gp.getSimplifyToleranceM();
        double margin = gp.getClearanceMarginM();

        for (int idx : ctx.restrictionsInArea) {
            Scenario.RestrictionFeature r = ctx.scenario.restrictions.get(idx);
            Restriction rule = rules.restriction(r.type);
            Geometry simplified = TopologyPreservingSimplifier.simplify(r.geom, tol);
            if (rule.getRule() == Rule.FORBID) {
                double dist = rules.offset(rule, dn) + pipeWidth / 2 + tol + margin;
                Geometry zone = buffer(simplified, dist);
                hardZones.add(zone);
                hardOwner.add(idx);
                hardIndex.insert(zone.getEnvelopeInternal(), hardZones.size() - 1);
            } else {
                double dist = rules.offset(rule, dn) + pipeWidth / 2 + rule.getOwnWidth() / 2 + tol + margin;
                Soft s = new Soft();
                s.kind = simplified instanceof Polygonal ? SoftKind.POLYGON : SoftKind.LINE;
                s.core = simplified;
                s.zone = buffer(simplified, dist);
                s.rule = rule;
                s.type = r.type;
                softs.add(s);
            }
        }
        // существующая сеть: пересекать можно (K 1,05), идти вдоль ближе отступа нельзя
        Restriction crossing = rules.props().getExistingNetworkCrossing();
        for (int i = 0; i < ctx.scenario.network.size(); i++) {
            Scenario.NetworkLine line = ctx.scenario.network.get(i);
            if (!ctx.area.intersects(line.geom.getEnvelopeInternal())) {
                continue;
            }
            double dist = rules.offset(crossing, dn) + pipeWidth / 2 + rules.width(line.dn) / 2 + margin;
            Soft s = new Soft();
            s.kind = SoftKind.LINE;
            s.core = line.geom;
            s.zone = buffer(line.geom, dist);
            s.rule = crossing;
            s.type = "heat_network";
            s.line = i;
            softs.add(s);
        }
        for (Soft s : softs) {
            s.prep = PreparedGeometryFactory.prepare(s.zone);
            softIndex.insert(s.zone.getEnvelopeInternal(), s);
        }
        softIndex.build();
        hardIndex.build();

        hardUnion = hardZones.isEmpty() ? gf.createPolygon() : UnaryUnionOp.union(hardZones);
        hardPrep = PreparedGeometryFactory.prepare(hardUnion);
        List<Geometry> softZones = new ArrayList<>();
        for (Soft s : softs) {
            softZones.add(s.zone);
        }
        softPrep = softZones.isEmpty() ? null : PreparedGeometryFactory.prepare(UnaryUnionOp.union(softZones));

        buildNodes(gp);
    }

    /** Буфер с острыми (mitre) углами: вершин столько же, сколько у контура, и угол не ближе d. */
    private Geometry buffer(Geometry g, double dist) {
        BufferParameters bp = new BufferParameters();
        bp.setJoinStyle(BufferParameters.JOIN_MITRE);
        bp.setMitreLimit(2.0);
        bp.setEndCapStyle(BufferParameters.CAP_SQUARE);
        return BufferOp.bufferOp(g, dist, bp);
    }

    private void buildNodes(HeatProperties.Geometry gp) {
        addConvexVertices(hardUnion);
        for (Soft s : softs) {
            addConvexVertices(s.zone);
            // узлы вдоль мягких зон: чтобы повернуть сразу после спецперехода
            double out = s.kind == SoftKind.POLYGON ? Math.max(s.rule.getExtraM(), 0) : 0;
            Geometry ring = s.kind == SoftKind.POLYGON
                    ? buffer(s.core, Math.max(out, distOf(s)) + 0.3).getBoundary()
                    : s.zone.buffer(0.3, 2).getBoundary();
            sample(ring, gp.getRoadSampleStepM());
        }
        // редкая сетка на открытых местах: видимость ограничена радиусом, длинные пути идут через неё
        double step = radius / 2.5;
        for (double x = ctx.area.getMinX() + step / 2; x < ctx.area.getMaxX(); x += step) {
            for (double y = ctx.area.getMinY() + step / 2; y < ctx.area.getMaxY(); y += step) {
                addNode(new Coordinate(x, y), null);
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            visibility.add(null);
            nodeIndex.insert(new Envelope(nodes.get(i)), i);
        }
        nodeIndex.build();
    }

    /** Узлы в радиусе видимости от точки. */
    @SuppressWarnings("unchecked")
    private List<Integer> near(Coordinate a) {
        return nodeIndex.query(new Envelope(a.x - radius, a.x + radius, a.y - radius, a.y + radius));
    }

    private double distOf(Soft s) {
        return rules.offset(s.rule, dn) + pipeWidth / 2;
    }

    private void sample(Geometry ring, double step) {
        for (int g = 0; g < ring.getNumGeometries(); g++) {
            LineString ls = (LineString) ring.getGeometryN(g);
            Coordinate[] cs = ls.getCoordinates();
            double carry = 0;
            for (int i = 0; i + 1 < cs.length; i++) {
                double len = cs[i].distance(cs[i + 1]);
                double t = step - carry;
                while (t < len) {
                    double f = t / len;
                    addNode(new Coordinate(cs[i].x + (cs[i + 1].x - cs[i].x) * f, cs[i].y + (cs[i + 1].y - cs[i].y) * f), null);
                    t += step;
                }
                carry = (carry + len) % step;
            }
        }
    }

    private void addConvexVertices(Geometry g) {
        for (int p = 0; p < g.getNumGeometries(); p++) {
            Geometry part = g.getGeometryN(p);
            if (!(part instanceof Polygon)) {
                continue;
            }
            Polygon poly = (Polygon) part;
            addRing(poly.getExteriorRing(), true);
            for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                addRing(poly.getInteriorRingN(h), false);
            }
        }
    }

    private void addRing(LinearRing ring, boolean shell) {
        Coordinate[] cs = ring.getCoordinates();
        int n = cs.length - 1;
        if (n < 3) {
            return;
        }
        boolean ccw = Orientation.isCCW(cs);
        boolean obstacleOnLeft = shell == ccw;
        for (int i = 0; i < n; i++) {
            Coordinate prev = cs[(i - 1 + n) % n];
            Coordinate v = cs[i];
            Coordinate next = cs[(i + 1) % n];
            int turn = Orientation.index(prev, v, next);
            boolean convex = obstacleOnLeft ? turn == Orientation.COUNTERCLOCKWISE : turn == Orientation.CLOCKWISE;
            if (!convex) {
                continue;
            }
            double[] d1 = unit(v.x - prev.x, v.y - prev.y);
            double[] d2 = unit(v.x - next.x, v.y - next.y);
            double[] out = unit(d1[0] + d2[0], d1[1] + d2[1]);
            addNode(new Coordinate(v.x + out[0] * NODE_OFFSET, v.y + out[1] * NODE_OFFSET), new Coordinate[] {prev, next});
        }
    }

    private void addNode(Coordinate c, Coordinate[] ring) {
        if (!ctx.area.contains(c) || isBlocked(c)) {
            return;
        }
        nodes.add(c);
        nodeRing.add(ring);
    }

    /** Точка внутри запрета или мягкой зоны. */
    public boolean isBlocked(Coordinate c) {
        org.locationtech.jts.geom.Point p = gf.createPoint(c);
        return hardPrep.intersects(p) || (softPrep != null && softPrep.intersects(p));
    }

    /** Точка внутри запрета (мягкие зоны не учитываются: точка врезки лежит в коридоре своей линии). */
    public boolean isHardBlocked(Coordinate c) {
        return hardPrep.intersects(gf.createPoint(c));
    }

    public static double[] unit(double x, double y) {
        double l = Math.hypot(x, y);
        return l < 1e-12 ? new double[] {0, 0} : new double[] {x / l, y / l};
    }

    public int nodeCount() {
        return nodes.size();
    }

    /** Соседи по графу видимости (лениво, с кэшем). Проверка без исключений. */
    synchronized List<Edge> neighbors(int i) {
        List<Edge> cached = visibility.get(i);
        if (cached != null) {
            return cached;
        }
        List<Edge> out = new ArrayList<>();
        Coordinate a = nodes.get(i);
        Coordinate[] ra = nodeRing.get(i);
        for (int j : near(a)) {
            if (j == i) {
                continue;
            }
            Coordinate b = nodes.get(j);
            if (!tangent(a, b, ra) || !tangent(b, a, nodeRing.get(j))) {
                continue;
            }
            SegInfo info = check(a, b, -1, null);
            if (info != null) {
                out.add(new Edge(j, segScore(info)));
            }
        }
        visibility.set(i, out);
        return out;
    }

    /** Все рёбра из произвольной точки (порт ОКС, камера) к узлам графа. */
    List<Edge> neighborsOf(Coordinate a) {
        List<Edge> out = new ArrayList<>();
        for (int j : near(a)) {
            Coordinate b = nodes.get(j);
            if (!tangent(b, a, nodeRing.get(j))) {
                continue;
            }
            SegInfo info = check(a, b, -1, null);
            if (info != null) {
                out.add(new Edge(j, segScore(info)));
            }
        }
        return out;
    }

    /** Ребро из вершины препятствия не должно уходить внутрь препятствия (касательное ребро). */
    private static boolean tangent(Coordinate v, Coordinate w, Coordinate[] ring) {
        if (ring == null) {
            return true;
        }
        int o1 = Orientation.index(v, w, ring[0]);
        int o2 = Orientation.index(v, w, ring[1]);
        return o1 * o2 >= 0;
    }

    /** Вклад отрезка в S при ДУ слоя: базовая цена + надбавка спецучастков. */
    public double segScore(SegInfo info) {
        double s = info.length * rules.scorePerMeter(dn, 1.0);
        for (SegInfo.Part part : info.parts()) {
            if (part.k > 1.0) {
                s += part.length() * rules.costScore(rules.costPerM(dn) * (part.k - 1.0));
            }
        }
        return s;
    }

    /**
     * Проверка прямого отрезка a→b по всем плановым ограничениям.
     * exemptOwn - индекс ограничения, отступ до которого не проверяется (свой полигон ОКС, R-OKS-02);
     * exemptLines - линии сети, чей коридор не проверяется (подход к точке врезки).
     * Возвращает null, если отрезок недопустим.
     */
    public SegInfo check(Coordinate a, Coordinate b, int exemptOwn, int[] exemptLines) {
        double len = a.distance(b);
        if (len < 1e-9) {
            return new SegInfo(0, Collections.emptyList());
        }
        LineString seg = gf.createLineString(new Coordinate[] {a, b});
        if (exemptOwn < 0) {
            if (hardPrep.intersects(seg)) {
                return null;
            }
        } else {
            for (Object o : hardIndex.query(seg.getEnvelopeInternal())) {
                int z = (Integer) o;
                if (hardOwner.get(z) != exemptOwn && hardZones.get(z).intersects(seg)) {
                    return null;
                }
            }
        }
        if (softPrep == null || !softPrep.intersects(seg)) {
            return new SegInfo(len, Collections.emptyList());
        }
        List<SegInfo.Special> specials = new ArrayList<>();
        for (Object o : softIndex.query(seg.getEnvelopeInternal())) {
            Soft s = (Soft) o;
            if (s.line >= 0 && contains(exemptLines, s.line)) {
                continue;
            }
            if (!s.prep.intersects(seg)) {
                continue;
            }
            if (!crossSoft(s, a, b, seg, len, specials)) {
                return null;
            }
        }
        return new SegInfo(len, specials);
    }

    private boolean crossSoft(Soft s, Coordinate a, Coordinate b, LineString seg, double len, List<SegInfo.Special> out) {
        org.locationtech.jts.geom.Point pa = gf.createPoint(a);
        org.locationtech.jts.geom.Point pb = gf.createPoint(b);
        if (s.prep.intersects(pa) || s.prep.intersects(pb)) {
            // внутри зоны нельзя ни начинать, ни заканчивать, ни поворачивать
            return false;
        }
        double extra = s.rule.getExtraM();
        double minAngle = s.rule.getMinAngleDeg();
        if (s.kind == SoftKind.POLYGON) {
            Geometry inside = seg.intersection(s.core);
            if (inside.isEmpty()) {
                return false; // задевает только зону отступа, не пересекая объект
            }
            if (minAngle > 0 && !anglesOk(s.core, a, b, minAngle)) {
                return false;
            }
            for (int i = 0; i < inside.getNumGeometries(); i++) {
                Geometry piece = inside.getGeometryN(i);
                double u0 = Double.MAX_VALUE;
                double u1 = -Double.MAX_VALUE;
                for (Coordinate c : piece.getCoordinates()) {
                    double u = a.distance(c);
                    u0 = Math.min(u0, u);
                    u1 = Math.max(u1, u);
                }
                double from = u0 - extra;
                double to = u1 + extra;
                if (from < -1e-6 || to > len + 1e-6) {
                    return false; // спецучасток должен быть одним прямым отрезком (R-RST-05)
                }
                out.add(new SegInfo.Special(from, to, s.rule.getKSpec(), s.type));
            }
        } else {
            Geometry x = seg.intersection(s.core);
            if (x.isEmpty()) {
                return false; // идёт вдоль линии ближе отступа, не пересекая её
            }
            if (minAngle > 0 && !anglesOk(s.core, a, b, minAngle)) {
                return false;
            }
            for (Coordinate c : x.getCoordinates()) {
                double u = a.distance(c);
                if (u - extra < -1e-6 || u + extra > len + 1e-6) {
                    return false;
                }
                // допущение 3: зона отступа пересекается насквозь, спеццена только на ±extra
                out.add(new SegInfo.Special(u - extra, u + extra, s.rule.getKSpec(), s.type));
            }
        }
        // каждая часть отрезка в зоне отступа должна быть частью перехода, а не проходом вдоль
        Geometry inZone = seg.intersection(s.zone);
        for (int i = 0; i < inZone.getNumGeometries(); i++) {
            Geometry piece = inZone.getGeometryN(i);
            if (!piece.intersects(s.core)) {
                return false;
            }
        }
        return true;
    }

    /** Угол между отрезком и контуром ограничения во всех точках пересечения (R-RST-06). */
    private static boolean anglesOk(Geometry core, Coordinate a, Coordinate b, double minAngleDeg) {
        LineSegment seg = new LineSegment(a, b);
        Geometry boundary = core instanceof Polygonal ? core.getBoundary() : core;
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] cs = boundary.getGeometryN(g).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                LineSegment edge = new LineSegment(cs[i], cs[i + 1]);
                if (edge.intersection(seg) == null) {
                    continue;
                }
                double ang = Math.toDegrees(Math.abs(Math.atan2(
                        (b.x - a.x) * (cs[i + 1].y - cs[i].y) - (b.y - a.y) * (cs[i + 1].x - cs[i].x),
                        (b.x - a.x) * (cs[i + 1].x - cs[i].x) + (b.y - a.y) * (cs[i + 1].y - cs[i].y))));
                double acute = Math.min(ang, 180 - ang);
                if (acute + 1e-6 < minAngleDeg) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean contains(int[] arr, int v) {
        if (arr == null) {
            return false;
        }
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    public Envelope area() {
        return ctx.area;
    }
}
