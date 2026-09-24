package ru.lct.heat.route;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.index.strtree.STRtree;

/** Отрезки уже построенной новой сети: пересекать их нельзя (R-TOP-06), можно только прийти в них. */
public final class Blockers {

    public static final Blockers NONE = new Blockers(List.of());

    private final STRtree index = new STRtree();
    private final boolean empty;

    public Blockers(List<LineSegment> segments) {
        for (LineSegment s : segments) {
            index.insert(new Envelope(s.p0, s.p1), s);
        }
        empty = segments.isEmpty();
        index.build();
    }

    /**
     * true, если a→b пересекает или касается новой сети (R-TOP-06). Касание допустимо только
     * в концах, где трасса законно примыкает к сети: allowA - в начале (узел-ребёнок), allowB - в цели.
     */
    public boolean blocks(Coordinate a, Coordinate b, boolean allowA, boolean allowB) {
        if (empty) {
            return false;
        }
        LineSegment seg = new LineSegment(a, b);
        for (Object o : index.query(new Envelope(a, b))) {
            LineSegment s = (LineSegment) o;
            if (seg.distance(s) > 1e-7) {
                continue;
            }
            if (collinearOverlap(seg, s)) {
                return true;
            }
            Coordinate x = seg.intersection(s);
            if (x == null) {
                return true;
            }
            boolean atA = x.distance(a) <= 1e-6;
            boolean atB = x.distance(b) <= 1e-6;
            if ((atA && allowA) || (atB && allowB)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Отрезок идёт вплотную и почти вдоль уже построенной новой трубы (монтажный режим). */
    public boolean parallelToExisting(Coordinate a, Coordinate b, double distance, double angleDeg) {
        if (empty) {
            return false;
        }
        LineSegment seg = new LineSegment(a, b);
        Envelope env = new Envelope(a, b);
        env.expandBy(distance);
        for (Object o : index.query(env)) {
            LineSegment s = (LineSegment) o;
            if (seg.distance(s) > distance) {
                continue;
            }
            double angle = Math.abs(Math.toDegrees(Math.atan2(
                    (b.x - a.x) * (s.p1.y - s.p0.y) - (b.y - a.y) * (s.p1.x - s.p0.x),
                    (b.x - a.x) * (s.p1.x - s.p0.x) + (b.y - a.y) * (s.p1.y - s.p0.y))));
            double acute = Math.min(angle, 180 - angle);
            if (acute <= angleDeg && seg.getLength() > 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static boolean collinearOverlap(LineSegment a, LineSegment b) {
        if (Math.abs(a.orientationIndex(b.p0)) + Math.abs(a.orientationIndex(b.p1)) != 0) {
            return false;
        }
        double t0 = a.projectionFactor(b.p0);
        double t1 = a.projectionFactor(b.p1);
        double lo = Math.max(0, Math.min(t0, t1));
        double hi = Math.min(1, Math.max(t0, t1));
        return (hi - lo) * a.getLength() > 1e-6;
    }
}
