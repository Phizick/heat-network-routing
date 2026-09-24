package ru.lct.heat.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.distance.DistanceOp;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.model.Rules;
import ru.lct.heat.model.Scenario;

/** Всё, что общее для расчёта одного датасета: область поиска, сеть, слои препятствий по ДУ, выходы из зданий. */
public final class Context {

    /** Начало трассы от ОКС: точка P и конец финального прямого отрезка E (R-OKS-02). */
    public static final class Port {
        public final Coordinate p;
        public final Coordinate e;
        public final int own;
        public final boolean fixed;
        public final String note;

        Port(Coordinate p, Coordinate e, int own, boolean fixed, String note) {
            this.p = p;
            this.e = e;
            this.own = own;
            this.fixed = fixed;
            this.note = note;
        }
    }

    public static final String OKS_TYPE = "oks";

    public final Scenario scenario;
    public final Rules rules;
    public final GeometryFactory gf;
    public final Envelope area;
    public final List<Integer> restrictionsInArea = new ArrayList<>();
    public final ExistingNetwork network;
    /** Индекс полигона-здания для каждой ОКС или -1 (R-IN-02). */
    public final int[] ownPolygon;
    public final List<String> diagnostics = new ArrayList<>();
    private final Map<Integer, ObstacleLayer> layers = new HashMap<>();
    private final Map<String, List<Port>> ports = new HashMap<>();
    /** Верхняя оценка ДУ в сети: по суммарному расходу всех ОКС. Узлы ставим так, чтобы они были допустимы и при нём. */
    public final int maxDn;

