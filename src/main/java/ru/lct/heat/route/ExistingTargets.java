package ru.lct.heat.route;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.ItemDistance;

import ru.lct.heat.model.Rules;

/**
 * Цели на существующей сети (R-TIE): основание перпендикуляра на ближайших отрезках и их концы.
 * Точка в пределах 10 м от камеры со свободным примыканием заменяется камерой (R-TIE-02).
 */
public final class ExistingTargets implements TargetSet {

    private static final int K_SEGMENTS = 6;
    private static final int K_CHAMBERS = 3;
    private static final ItemDistance SEG_DISTANCE = (a, b) -> {
        Object ia = a.getItem();
        Object ib = b.getItem();
        ExistingNetwork.Seg s = (ExistingNetwork.Seg) (ia instanceof ExistingNetwork.Seg ? ia : ib);
        Coordinate c = (Coordinate) (ia instanceof Coordinate ? ia : ib);
        return s.seg.distance(c);
    };
    private static final ItemDistance POINT_DISTANCE = (a, b) -> {
        Envelope ea = (Envelope) a.getBounds();
        Envelope eb = (Envelope) b.getBounds();
        return ea.distance(eb);
    };

    private final Context ctx;
    private final Rules rules;
    private final int dnNew;
    /** Сколько новых участков уже заканчивается в каждой существующей камере. */
    private final int[] usedChildren;
    /** Запрещённые зоны врезки (для содержательно другого варианта). */
    private final List<double[]> forbidden;
    private final double minNode;

    public ExistingTargets(Context ctx, int dnNew, int[] usedChildren, List<double[]> forbidden) {
        this.ctx = ctx;
        this.rules = ctx.rules;
        this.dnNew = dnNew;
        this.usedChildren = usedChildren;
        this.forbidden = forbidden;
        int minLineDn = Integer.MAX_VALUE;
        for (ExistingNetwork.Seg s : ctx.network.segs) {
            minLineDn = Math.min(minLineDn, s.dn);
        }
        double newChamber = rules.newChamberCost(Math.max(minLineDn == Integer.MAX_VALUE ? dnNew : minLineDn, dnNew));
        this.minNode = rules.costScore(Math.min(newChamber, rules.existingTieInCost()));
    }

    @Override
    public void candidates(Coordinate from, List<Goal> out) {
        if (ctx.network.segs.isEmpty()) {
            return;
        }
        Object[] near = ctx.network.segIndex.nearestNeighbour(new Envelope(from), from, SEG_DISTANCE,
                Math.min(K_SEGMENTS, ctx.network.segs.size()));
        for (Object o : near) {
            ExistingNetwork.Seg s = (ExistingNetwork.Seg) o;
            addLinePoint(s, s.seg.closestPoint(from), out);
            addLinePoint(s, s.seg.p0, out);
            addLinePoint(s, s.seg.p1, out);
        }
        int nc = ctx.scenario.chambers.size();
        if (nc > 0) {
            Object[] chambers = ctx.network.chamberIndex.nearestNeighbour(new Envelope(from), from, POINT_DISTANCE,
                    Math.min(K_CHAMBERS, nc));
            for (Object o : chambers) {
                addChamber((Integer) o, out);
            }
        }
    }

    private void addLinePoint(ExistingNetwork.Seg s, Coordinate x, List<Goal> out) {
        if (isForbidden(x)) {
            return;
        }
        double radius = rules.props().getChambers().getExistingSnapRadiusM();
        Object[] chambers = ctx.network.chamberIndex.query(new Envelope(x.x - radius, x.x + radius, x.y - radius, x.y + radius)).toArray();
        int nearest = -1;
        double nearestD = Double.MAX_VALUE;
        for (Object o : chambers) {
            int c = (Integer) o;
            double d = ctx.scenario.chambers.get(c).geom.getCoordinate().distance(x);
            if (d <= radius && hasCapacity(c) && d < nearestD) {
                nearest = c;
                nearestD = d;
            }
        }
        if (nearest >= 0) {
            // R-TIE-02: в пределах 10 м от камеры со свободным примыканием врезаемся в камеру
            addChamber(nearest, out);
            return;
        }
        double cost = rules.newChamberCost(Math.max(s.dn, dnNew));
        out.add(new Goal(Goal.Kind.NEW_ON_LINE, x, rules.costScore(cost), s.line, new int[] {s.line}));
    }

    private void addChamber(int c, List<Goal> out) {
        Coordinate p = ctx.scenario.chambers.get(c).geom.getCoordinate();
        if (!hasCapacity(c) || isForbidden(p)) {
            return;
        }
        out.add(new Goal(Goal.Kind.EXISTING_CHAMBER, p, rules.costScore(rules.existingTieInCost()), c,
                ctx.network.chamberLines[c]));
    }

    private boolean hasCapacity(int c) {
        return ctx.network.occupancy[c] + usedChildren[c] + 1 <= rules.maxConnections();
    }

    private boolean isForbidden(Coordinate x) {
        for (double[] f : forbidden) {
            if (Math.hypot(x.x - f[0], x.y - f[1]) < f[2]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double minDistance(Coordinate from) {
        if (ctx.network.segs.isEmpty()) {
            return 0;
        }
        Object nearest = ctx.network.segIndex.nearestNeighbour(new Envelope(from), from, SEG_DISTANCE);
        return ((ExistingNetwork.Seg) nearest).seg.distance(from);
    }

    @Override
    public double minNodeScore() {
        return minNode;
    }

    /** Совмещение нескольких наборов целей. */
    public static TargetSet union(TargetSet a, TargetSet b) {
        return new TargetSet() {
            @Override
            public void candidates(Coordinate from, List<Goal> out) {
                a.candidates(from, out);
                b.candidates(from, out);
            }

            @Override
            public double minDistance(Coordinate from) {
                return Math.min(a.minDistance(from), b.minDistance(from));
            }

            @Override
            public double minNodeScore() {
                return Math.min(a.minNodeScore(), b.minNodeScore());
            }
        };
    }
}
