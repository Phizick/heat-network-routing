package ru.lct.heat;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.geo.Projection;
import ru.lct.heat.model.Scenario;

/**
 * Демо-набор для показа спецпереходов (R-IN-03 разрешает дополнять копию входа своими restriction):
 * конкурсный датасет + дороги и газопровод, проложенные по свободным от зданий коридорам.
 * Запуск: mvn test -Dtest=DemoDatasetGenerator -Ddemo=true
 */
@EnabledIfSystemProperty(named = "demo", matches = "true")
class DemoDatasetGenerator {

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 32637);

    private static final class Run {
        final LineString line;
        final int crossings;

        Run(LineString line, int crossings) {
            this.line = line;
            this.crossings = crossings;
        }
    }

    @Test
    void generate() throws Exception {
        HeatProperties props = TestSupport.props();
        Scenario s = TestSupport.scenario("/lct-dataset.geojson", props);
        List<Geometry> buildings = new ArrayList<>();
        for (Scenario.RestrictionFeature r : s.restrictions) {
            buildings.add(r.geom);
        }
        Geometry blocked = UnaryUnionOp.union(buildings);
        List<Geometry> net = new ArrayList<>();
        s.network.forEach(l -> net.add(l.geom));
        Geometry network = gf.buildGeometry(net);

        // линии ОКС → ближайшая точка сети: дорога должна их пересекать, чтобы появились спецпереходы
        List<LineString> demand = new ArrayList<>();
        for (Scenario.Oks o : s.oks) {
            Coordinate[] nearest = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(network, o.geom);
            demand.add(gf.createLineString(new Coordinate[] {o.geom.getCoordinate(), nearest[0]}));
        }

        Envelope env = new Envelope();
        s.oks.forEach(o -> env.expandToInclude(o.geom.getCoordinate()));
        net.forEach(g -> env.expandToInclude(g.getEnvelopeInternal()));
        double angle = dominantAngle(buildings);

        List<Run> runs = new ArrayList<>();
        for (double a : new double[] {angle, angle + Math.PI / 2}) {
            runs.addAll(freeRuns(env, a, blocked.buffer(8), demand, 150));
        }
        runs.sort(Comparator.comparingInt((Run r) -> -r.crossings).thenComparingDouble(r -> -r.line.getLength()));
        List<Map<String, Object>> added = new ArrayList<>();
        List<Geometry> used = new ArrayList<>();
        int roads = 0;
        for (Run r : runs) {
            if (roads >= 2) {
                break;
            }
            if (used.stream().anyMatch(u -> u.distance(r.line) < 40)) {
                continue;
            }
            Geometry road = r.line.buffer(6, 1, BufferParameters.CAP_FLAT);
            added.add(feature("demo_road_" + (++roads), "road", road));
            used.add(r.line);
        }
        // газопровод: ещё один свободный коридор, пересекающий подключения, вдали от дорог
        List<Run> gasRuns = new ArrayList<>();
        for (double a : new double[] {angle, angle + Math.PI / 2}) {
            gasRuns.addAll(freeRuns(env, a, blocked.buffer(4), demand, 120));
        }
        gasRuns.sort(Comparator.comparingInt((Run r) -> -r.crossings).thenComparingDouble(r -> -r.line.getLength()));
        for (Run r : gasRuns) {
            if (used.stream().noneMatch(u -> u.distance(r.line) < 25)) {
                added.add(feature("demo_gas_1", "gas_pipeline", r.line));
                break;
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode fc;
        try (InputStream in = getClass().getResourceAsStream("/lct-dataset.geojson")) {
            fc = (ObjectNode) mapper.readTree(in);
        }
        fc.put("name", "lct_demo_with_roads");
        ArrayNode features = (ArrayNode) fc.get("features");
        for (Map<String, Object> f : added) {
            features.add(mapper.valueToTree(f));
        }
        File out = new File("src/main/resources/static/demo-dataset.geojson");
        mapper.writeValue(out, fc);
        System.out.println("ДЕМО: добавлено " + added.size() + " ограничений → " + out);
    }

    /** Самые длинные свободные отрезки прямых, параллельных направлению angle, с шагом 4 м. */
    private List<Run> freeRuns(Envelope env, double angle, Geometry blocked, List<LineString> demand, double minLength) {
        List<Run> out = new ArrayList<>();
        double cx = env.centre().x;
        double cy = env.centre().y;
        double half = Math.hypot(env.getWidth(), env.getHeight()) / 2 + 50;
        double dx = Math.cos(angle);
        double dy = Math.sin(angle);
        for (double off = -half; off <= half; off += 4) {
            double ox = cx - dy * off;
            double oy = cy + dx * off;
            LineString line = gf.createLineString(new Coordinate[] {
                    new Coordinate(ox - dx * half, oy - dy * half), new Coordinate(ox + dx * half, oy + dy * half)});
            Geometry free = line.difference(blocked);
            for (int i = 0; i < free.getNumGeometries(); i++) {
                LineString piece = (LineString) free.getGeometryN(i);
                if (piece.getLength() < minLength || !env.intersects(piece.getEnvelopeInternal())) {
                    continue;
                }
                int crossings = 0;
                for (LineString d : demand) {
                    if (d.intersects(piece)) {
                        crossings++;
                    }
                }
                if (crossings > 0) {
                    out.add(new Run(piece, crossings));
                }
            }
        }
        return out;
    }

    /** Преобладающее направление стен зданий (по сумме длин рёбер, по модулю 90°). */
    private static double dominantAngle(List<Geometry> buildings) {
        double[] hist = new double[90];
        for (Geometry b : buildings) {
            Coordinate[] cs = b.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                double a = Math.toDegrees(Math.atan2(cs[i + 1].y - cs[i].y, cs[i + 1].x - cs[i].x));
                int bin = (int) Math.floor(((a % 90) + 90) % 90);
                hist[bin] += cs[i].distance(cs[i + 1]);
            }
        }
        int best = 0;
        for (int i = 1; i < 90; i++) {
            if (hist[i] > hist[best]) {
                best = i;
            }
        }
        return Math.toRadians(best + 0.5);
    }

    private Map<String, Object> feature(String id, String type, Geometry calc) {
        Geometry wgs = new Projection("EPSG:32637").toWgs84(calc);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", id);
        props.put("object_type", "restriction");
        props.put("restriction_type", type);
        props.put("name", "демо: добавлено для показа спецпереходов");
        Map<String, Object> geom = new LinkedHashMap<>();
        geom.put("type", wgs.getGeometryType());
        if (wgs instanceof LineString) {
            geom.put("coordinates", coords(wgs.getCoordinates()));
        } else {
            List<Object> rings = new ArrayList<>();
            org.locationtech.jts.geom.Polygon p = (org.locationtech.jts.geom.Polygon) wgs;
            rings.add(coords(p.getExteriorRing().getCoordinates()));
            geom.put("coordinates", rings);
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", props);
        f.put("geometry", geom);
        return f;
    }

    private static List<double[]> coords(Coordinate[] cs) {
        List<double[]> out = new ArrayList<>();
        for (Coordinate c : cs) {
            out.add(new double[] {c.x, c.y});
        }
        return out;
    }
}
