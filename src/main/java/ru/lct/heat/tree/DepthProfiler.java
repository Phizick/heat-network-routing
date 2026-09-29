package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.config.HeatProperties.Restriction;
import ru.lct.heat.config.HeatProperties.Vertical;
import ru.lct.heat.model.Rules;
import ru.lct.heat.route.Context;
import ru.lct.heat.route.SegInfo;

/**
 * Режим с глубиной (раздел 8 rules.md): продольный профиль глубины верха габарита по каждому ребру
 * плановой трассы. Обычная глубина 3,0 м; у пересечений - вертикальные правила таблицы 2
 * (под дорогой не мельче 1,0 м, газ и кабель выше/ниже с просветом); переходы с уклоном ≤ 0,1;
 * между близкими препятствиями трасса остаётся на изменённой глубине.
 */
public final class DepthProfiler {

    /** Требование по глубине на участке ребра [a, b] (м от начала ребра). */
    private static final class Constraint {
        double a;
        double b;
        List<double[]> ranges;
        String what;
        /** Условная глубина верха и высота габарита чужой коммуникации, нормативный просвет. */
        double otherTop;
        double otherHeight;
        double clearance;
        double minTop;
        boolean vertical;

        Constraint(double a, double b, List<double[]> ranges, String what) {
            this.a = a;
            this.b = b;
            this.ranges = ranges;
            this.what = what;
        }
    }

    /** Пересечение с существующей коммуникацией: чем, где, сверху или снизу, с каким просветом. */
    public static final class Crossing {
        public final String type;
        public final double at;
        public final double depth;
        public final boolean above;
        public final double clearance;
        public final double clearanceRequired;

        Crossing(String type, double at, double depth, boolean above, double clearance, double clearanceRequired) {
            this.type = type;
            this.at = at;
            this.depth = depth;
            this.above = above;
            this.clearance = clearance;
            this.clearanceRequired = clearanceRequired;
        }
    }

    private final Context ctx;
    private final Rules rules;
    private final Evaluator evaluator;
    private final HeatProperties.Depth cfg;

    public DepthProfiler(Context ctx, Evaluator evaluator) {
        this.ctx = ctx;
        this.rules = ctx.rules;
        this.evaluator = evaluator;
        this.cfg = rules.props().getDepth();
    }

    private double maxDepth() {
        return cfg.getMaxDepth() > 0 ? cfg.getMaxDepth() : 100;
    }

    /** Коэффициент стоимости по глубине верха габарита (таблица правила 5 или запасная формула). */
    public double kDepth(double h) {
        return rules.depthFactor(h);
    }

    /** Глубина округляется к сетке шага (правило 4.2) внутри допустимого диапазона. */
    private double snap(double h, double lo, double hi) {
        double step = cfg.getStepM();
        if (step <= 0) {
            return Math.max(lo, Math.min(hi, h));
        }
        double up = Math.ceil((h - 1e-9) / step) * step;
        double down = Math.floor((h + 1e-9) / step) * step;
        double best = Double.NaN;
        for (double c : new double[] {down, up, Math.ceil((lo - 1e-9) / step) * step, Math.floor((hi + 1e-9) / step) * step}) {
            if (c < lo - 1e-9 || c > hi + 1e-9) {
                continue;
            }
            if (Double.isNaN(best) || better(c, best, h)) {
                best = c;
            }
        }
        return Double.isNaN(best) ? Math.max(lo, Math.min(hi, h)) : best;
    }

