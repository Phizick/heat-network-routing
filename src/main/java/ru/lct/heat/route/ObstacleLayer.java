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
import ru.lct.heat.config.HeatProperties.Zone;
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
        /** Подготовленная геометрия объекта: быстрый отсев отрезков, которые его не пересекают. */
        PreparedGeometry corePrep;
        /** Дорога/трамвай, заданные линией: зона спецучастка от центральной линии (ответ организаторов). */
        Geometry specialZone;
        PreparedGeometry specialPrep;
        Restriction rule;
        String type;
        int line = -1;
        /** Полуширина зоны отступа (для ограничения длины подхода к точке врезки). */
        double halfWidth;
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
    public final HeatProperties.Mode mode;
    /** Монтажный режим: индексы узлов сетки по (столбец, строка) и обратное соответствие. */
    private final java.util.Map<Long, Integer> gridIndex = new java.util.HashMap<>();
    private final List<int[]> gridCell = new ArrayList<>();
    private double gridStep;
    private double gridCos;
    private double gridSin;
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

    /** Монтажный режим: коридор вдоль улиц - полоса за зоной отступа дороги, где ведём трассы. */
    private PreparedGeometry streetCorridor;
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
        this(ctx, dn, HeatProperties.Mode.ECONOMY);
    }

    ObstacleLayer(Context ctx, int dn, HeatProperties.Mode mode) {
        this.ctx = ctx;
        this.mode = mode;
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
            if (rule.getRule() == Rule.IGNORE) {
                continue; // тип без правил: по ответу организаторов игнорируем
            }
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
                // границы спецучастка и угол - по исходной геометрии, зона отступа - по упрощённой с запасом tol
                s.core = r.geom;
                s.zone = buffer(simplified, dist);
                s.halfWidth = dist;
                if (s.kind == SoftKind.LINE && rule.getZone() == Zone.POLYGON_PLUS) {
                    // дорога задана линией: спецучасток отмеряется от центральной линии
                    s.specialZone = buffer(r.geom, rule.getExtraM());
                }
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
            s.halfWidth = dist;
            s.rule = crossing;
            s.type = "heat_network";
            s.line = i;
            softs.add(s);
        }
        for (Soft s : softs) {
            s.prep = PreparedGeometryFactory.prepare(s.zone);
            s.corePrep = PreparedGeometryFactory.prepare(s.core);
            if (s.specialZone != null) {
                s.specialPrep = PreparedGeometryFactory.prepare(s.specialZone);
            }
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

        buildStreetCorridor();
        buildNodes(gp);
    }

    /**
     * Коридор вдоль улиц: полоса шириной street-corridor-m сразу за зоной отступа дороги.
     * Внутрь дороги нельзя (R-RST-02), но идти рядом с ней - удобно для монтажа.
     */
    private void buildStreetCorridor() {
        if (mode != HeatProperties.Mode.INSTALLATION) {
            return;
        }
        double width = rules.props().getInstallation().getStreetCorridorM();
        List<Geometry> bands = new ArrayList<>();
        for (Soft s : softs) {
            if (s.rule.getZone() != Zone.POLYGON_PLUS) {
                continue; // только дороги и трамвайные пути
            }
            bands.add(s.zone.buffer(width).difference(s.zone));
        }
        if (!bands.isEmpty()) {
            streetCorridor = PreparedGeometryFactory.prepare(UnaryUnionOp.union(bands));
        }
    }

    /** Множитель цены для поиска: вдоль улицы дешевле, параллельно чужой трубе дороже. */
    private double routingFactor(Coordinate a, Coordinate b) {
        double factor = parallelPenalty(a, b);
        if (streetCorridor != null) {
            Coordinate mid = new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2);
            if (streetCorridor.intersects(gf.createPoint(mid))) {
                factor *= rules.props().getInstallation().getStreetPreference();
            }
        }
        return factor;
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
        if (mode == HeatProperties.Mode.INSTALLATION) {
            buildGrid();
            return;
        }
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

    /**
     * Монтажный режим: узлы - точки регулярной сетки, ориентированной по кварталу.
     * Ходы по сетке дают направления кратно 45°, а повороты - ровно 45° или 90°.
     */
    private void buildGrid() {
        HeatProperties.Installation cfg = rules.props().getInstallation();
        gridStep = cfg.getGridStepM();
        double angle = ctx.gridAngle();
        gridCos = Math.cos(angle);
        gridSin = Math.sin(angle);
        Envelope area = ctx.area;
        Coordinate origin = new Coordinate(area.getMinX(), area.getMinY());
        double diag = Math.hypot(area.getWidth(), area.getHeight());
        int steps = (int) Math.ceil(diag / gridStep) + 2;
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                Coordinate c = gridPoint(origin, i, j);
                if (!area.contains(c) || isBlocked(c)) {
                    continue;
                }
                gridIndex.put(key(i, j), nodes.size());
                gridCell.add(new int[] {i, j});
                nodes.add(c);
                nodeRing.add(null);
            }
        }
        for (int i = 0; i < nodes.size(); i++) {
            visibility.add(null);
            nodeIndex.insert(new Envelope(nodes.get(i)), i);
        }
        nodeIndex.build();
    }

    private Coordinate gridPoint(Coordinate origin, int i, int j) {
        return new Coordinate(origin.x + (i * gridCos - j * gridSin) * gridStep,
                origin.y + (i * gridSin + j * gridCos) * gridStep);
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xffffffffL);
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
        if (mode == HeatProperties.Mode.INSTALLATION) {
            List<Edge> grid = gridNeighbors(i);
            visibility.set(i, grid);
            return grid;
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

    /**
     * Монтажный режим: восемь соседей по сетке. Длинные прямые получаются цепочкой ходов,
     * лишние вершины потом убирает спрямление.
     */
    private List<Edge> gridNeighbors(int i) {
        List<Edge> out = new ArrayList<>();
        int[] cell = gridCell.get(i);
        Coordinate a = nodes.get(i);
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) {
                    continue;
                }
                Integer j = gridIndex.get(key(cell[0] + di, cell[1] + dj));
                if (j == null) {
                    continue;
                }
                SegInfo info = check(a, nodes.get(j), -1, null);
                if (info != null) {
                    out.add(new Edge(j, segScore(info)));
                }
            }
        }
        return out;
    }

    public double gridStep() {
        return gridStep;
    }

    /**
     * Отрезок идёт параллельно существующей линии сети вплотную: в монтажном режиме так не кладут,
     * вместо этого делают врезку рядом. Возвращает множитель цены для поиска (1 - штрафа нет).
     */
    public double parallelPenalty(Coordinate a, Coordinate b) {
        if (mode != HeatProperties.Mode.INSTALLATION) {
            return 1;
        }
        HeatProperties.Installation cfg = rules.props().getInstallation();
        double d = cfg.getParallelDistanceM();
        Envelope env = new Envelope(a, b);
        env.expandBy(d);
        LineSegment seg = new LineSegment(a, b);
        for (Object o : ctx.network.segIndex.query(env)) {
            ExistingNetwork.Seg line = (ExistingNetwork.Seg) o;
            if (seg.distance(line.seg) > d) {
                continue;
            }
            double angle = Math.abs(Math.toDegrees(Math.atan2(
                    (b.x - a.x) * (line.seg.p1.y - line.seg.p0.y) - (b.y - a.y) * (line.seg.p1.x - line.seg.p0.x),
                    (b.x - a.x) * (line.seg.p1.x - line.seg.p0.x) + (b.y - a.y) * (line.seg.p1.y - line.seg.p0.y))));
            double acute = Math.min(angle, 180 - angle);
            if (acute <= cfg.getParallelAngleDeg()) {
                return cfg.getParallelPenalty();
            }
        }
        return 1;
    }

    /** Отрезок лежит по сетке квартала: направление кратно 45° от базового (в экономичном режиме - всегда). */
    public boolean gridAligned(Coordinate a, Coordinate b) {
        if (mode != HeatProperties.Mode.INSTALLATION) {
            return true;
        }
        double len = a.distance(b);
        if (len < 1e-6) {
            return true;
        }
        double angle = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x) - ctx.gridAngle());
        double mod = ((angle % 45) + 45) % 45;
        return Math.min(mod, 45 - mod) < 1.5;
    }

    /** Ближайший свободный узел сетки к точке (для подключения порта ОКС и целей). */
    @SuppressWarnings("unchecked")
    int nearestGridNode(Coordinate c) {
        List<Integer> near = nodeIndex.query(new Envelope(c.x - gridStep * 3, c.x + gridStep * 3,
                c.y - gridStep * 3, c.y + gridStep * 3));
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i : near) {
            double d = nodes.get(i).distance(c);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Все рёбра из произвольной точки (порт ОКС, камера) к узлам графа. */
    List<Edge> neighborsOf(Coordinate a) {
        List<Edge> out = new ArrayList<>();
        if (mode == HeatProperties.Mode.INSTALLATION) {
            // вход в сетку: короткая перемычка от выхода из здания к ближайшим узлам
            double limit = gridStep * 2;
            @SuppressWarnings("unchecked")
            List<Integer> candidates = nodeIndex.query(new Envelope(a.x - limit, a.x + limit, a.y - limit, a.y + limit));
            for (int j : candidates) {
                Coordinate b = nodes.get(j);
                if (a.distance(b) > limit) {
                    continue;
                }
                SegInfo info = check(a, b, -1, null);
                if (info != null) {
                    out.add(new Edge(j, segScore(info)));
                }
            }
            return out;
        }
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
            if (!s.prep.intersects(seg)) {
                continue;
            }
            if (s.line >= 0 && contains(exemptLines, s.line)) {
                if (!approachOk(s, a, b, seg)) {
                    return null;
                }
                continue;
            }
            if (!crossSoft(s, a, b, seg, len, specials)) {
                return null;
            }
        }
        return new SegInfo(len, specials);
    }

    /**
     * Подход к точке врезки на линии: коридор линии не проверяется только на последнем куске, который
     * входит в зону и заканчивается в цели b, и не длиннее двух полуширин зоны (угол подхода ≳ 30°).
     */
    private boolean approachOk(Soft s, Coordinate a, Coordinate b, LineString seg) {
        // Начало подхода не может быть внутри коридора: иначе трасса идёт вдоль линии.
        if (s.prep.intersects(gf.createPoint(a))) {
            return false;
        }
        Coordinate[] cs = s.core.getCoordinates();
        if (cs.length > 200) {
            return approachByOverlay(s, b, seg); // очень длинная линия: считаем честно
        }
        LineSegment ab = new LineSegment(a, b);
        int crossings = 0;
        LineSegment nearest = null;
        double nearestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < cs.length; i++) {
            LineSegment core = new LineSegment(cs[i], cs[i + 1]);
            if (ab.intersection(core) != null) {
                crossings++;
            }
            double d = core.distance(b);
            if (d < nearestD) {
                nearestD = d;
                nearest = core;
            }
        }
        if (crossings > 1 || nearest == null) {
            return false; // пересекает линию несколько раз: это не подход к врезке
        }
        // длина куска внутри коридора = полуширина / sin(угла подхода)
        double angle = Math.abs(Math.toDegrees(Math.atan2(
                (b.x - a.x) * (nearest.p1.y - nearest.p0.y) - (b.y - a.y) * (nearest.p1.x - nearest.p0.x),
                (b.x - a.x) * (nearest.p1.x - nearest.p0.x) + (b.y - a.y) * (nearest.p1.y - nearest.p0.y))));
        double acute = Math.min(angle, 180 - angle);
        double sin = Math.sin(Math.toRadians(acute));
        double inside = sin < 1e-6 ? Double.MAX_VALUE : s.halfWidth / sin;
        return inside <= approachLimit(s.halfWidth);
    }

    /** Точный вариант через пересечение геометрий: для очень длинных линий. */
    private boolean approachByOverlay(Soft s, Coordinate b, LineString seg) {
        Geometry inZone = seg.intersection(s.zone);
        for (int i = 0; i < inZone.getNumGeometries(); i++) {
            Geometry piece = inZone.getGeometryN(i);
            if (piece.distance(gf.createPoint(b)) > 1e-6 || piece.getLength() > approachLimit(s.halfWidth)) {
                return false;
            }
        }
        return true;
    }

    public static double approachLimit(double halfWidth) {
        return 2 * halfWidth + 0.5;
    }

    private boolean crossSoft(Soft s, Coordinate a, Coordinate b, LineString seg, double len, List<SegInfo.Special> out) {
        org.locationtech.jts.geom.Point pa = gf.createPoint(a);
        org.locationtech.jts.geom.Point pb = gf.createPoint(b);
        if (s.prep.intersects(pa) || s.prep.intersects(pb)) {
            // внутри зоны нельзя ни начинать, ни заканчивать, ни поворачивать
            return false;
        }
        // быстрый отсев: отрезок задевает только зону отступа, сам объект не пересекает
        if (!s.corePrep.intersects(seg)) {
            return false;
        }
        double extra = s.rule.getExtraM();
        double minAngle = s.rule.getMinAngleDeg();
        List<double[]> intervals = new ArrayList<>();
        // куски отрезка внутри зоны отступа: обычной прокладки в них быть не может
        List<double[]> zonePieces = insideIntervals(s.zone, s.prep, a, b, len);
        if (s.kind == SoftKind.POLYGON) {
            List<double[]> insideCore = insideIntervals(s.core, s.corePrep, a, b, len);
            if (insideCore.isEmpty()) {
                return false; // задевает только зону отступа, не пересекая объект
            }
            if (minAngle > 0 && !anglesOk(s.core, a, b, minAngle)) {
                return false;
            }
            for (double[] core : insideCore) {
                double u0 = core[0];
                double u1 = core[1];
                double from = u0 - extra;
                double to = u1 + extra;
                // спецучасток покрывает и зону отступа: при косом пересечении она шире, чем полигон + extra
                for (double[] z : zonePieces) {
                    if (z[0] <= u1 + 1e-6 && z[1] >= u0 - 1e-6) {
                        from = Math.min(from, z[0]);
                        to = Math.max(to, z[1]);
                    }
                }
                if (from < -1e-6 || to > len + 1e-6) {
                    return false; // спецучасток должен быть одним прямым отрезком (R-RST-05)
                }
                out.add(new SegInfo.Special(from, to, s.rule.getKSpec(), s.type));
                intervals.add(new double[] {from, to});
            }
        } else {
            List<Double> crossings = new ArrayList<>();
            LineSegment ab = new LineSegment(a, b);
            Coordinate[] cs = s.core.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                Coordinate x = ab.intersection(new LineSegment(cs[i], cs[i + 1]));
                if (x != null) {
                    crossings.add(a.distance(x));
                }
            }
            if (crossings.isEmpty()) {
                return false; // идёт вдоль линии ближе отступа, не пересекая её
            }
            if (minAngle > 0 && !anglesOk(s.core, a, b, minAngle)) {
                return false;
            }
            if (s.specialZone != null) {
                // дорога линией: спецучасток - часть отрезка внутри полосы «линия ± extra»
                for (double[] iv : insideIntervals(s.specialZone, s.specialPrep, a, b, len)) {
                    if (iv[0] < 1e-6 && iv[1] > len - 1e-6 && len > 2 * extra) {
                        return false; // отрезок не выходит за полосу: не прямой переход, а движение вдоль
                    }
                    out.add(new SegInfo.Special(iv[0], iv[1], s.rule.getKSpec(), s.type, s.line, (iv[0] + iv[1]) / 2));
                    intervals.add(new double[] {iv[0], iv[1]});
                }
            } else {
                for (double u : crossings) {
                    if (u - extra < -1e-6 || u + extra > len + 1e-6) {
                        return false;
                    }
                    // допущение 3: зона отступа пересекается насквозь, спеццена только на ±extra
                    out.add(new SegInfo.Special(u - extra, u + extra, s.rule.getKSpec(), s.type, s.line, u));
                    intervals.add(new double[] {u - extra, u + extra});
                }
            }
        }
        // каждая часть отрезка в зоне отступа должна быть частью перехода, а не проходом вдоль
        if (s.kind == SoftKind.POLYGON || s.specialZone != null) {
            // R-RST-02/05: обычной прокладки в зоне отступа дороги быть не может - только спецучасток
            for (double[] z : zonePieces) {
                if (z[1] - z[0] < 1e-6) {
                    continue;
                }
                boolean covered = false;
                for (double[] iv : intervals) {
                    if (iv[0] <= z[0] + 1e-6 && iv[1] >= z[1] - 1e-6) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Интервалы [от, до] (метры от a), на которых отрезок лежит внутри геометрии.
     * Считается арифметикой по границам плюс проверка середин: дешевле, чем пересечение геометрий.
     */
    private List<double[]> insideIntervals(Geometry geom, PreparedGeometry prep, Coordinate a, Coordinate b, double len) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0);
        cuts.add(len);
        LineSegment ab = new LineSegment(a, b);
        Geometry boundary = geom instanceof Polygonal ? geom.getBoundary() : geom;
        for (int g = 0; g < boundary.getNumGeometries(); g++) {
            Coordinate[] cs = boundary.getGeometryN(g).getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                Coordinate x = ab.intersection(new LineSegment(cs[i], cs[i + 1]));
                if (x != null) {
                    cuts.add(Math.max(0, Math.min(len, a.distance(x))));
                }
            }
        }
        cuts.sort(Double::compare);
        List<double[]> out = new ArrayList<>();
        double[] dir = unit(b.x - a.x, b.y - a.y);
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double t0 = cuts.get(i);
            double t1 = cuts.get(i + 1);
            if (t1 - t0 < 1e-7) {
                continue;
            }
            double mid = (t0 + t1) / 2;
            if (prep.intersects(gf.createPoint(new Coordinate(a.x + dir[0] * mid, a.y + dir[1] * mid)))) {
                double[] last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && t0 - last[1] < 1e-7) {
                    last[1] = t1;
                } else {
                    out.add(new double[] {t0, t1});
                }
            }
        }
        return out;
    }

    /** Куски пересечения отрезка с геометрией как интервалы [от, до] метров от точки a. */
    private static List<double[]> pieces(Geometry intersection, Coordinate a) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < intersection.getNumGeometries(); i++) {
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (Coordinate c : intersection.getGeometryN(i).getCoordinates()) {
                double u = a.distance(c);
                lo = Math.min(lo, u);
                hi = Math.max(hi, u);
            }
            if (hi >= lo) {
                out.add(new double[] {lo, hi});
            }
        }
        return out;
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