    public Context(Scenario scenario, Rules rules, int srid) {
        this.scenario = scenario;
        this.rules = rules;
        this.gf = new GeometryFactory(new PrecisionModel(), srid);
        HeatProperties.Geometry gp = rules.props().getGeometry();

        Envelope env = new Envelope();
        scenario.oks.forEach(o -> env.expandToInclude(o.geom.getCoordinate()));
        scenario.chambers.forEach(c -> env.expandToInclude(c.geom.getCoordinate()));
        scenario.sources.forEach(s -> env.expandToInclude(s.getCoordinate()));
        scenario.network.forEach(l -> env.expandToInclude(l.geom.getEnvelopeInternal()));
        env.expandBy(gp.getSearchAreaBufferM());
        this.area = env;

        for (int i = 0; i < scenario.restrictions.size(); i++) {
            Scenario.RestrictionFeature r = scenario.restrictions.get(i);
            if (area.intersects(r.geom.getEnvelopeInternal())) {
                restrictionsInArea.add(i);
                if (!rules.isKnownRestriction(r.type)) {
                    diagnostics.add("restriction " + r.id + ": тип " + r.type + " без правил в таблице 2, "
                            + describe(rules.props().getUnknownRestriction()));
                }
            }
        }
        this.network = new ExistingNetwork(scenario, gp.getTopologySnapToleranceM());
        double totalFlow = scenario.oks.stream().mapToDouble(o -> o.flow).sum();
        int byFlow = rules.minDnForFlow(totalFlow);
        this.maxDn = byFlow > 0 ? byFlow : rules.pipes().get(rules.pipes().size() - 1).getDn();
        diagnostics.addAll(network.diagnostics);

        ownPolygon = new int[scenario.oks.size()];
        double minOksOffset = rules.offset(rules.restriction(OKS_TYPE), rules.pipes().get(0).getDn());
        for (int k = 0; k < scenario.oks.size(); k++) {
            Scenario.Oks o = scenario.oks.get(k);
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int idx : restrictionsInArea) {
                Scenario.RestrictionFeature r = scenario.restrictions.get(idx);
                if (!OKS_TYPE.equals(r.type) || !(r.geom instanceof Polygonal)) {
                    continue;
                }
                double d = r.geom.distance(o.geom);
                if (d < bestDist) {
                    bestDist = d;
                    best = idx;
                }
            }
            if (best >= 0 && bestDist <= gp.getOksPolygonToleranceM()) {
                ownPolygon[k] = best;
            } else if (best >= 0 && bestDist < minOksOffset) {
                // точка в зоне отступа здания: без исключения для этого здания она недостижима
                ownPolygon[k] = best;
                diagnostics.add("ОКС " + o.id + ": точка вне полигона, но в " + String.format("%.1f", bestDist)
                        + " м от здания " + scenario.restrictions.get(best).id + ", здание считается своим");
            } else {
                ownPolygon[k] = -1;
            }
        }
    }

    /**
     * Слой препятствий для ДУ. Слои строятся на класс отступа (heat.geometry.clearance-classes):
     * ДУ класса получает отступы по верхнему ДУ класса - это консервативно и сильно экономит память.
     */
    /** Линии, занятые примыкания и ДУ в точке врезки новой камеры. */
    public ExistingNetwork.At tieAt(Coordinate x) {
        return network.at(x, Math.max(rules.props().getGeometry().getTopologySnapToleranceM(), 0.05));
    }

    private static String describe(HeatProperties.Restriction r) {
        switch (r.getRule()) {
            case IGNORE:
                return "игнорируется";
            case SPECIAL:
                return "обработан как спецпереход";
            default:
                return "обработан как запрет с отступом " + r.getOffset() + " м";
        }
    }

    public synchronized ObstacleLayer layer(int dn) {
        return layers.computeIfAbsent(clearanceClass(dn), d -> new ObstacleLayer(this, d));
    }

    int clearanceClass(int dn) {
        for (int c : rules.props().getGeometry().getClearanceClasses()) {
            if (dn <= c) {
                return c;
            }
        }
        return dn;
    }

    /** Основной порт ОКС для ДУ (ближайшая точка границы). null - выйти из здания нельзя. */
    public Port port(int oksIdx, int dn) {
        List<Port> list = ports(oksIdx, dn);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Порты ОКС от ближайшей точки границы к дальним (R-OKS-02 + допущение 4): следующий пробуется,
     * если из предыдущего нет маршрута (например, он ведёт во двор, закрытый отступами соседей).
     */
    public synchronized List<Port> ports(int oksIdx, int dn) {
        String key = oksIdx + ":" + dn;
        List<Port> cached = ports.get(key);
        if (cached == null) {
            cached = computePorts(oksIdx, layer(dn));
            ports.put(key, cached);
        }
        return cached;
    }

    private static final int MAX_PORTS = 8;
    private static final double PORT_SEPARATION = 8.0;

    private List<Port> computePorts(int k, ObstacleLayer layer) {
        List<Port> out = new ArrayList<>();
        Coordinate p = scenario.oks.get(k).geom.getCoordinate();
        int own = ownPolygon[k];
        if (own < 0) {
            if (!layer.isBlocked(p)) {
                out.add(new Port(p, p, -1, false, null));
            }
            return out;
        }
        Geometry poly = scenario.restrictions.get(own).geom;
        HeatProperties.Geometry gp = rules.props().getGeometry();
        double ownDist = rules.offset(rules.restriction(OKS_TYPE), layer.dn) + layer.pipeWidth / 2
                + 2 * gp.getSimplifyToleranceM() + gp.getClearanceMarginM() + 0.1;
        boolean inside = poly.intersects(gf.createPoint(p));

        List<Coordinate> candidates = new ArrayList<>();
        candidates.add(DistanceOp.nearestPoints(poly.getBoundary(), gf.createPoint(p))[0]);
        List<Coordinate> ring = new ArrayList<>();
        Coordinate[] cs = poly.getBoundary().getCoordinates();
        for (int i = 0; i + 1 < cs.length; i++) {
            double len = cs[i].distance(cs[i + 1]);
            for (double t = 0; t < len; t += 1.0) {
                double f = t / len;
                ring.add(new Coordinate(cs[i].x + (cs[i + 1].x - cs[i].x) * f, cs[i].y + (cs[i + 1].y - cs[i].y) * f));
            }
        }
        ring.sort((x, y) -> Double.compare(x.distance(p), y.distance(p)));
        candidates.addAll(ring);

        for (int c = 0; c < candidates.size() && out.size() < MAX_PORTS; c++) {
            Coordinate b = candidates.get(c);
            double[] u;
            double dPB = p.distance(b);
            if (dPB > 0.01) {
                u = inside ? ObstacleLayer.unit(b.x - p.x, b.y - p.y) : ObstacleLayer.unit(p.x - b.x, p.y - b.y);
            } else {
                u = outwardNormal(poly, b);
            }
            Coordinate origin = inside ? b : p;
            double t = ownDist;
            Coordinate e = null;
            while (t < ownDist + 60) {
                Coordinate cand = new Coordinate(origin.x + u[0] * t, origin.y + u[1] * t);
                if (!layer.isBlocked(cand)) {
                    e = cand;
                    break;
                }
                t += 0.5;
            }
            if (e == null || layer.check(p, e, own, null) == null) {
                continue;
            }
            // R-OKS-02 снимает отступ до своего здания, но сквозь сам дом труба идти не может:
            // за стеной отрезок не должен снова войти в свой полигон (П- и Г-образные дома)
            Coordinate outside = inside
                    ? new Coordinate(b.x + u[0] * 0.05, b.y + u[1] * 0.05)
                    : new Coordinate(p.x + u[0] * 0.05, p.y + u[1] * 0.05);
            Geometry beyondWall = poly.intersection(gf.createLineString(new Coordinate[] {outside, e}));
            if (beyondWall.getLength() > 0.05) {
                continue; // за стеной труба снова идёт внутри дома
            }
            // и до стены ввод должен быть одним куском: иначе прямая от точки успевает
            // выйти во двор и снова войти в другое крыло того же дома
            Geometry inlet = poly.intersection(gf.createLineString(new Coordinate[] {p, outside}));
            int inletPieces = 0;
            for (int g = 0; g < inlet.getNumGeometries(); g++) {
                if (inlet.getGeometryN(g).getLength() > 0.05) {
                    inletPieces++;
                }
            }
            if (inletPieces > 1) {
                continue; // ввод разрывается: прямая выходит во двор и снова входит в дом
            }
            boolean distinct = true;
            for (Port q : out) {
                if (q.e.distance(e) < PORT_SEPARATION) {
                    distinct = false;
                    break;
                }
            }
            if (!distinct) {
                continue;
            }
            String note = c == 0 ? null : "выход из здания через ближайшую допустимую точку границы (ближайшая перекрыта)";
            out.add(new Port(p, e, own, true, note));
        }
        return out;
    }

    private double[] outwardNormal(Geometry poly, Coordinate b) {
        Coordinate[] cs = poly.getBoundary().getCoordinates();
        LineSegment best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < cs.length; i++) {
            LineSegment s = new LineSegment(cs[i], cs[i + 1]);
            double d = s.distance(b);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        double[] n = ObstacleLayer.unit(-(best.p1.y - best.p0.y), best.p1.x - best.p0.x);
        Coordinate probe = new Coordinate(b.x + n[0] * 0.05, b.y + n[1] * 0.05);
        if (poly.intersects(gf.createPoint(probe))) {
            n[0] = -n[0];
            n[1] = -n[1];
        }
        return n;
    }
}
