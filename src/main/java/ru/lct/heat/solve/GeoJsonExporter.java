package ru.lct.heat.solve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import ru.lct.heat.geo.Projection;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.route.Context;
import ru.lct.heat.tree.Eval;
import ru.lct.heat.tree.Net;
import ru.lct.heat.tree.Solver;

/**
 * Выход по разделу 7 rules.md: один FeatureCollection в WGS 84, варианты различаются variant_id.
 * Участки режутся на границах спецпроходов, там ставятся technical_node (R-GEO-05, R-RST-05).
 */
public final class GeoJsonExporter {

    private final Context ctx;
    private final Projection projection;

    public GeoJsonExporter(Context ctx, String calcCrs) {
        this.ctx = ctx;
        this.projection = new Projection(calcCrs);
    }

    public Map<String, Object> export(List<Solver.Candidate> variants, Map<Integer, List<String>> validation) {
        List<Object> features = new ArrayList<>();
        for (int v = 0; v < variants.size(); v++) {
            Solver.Candidate c = variants.get(v);
            int rank = v + 1;
            exportVariant(c, rank, validation.getOrDefault(v, List.of()), features);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("name", "heat_routing_2d");
        fc.put("features", features);
        return fc;
    }

    private void exportVariant(Solver.Candidate c, int rank, List<String> validation, List<Object> out) {
        String vid = "v" + rank;
        Net net = c.net;
        Eval ev = c.eval;
        Map<Integer, Object> nodeIds = new HashMap<>();
        int chamberNo = 0;
        for (Net.Node n : net.nodes.values()) {
            switch (n.kind) {
                case OKS:
                    nodeIds.put(n.id, ctx.scenario.oks.get(n.oks).id.json());
                    break;
                case EXISTING_CHAMBER:
                    nodeIds.put(n.id, ctx.scenario.chambers.get(n.chamber).id.json());
                    break;
                default:
                    String id = vid + "_ch_" + (++chamberNo);
                    nodeIds.put(n.id, id);
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("id", id);
                    props.put("object_type", "heat_chamber");
                    props.put("variant_id", vid);
                    props.put("diameter", ev.chamberDn.get(n.id));
                    props.put("cost", round(ev.chamberCosts.get(n.id)));
                    props.put("chamber_role", n.root ? "tie_in" : "branch");
                    if (n.line >= 0) {
                        props.put("tie_in_line_id", ctx.scenario.network.get(n.line).id.json());
                    }
                    out.add(feature(point(n.c), props));
            }
        }

        int netNo = 0;
        int techNo = 0;
        for (Net.Edge e : net.edges.values()) {
            List<Eval.Part> parts = ev.parts.getOrDefault(e.id, List.of());
            Object prevNode = nodeIds.get(e.child);
            for (int i = 0; i < parts.size(); i++) {
                Eval.Part p = parts.get(i);
                Object endNode;
                if (i == parts.size() - 1) {
                    endNode = nodeIds.get(e.parent);
                } else {
                    String tid = vid + "_tn_" + (++techNo);
                    Map<String, Object> tprops = new LinkedHashMap<>();
                    tprops.put("id", tid);
                    tprops.put("object_type", "technical_node");
                    tprops.put("variant_id", vid);
                    out.add(feature(point(p.coords.get(p.coords.size() - 1)), tprops));
                    endNode = tid;
                }
                Map<String, Object> props = new LinkedHashMap<>();
                props.put("id", vid + "_net_" + (++netNo));
                props.put("object_type", "heat_network");
                props.put("variant_id", vid);
                props.put("start_node_id", prevNode);
                props.put("end_node_id", endNode);
                props.put("flow_tph", round2(e.flow));
                props.put("diameter", e.dn);
                props.put("length", round2(p.length));
                props.put("laying_method", p.special ? "special" : "base");
                props.put("depth_start", null);
                props.put("depth_end", null);
                props.put("cost", round(p.cost));
                if (p.special) {
                    props.put("special_restriction_types", p.types);
                    props.put("k_spec", p.k);
                }
                out.add(feature(line(p.coords), props));
                prevNode = endNode;
            }
        }

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", vid + "_summary");
        s.put("object_type", "variant_summary");
        s.put("variant_id", vid);
        s.put("rank", rank);
        s.put("construction_cost", round(ev.constructionCost));
        s.put("chamber_construction_cost", round(ev.chamberCost));
        s.put("existing_chamber_tie_in_count", ev.existingTieInCount);
        s.put("existing_chamber_tie_in_cost", round(ev.existingTieInCost));
        s.put("unconnected_penalty", round(ev.unconnectedPenalty));
        s.put("calculated_cost", round(ev.calculatedCost));
        s.put("new_network_length", round2(ev.length));
        s.put("score", Math.round(ev.score * 10000) / 10000.0);
        List<Object> unconnected = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> u : net.unconnected.entrySet()) {
            Scenario.Oks o = ctx.scenario.oks.get(u.getKey());
            unconnected.add(o.id.json());
            reasons.put(o.id.value, u.getValue());
        }
        s.put("unconnected_oks_ids", unconnected);
        // дополнительные свойства (допустимы по ТЗ): объяснимость и самопроверка
        s.put("strategy", c.strategy);
        s.put("unconnected_reasons", reasons);
        s.put("validation_errors", validation);
        s.put("notes", new ArrayList<>(net.notes));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", s);
        f.put("geometry", null);
        out.add(f);
    }

    private Map<String, Object> feature(Map<String, Object> geometry, Map<String, Object> props) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", props);
        f.put("geometry", geometry);
        return f;
    }

    private Map<String, Object> point(Coordinate c) {
        Geometry g = projection.toWgs84(ctx.gf.createPoint(c));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "Point");
        m.put("coordinates", lonLat(g.getCoordinate()));
        return m;
    }

    private Map<String, Object> line(List<Coordinate> coords) {
        Geometry g = projection.toWgs84(ctx.gf.createLineString(coords.toArray(new Coordinate[0])));
        List<Object> cs = new ArrayList<>();
        for (Coordinate c : g.getCoordinates()) {
            cs.add(lonLat(c));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "LineString");
        m.put("coordinates", cs);
        return m;
    }

    private static double[] lonLat(Coordinate c) {
        return new double[] {Math.round(c.x * 1e9) / 1e9, Math.round(c.y * 1e9) / 1e9};
    }

    private static long round(Double v) {
        return v == null ? 0 : Math.round(v);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
