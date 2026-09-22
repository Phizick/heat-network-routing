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

    /** true, если a→b пересекает новую сеть где-то, кроме своих концов. */
    public boolean blocks(Coordinate a, Coordinate b) {
        if (empty) {
            return false;
        }
        LineSegment seg = new LineSegment(a, b);
        for (Object o : index.query(new Envelope(a, b))) {
            LineSegment s = (LineSegment) o;
            if (seg.distance(s) > 1e-7) {
                continue;
            }
            Coordinate x = seg.intersection(s);
            if (x == null) {
                // параллельное наложение
                return true;
            }
            if (x.distance(a) > 1e-6 && x.distance(b) > 1e-6) {
                return true;
            }
            // касание в конце: запрещаем наложение вдоль (коллинеарные отрезки)
            if (seg.distance(s) < 1e-7 && collinearOverlap(seg, s)) {
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
