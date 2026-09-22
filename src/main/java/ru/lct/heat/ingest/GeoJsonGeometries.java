package ru.lct.heat.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** GeoJSON geometry → JTS. Z отбрасывается: расчёт идёт по плану (2D). */
public final class GeoJsonGeometries {

    private GeoJsonGeometries() {
    }

    public static Geometry parse(JsonNode node, GeometryFactory gf) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new IllegalArgumentException("геометрия отсутствует");
        }
        String type = node.path("type").asText("");
        JsonNode c = node.get("coordinates");
        switch (type) {
            case "Point":
                return gf.createPoint(coordinate(c));
            case "LineString":
                return lineString(c, gf);
            case "Polygon":
                return polygon(c, gf);
            case "MultiPoint": {
                Point[] points = new Point[array(c).size()];
                for (int i = 0; i < points.length; i++) {
                    points[i] = gf.createPoint(coordinate(c.get(i)));
                }
                return gf.createMultiPoint(points);
            }
            case "MultiLineString": {
                LineString[] lines = new LineString[array(c).size()];
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = lineString(c.get(i), gf);
                }
                return gf.createMultiLineString(lines);
            }
            case "MultiPolygon": {
                Polygon[] polygons = new Polygon[array(c).size()];
                for (int i = 0; i < polygons.length; i++) {
                    polygons[i] = polygon(c.get(i), gf);
                }
                return gf.createMultiPolygon(polygons);
            }
            case "GeometryCollection": {
                JsonNode members = array(node.get("geometries"));
                Geometry[] parts = new Geometry[members.size()];
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parse(members.get(i), gf);
                }
                return gf.createGeometryCollection(parts);
            }
            default:
                throw new IllegalArgumentException("неизвестный тип геометрии: " + type);
        }
    }

    private static LineString lineString(JsonNode c, GeometryFactory gf) {
        Coordinate[] coords = coordinates(c);
        if (coords.length < 2) {
            throw new IllegalArgumentException("LineString содержит меньше 2 точек");
        }
        return gf.createLineString(coords);
    }

    private static Polygon polygon(JsonNode rings, GeometryFactory gf) {
        array(rings);
        if (rings.size() == 0) {
            return gf.createPolygon();
        }
        LinearRing shell = ring(rings.get(0), gf);
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) {
            holes[i - 1] = ring(rings.get(i), gf);
        }
        return gf.createPolygon(shell, holes);
    }

    private static LinearRing ring(JsonNode c, GeometryFactory gf) {
        Coordinate[] coords = coordinates(c);
        if (coords.length < 4 || !coords[0].equals2D(coords[coords.length - 1])) {
            throw new IllegalArgumentException("кольцо полигона не замкнуто или короче 4 точек");
        }
        return gf.createLinearRing(coords);
    }

    private static Coordinate[] coordinates(JsonNode c) {
        array(c);
        Coordinate[] coords = new Coordinate[c.size()];
        for (int i = 0; i < coords.length; i++) {
            coords[i] = coordinate(c.get(i));
        }
        return coords;
    }

    private static Coordinate coordinate(JsonNode c) {
        if (c == null || !c.isArray() || c.size() < 2 || !c.get(0).isNumber() || !c.get(1).isNumber()) {
            throw new IllegalArgumentException("некорректная координата: " + c);
        }
        return new Coordinate(c.get(0).doubleValue(), c.get(1).doubleValue());
    }

    private static JsonNode array(JsonNode c) {
        if (c == null || !c.isArray()) {
            throw new IllegalArgumentException("ожидался массив координат");
        }
        return c;
    }
}
