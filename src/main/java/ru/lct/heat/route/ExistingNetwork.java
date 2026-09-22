package ru.lct.heat.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;
import org.locationtech.jts.index.strtree.STRtree;

import ru.lct.heat.model.Scenario;

/**
 * Существующая сеть (R-IN-05): отрезки линий с индексом, камеры и занятые примыкания.
 * Топология восстанавливается геометрически, с допуском склейки.
 */
public final class ExistingNetwork {

    public static final class Seg {
        public final LineSegment seg;
        public final int line;
        public final int dn;

        Seg(LineSegment seg, int line, int dn) {
            this.seg = seg;
            this.line = line;
            this.dn = dn;
        }
    }

    public final Scenario scenario;
    public final List<Seg> segs = new ArrayList<>();
    public final STRtree segIndex = new STRtree();
    public final STRtree chamberIndex = new STRtree();
    /** Занятые примыкания существующих камер: конец линии = 1, линия насквозь = 2 (R-TOP-05). */
    public final int[] occupancy;
    /** Линии, примыкающие к камере (для исключения их коридора при подходе к камере). */
    public final int[][] chamberLines;
    public final List<String> diagnostics = new ArrayList<>();
    public int components;

    public ExistingNetwork(Scenario scenario, double tol) {
        this.scenario = scenario;
        for (int i = 0; i < scenario.network.size(); i++) {
            Scenario.NetworkLine line = scenario.network.get(i);
            Coordinate[] cs = line.geom.getCoordinates();
            for (int k = 0; k + 1 < cs.length; k++) {
                if (cs[k].equals2D(cs[k + 1])) {
                    continue;
                }
                Seg s = new Seg(new LineSegment(cs[k], cs[k + 1]), i, line.dn);
                segs.add(s);
                segIndex.insert(new Envelope(cs[k], cs[k + 1]), s);
            }
        }
        segIndex.build();

        int nc = scenario.chambers.size();
        occupancy = new int[nc];
        chamberLines = new int[nc][];
        for (int c = 0; c < nc; c++) {
            Coordinate p = scenario.chambers.get(c).geom.getCoordinate();
            chamberIndex.insert(new Envelope(p), c);
            List<Integer> lines = new ArrayList<>();
            for (int i = 0; i < scenario.network.size(); i++) {
                Scenario.NetworkLine line = scenario.network.get(i);
                if (line.geom.getCoordinate().distance(p) <= tol
                        || line.geom.getCoordinateN(line.geom.getNumPoints() - 1).distance(p) <= tol) {
                    occupancy[c] += 1;
                    lines.add(i);
                } else if (line.geom.distance(scenario.chambers.get(c).geom) <= tol) {
                    occupancy[c] += 2;
                    lines.add(i);
                }
            }
            chamberLines[c] = lines.stream().mapToInt(Integer::intValue).toArray();
            if (lines.isEmpty()) {
                diagnostics.add("камера " + scenario.chambers.get(c).id + " не лежит ни на одной линии сети (допуск " + tol + " м)");
            }
        }
        chamberIndex.build();
        components = countComponents(tol);
        if (components > 1) {
            diagnostics.add("существующая сеть состоит из " + components + " несвязных частей");
        }
    }

    /** Линии сети в точке врезки: какие, сколько примыканий занято, наибольший ДУ (стык линий - R-TOP-05, R-CH-01). */
    public static final class At {
        public final int[] lines;
        public final int occupancy;
        public final int maxDn;

        At(int[] lines, int occupancy, int maxDn) {
            this.lines = lines;
            this.occupancy = occupancy;
            this.maxDn = maxDn;
        }
    }

    public At at(Coordinate x, double tol) {
        java.util.Set<Integer> lines = new java.util.LinkedHashSet<>();
        for (Object o : segIndex.query(new Envelope(x.x - tol, x.x + tol, x.y - tol, x.y + tol))) {
            Seg s = (Seg) o;
            if (s.seg.distance(x) <= tol) {
                lines.add(s.line);
            }
        }
        int occupancy = 0;
        int maxDn = 0;
        for (int i : lines) {
            Scenario.NetworkLine line = scenario.network.get(i);
            boolean end = line.geom.getCoordinate().distance(x) <= tol
                    || line.geom.getCoordinateN(line.geom.getNumPoints() - 1).distance(x) <= tol;
            occupancy += end ? 1 : 2;
            maxDn = Math.max(maxDn, line.dn);
        }
        return new At(lines.stream().mapToInt(Integer::intValue).toArray(), occupancy, maxDn);
    }

    /** Связность по концам линий и камерам на линиях, для диагностики. */
    private int countComponents(double tol) {
        int n = scenario.network.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        Map<String, Integer> endpointOwner = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Scenario.NetworkLine line = scenario.network.get(i);
            for (Coordinate c : new Coordinate[] {line.geom.getCoordinate(),
                    line.geom.getCoordinateN(line.geom.getNumPoints() - 1)}) {
                String key = Math.round(c.x / Math.max(tol, 1e-3)) + ":" + Math.round(c.y / Math.max(tol, 1e-3));
                Integer other = endpointOwner.putIfAbsent(key, i);
                if (other != null) {
                    union(parent, i, other);
                }
                // конец линии на середине другой линии - тоже соединение (Т-образная врезка)
                for (Object o : segIndex.query(new Envelope(c.x - tol, c.x + tol, c.y - tol, c.y + tol))) {
                    Seg s = (Seg) o;
                    if (s.line != i && s.seg.distance(c) <= tol) {
                        union(parent, i, s.line);
                    }
                }
            }
        }
        for (int[] lines : chamberLines) {
            for (int k = 1; k < lines.length; k++) {
                union(parent, lines[0], lines[k]);
            }
        }
        int roots = 0;
        for (int i = 0; i < n; i++) {
            if (find(parent, i) == i) {
                roots++;
            }
        }
        return roots;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }
}