    /** Переоценка варианта с профилем глубины: участки режутся на изломах профиля и на h = 3,0 м. */
    public Eval evaluate(Net net, Eval plan, List<String> problems) {
        Eval ev = new Eval();
        for (Net.Edge e : net.edges.values()) {
            List<Crossing> crossings = new ArrayList<>();
            List<double[]> profile = profile(net, e, problems, crossings);
            if (!crossings.isEmpty()) {
                ev.crossings.put(e.id, crossings);
            }
            List<Eval.Part> out = new ArrayList<>();
            double offset = 0;
            for (Eval.Part p : plan.parts.getOrDefault(e.id, List.of())) {
                double s0 = offset;
                double s1 = offset + p.length;
                List<Double> cuts = new ArrayList<>();
                cuts.add(s0);
                for (int i = 0; i + 1 < profile.size(); i++) {
                    double[] u = profile.get(i);
                    double[] v = profile.get(i + 1);
                    if (u[0] > s0 + 1e-6 && u[0] < s1 - 1e-6) {
                        cuts.add(u[0]);
                    }
                    // пересечение h = порог K_гл: там меняется коэффициент, нужен технический узел (раздел 8)
                    double thr = cfg.getKThreshold();
                    if ((u[1] - thr) * (v[1] - thr) < 0) {
                        double x = u[0] + (thr - u[1]) / (v[1] - u[1]) * (v[0] - u[0]);
                        if (x > s0 + 1e-6 && x < s1 - 1e-6) {
                            cuts.add(x);
                        }
                    }
                }
                cuts.add(s1);
                cuts.sort(Double::compare);
                for (int i = 0; i + 1 < cuts.size(); i++) {
                    double a = cuts.get(i);
                    double b = cuts.get(i + 1);
                    if (b - a < 1e-6) {
                        continue;
                    }
                    double ha = depthAt(profile, a);
                    double hb = depthAt(profile, b);
                    double k = (kDepth(ha) + kDepth(hb)) / 2;
                    List<Coordinate> coords = slice(p.coords, a - s0, b - s0);
                    double len = b - a;
                    double cost = len * rules.costPerM(e.dn) * p.k * k;
                    Eval.Part part = new Eval.Part(coords, p.special, p.k, p.types, len, cost);
                    part.depthStart = round2(ha);
                    part.depthEnd = round2(hb);
                    part.kDepth = k;
                    out.add(part);
                    ev.pipeCost += cost;
                    ev.length += len;
                }
                offset = s1;
            }
            ev.parts.put(e.id, out);
        }
        ev.problems.addAll(problems);
        ev.chamberCost = plan.chamberCost;
        ev.chamberCosts.putAll(plan.chamberCosts);
        ev.chamberDn.putAll(plan.chamberDn);
        ev.existingTieInCount = plan.existingTieInCount;
        ev.existingTieInCost = plan.existingTieInCost;
        ev.unconnectedPenalty = plan.unconnectedPenalty;
        ev.invalidSegments = plan.invalidSegments;
        ev.constructionCost = ev.pipeCost + ev.chamberCost + ev.existingTieInCost;
        ev.calculatedCost = ev.constructionCost + ev.unconnectedPenalty;
        ev.score = rules.score(ev.calculatedCost, ev.length);
        return ev;
    }

    /** Профиль ребра: точки (расстояние от начала ребра, глубина верха габарита). */
    List<double[]> profile(Net net, Net.Edge e, List<String> problems) {
        return profile(net, e, problems, new ArrayList<>());
    }

    List<double[]> profile(Net net, Net.Edge e, List<String> problems, List<Crossing> crossings) {
        double length = e.length();
        double normal = cfg.getNormalDepth();
        double slope = cfg.getMaxSlope();
        List<Constraint> cons = constraints(net, e);
        List<double[]> pts = new ArrayList<>();
        if (cons.isEmpty()) {
            pts.add(new double[] {0, normal});
            pts.add(new double[] {length, normal});
            return pts;
        }
        double curS = 0;
        double t0 = preferred(cons.get(0).ranges);
        double curH = cons.get(0).a < Math.abs(t0 - normal) / slope ? t0 : normal;
        pts.add(new double[] {0, curH});
        for (int k = 0; k < cons.size(); k++) {
            Constraint c = cons.get(k);
            double reach = slope * Math.max(0, c.a - curS);
            double pref = preferred(c.ranges);
            double t = reachable(c.ranges, curH, reach, pref);
            if (Double.isNaN(t)) {
                problems.add(String.format("ребро %d: у %s не выполнить вертикальное правило с уклоном ≤ %.2f - требует ручной проработки",
                        e.id, c.what, slope));
                t = pref;
            }
            if (Math.abs(t - curH) > 1e-6) {
                double rampStart = Math.max(curS, c.a - Math.abs(t - curH) / slope);
                pts.add(new double[] {rampStart, curH});
            }
            if (c.vertical) {
                crossings.add(crossing(c, t, rules.height(e.dn, true)));
            }
            pts.add(new double[] {c.a, t});
            pts.add(new double[] {c.b, t});
            curS = c.b;
            curH = t;
            // возврат к обычной глубине, если до следующего препятствия хватает места на два перехода
            double back = Math.abs(normal - curH) / slope;
            if (back < 1e-6) {
                continue;
            }
            if (k + 1 < cons.size()) {
                Constraint next = cons.get(k + 1);
                double r2 = Math.abs(preferred(next.ranges) - normal) / slope;
                if (next.a - curS >= back + r2) {
                    curS += back;
                    curH = normal;
                    pts.add(new double[] {curS, curH});
                }
            } else if (length - curS >= back) {
                curS += back;
                curH = normal;
                pts.add(new double[] {curS, curH});
            }
        }
        pts.add(new double[] {length, curH});
        pts.sort((x, y) -> Double.compare(x[0], y[0]));
        // проверка: глубина не мельче минимальной, уклоны в пределах
        for (int i = 0; i + 1 < pts.size(); i++) {
            double ds = pts.get(i + 1)[0] - pts.get(i)[0];
            double dh = Math.abs(pts.get(i + 1)[1] - pts.get(i)[1]);
            if (ds > 1e-6 && dh / ds > slope + 1e-6) {
                problems.add(String.format("ребро %d: уклон %.3f больше %.2f", e.id, dh / ds, slope));
            }
        }
        return pts;
    }

    /** Описание пересечения для выдачи: сверху или снизу и фактический вертикальный просвет. */
    private Crossing crossing(Constraint c, double h, double hg) {
        double at = (c.a + c.b) / 2;
        if (c.otherTop <= 0) {
            // правило «не мельче»: проход под объектом без своей отметки (дорога, трамвай)
            return new Crossing(c.what, at, h, false, h, c.minTop);
        }
        boolean above = h + hg <= c.otherTop + 1e-6;
        double gap = above ? c.otherTop - (h + hg) : h - (c.otherTop + c.otherHeight);
        return new Crossing(c.what, at, h, above, round2(gap), c.clearance);
    }

    private List<Constraint> constraints(Net net, Net.Edge e) {
        List<Constraint> out = new ArrayList<>();
        double base = 0;
        for (int i = 0; i + 1 < e.path.size(); i++) {
            SegInfo info = evaluator.check(net, e, i);
            double segLen = e.path.get(i).distance(e.path.get(i + 1));
            if (info != null) {
                for (SegInfo.Special sp : info.specials) {
                    Restriction r;
                    double ownHeight;
                    if (sp.line >= 0) {
                        r = rules.props().getExistingNetworkCrossing();
                        ownHeight = rules.pipe(ctx.scenario.network.get(sp.line).dn).getHeight();
                    } else {
                        r = rules.restriction(sp.type);
                        ownHeight = r.getOwnHeight();
                    }
                    double hg = rules.height(e.dn, true);
                    double minH = rules.minDepth(e.dn, true);
                    List<double[]> ranges = ranges(r, hg, ownHeight, minH);
                    Constraint c = new Constraint(base + Math.max(0, sp.from), base + Math.min(segLen, sp.to), ranges, sp.type);
                    c.otherTop = r.getDepthTop();
                    c.otherHeight = ownHeight;
                    c.clearance = r.getClearance();
                    c.minTop = r.getMinTopDepth();
                    c.vertical = r.getVertical() != Vertical.NONE;
                    out.add(c);
                }
            }
            base += segLen;
        }
        out.sort((x, y) -> Double.compare(x.a, y.a));
        // наложения: одно требование на объединённом участке с пересечением допустимых глубин
        List<Constraint> merged = new ArrayList<>();
        for (Constraint c : out) {
            Constraint last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && c.a <= last.b + 1e-6) {
                last.b = Math.max(last.b, c.b);
                last.ranges = intersect(last.ranges, c.ranges);
                last.what = last.what + "+" + c.what;
                // для отчёта берём коммуникацию, которая лежит выше: просвет считается по ней
                if (c.vertical && (!last.vertical || c.otherTop < last.otherTop)) {
                    last.otherTop = c.otherTop;
                    last.otherHeight = c.otherHeight;
                    last.clearance = c.clearance;
                    last.vertical = true;
                }
            } else {
                merged.add(c);
            }
        }
        return merged;
    }

    /** Допустимые глубины верха габарита при пересечении по вертикальному правилу. */
    private List<double[]> ranges(Restriction r, double hg, double ownHeight, double min) {
        List<double[]> out = new ArrayList<>();
        double max = maxDepth();
        Vertical v = r.getVertical();
        if (v == Vertical.BELOW) {
            out.add(new double[] {Math.max(min, r.getMinTopDepth()), max});
        } else if (v == Vertical.ABOVE_OR_BELOW) {
            // выше: низ новой ≥ верх чужой + просвет → h + h_габ + просвет ≤ h_огр
            double above = r.getDepthTop() - r.getClearance() - hg;
            if (above >= min) {
                out.add(new double[] {min, above});
            }
            // ниже: h ≥ h_огр + h_габ_огр + просвет
            double below = r.getDepthTop() + ownHeight + r.getClearance();
            if (below <= max) {
                out.add(new double[] {Math.max(min, below), max});
            }
        } else {
            out.add(new double[] {min, max});
        }
        return out;
    }

    private static List<double[]> intersect(List<double[]> a, List<double[]> b) {
        List<double[]> out = new ArrayList<>();
        for (double[] x : a) {
            for (double[] y : b) {
                double lo = Math.max(x[0], y[0]);
                double hi = Math.min(x[1], y[1]);
                if (lo <= hi) {
                    out.add(new double[] {lo, hi});
                }
            }
        }
        return out;
    }

    /** Лучшая глубина в допустимом наборе: минимальный K_гл, затем ближе к обычной. */
    private double preferred(List<double[]> ranges) {
        double normal = cfg.getNormalDepth();
        double best = Double.NaN;
        for (double[] r : ranges) {
            double c = snap(Math.max(r[0], Math.min(r[1], normal)), r[0], r[1]);
            if (Double.isNaN(best) || better(c, best, normal)) {
                best = c;
            }
        }
        return Double.isNaN(best) ? normal : best;
    }

    /** Лучшая глубина, достижимая от текущей с учётом уклона. NaN - недостижимо. */
    private double reachable(List<double[]> ranges, double cur, double reach, double pref) {
        double best = Double.NaN;
        for (double[] r : ranges) {
            double lo = Math.max(r[0], cur - reach);
            double hi = Math.min(r[1], cur + reach);
            if (lo > hi + 1e-9) {
                continue;
            }
            double c = snap(Math.max(lo, Math.min(hi, pref)), lo, hi);
            if (Double.isNaN(best) || better(c, best, pref)) {
                best = c;
            }
        }
        return best;
    }

    private boolean better(double c, double best, double target) {
        double kc = kDepth(c);
        double kb = kDepth(best);
        if (Math.abs(kc - kb) > 1e-9) {
            return kc < kb;
        }
        return Math.abs(c - target) < Math.abs(best - target);
    }

    static double depthAt(List<double[]> pts, double s) {
        if (s <= pts.get(0)[0]) {
            return pts.get(0)[1];
        }
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] u = pts.get(i);
            double[] v = pts.get(i + 1);
            if (s <= v[0] + 1e-9) {
                double ds = v[0] - u[0];
                return ds < 1e-9 ? v[1] : u[1] + (v[1] - u[1]) * (s - u[0]) / ds;
            }
        }
        return pts.get(pts.size() - 1)[1];
    }

    /** Точка на ломаной в s метрах от её начала. */
    public static Coordinate pointAt(List<Coordinate> line, double s) {
        double acc = 0;
        for (int i = 0; i + 1 < line.size(); i++) {
            Coordinate a = line.get(i);
            Coordinate b = line.get(i + 1);
            double len = a.distance(b);
            if (acc + len >= s - 1e-9 && len > 1e-12) {
                double f = Math.max(0, Math.min(1, (s - acc) / len));
                return new Coordinate(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f);
            }
            acc += len;
        }
        return new Coordinate(line.get(line.size() - 1));
    }

    /** Часть ломаной между расстояниями from и to от её начала. */
    static List<Coordinate> slice(List<Coordinate> line, double from, double to) {
        List<Coordinate> out = new ArrayList<>();
        double acc = 0;
        for (int i = 0; i + 1 < line.size(); i++) {
            Coordinate a = line.get(i);
            Coordinate b = line.get(i + 1);
            double len = a.distance(b);
            double s0 = acc;
            double s1 = acc + len;
            if (s1 >= from - 1e-9 && s0 <= to + 1e-9 && len > 1e-12) {
                double f0 = Math.max(0, (from - s0) / len);
                double f1 = Math.min(1, (to - s0) / len);
                Coordinate p0 = new Coordinate(a.x + (b.x - a.x) * f0, a.y + (b.y - a.y) * f0);
                Coordinate p1 = new Coordinate(a.x + (b.x - a.x) * f1, a.y + (b.y - a.y) * f1);
                if (out.isEmpty()) {
                    out.add(p0);
                }
                if (!out.get(out.size() - 1).equals2D(p1)) {
                    out.add(p1);
                }
            }
            acc = s1;
        }
        if (out.size() == 1) {
            out.add(new Coordinate(out.get(0)));
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
