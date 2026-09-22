package ru.lct.heat.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;

import ru.lct.heat.ingest.FeatureSink;
import ru.lct.heat.ingest.IngestProcessor;
import ru.lct.heat.ingest.IngestedFeature;
import ru.lct.heat.model.Scenario.SrcId;

/** Собирает Scenario из принятых объектов: и из PostGIS, и напрямую из разбора файла (тесты). */
public final class ScenarioAssembler implements FeatureSink {

    private final ObjectMapper mapper;
    private final Scenario scenario = new Scenario();

    public ScenarioAssembler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Scenario scenario() {
        return scenario;
    }

    @Override
    public void accept(IngestedFeature f) {
        SrcId id = new SrcId(f.srcId, f.srcIdIsNumber);
        Geometry g = f.geometry;
        switch (f.objectType) {
            case IngestProcessor.SOURCE:
                scenario.sources.add((Point) g);
                break;
            case IngestProcessor.HEAT_NETWORK: {
                int dn = (int) Math.round(props(f).path("diameter").asDouble(0));
                if (g instanceof LineString) {
                    scenario.network.add(new Scenario.NetworkLine(id, dn, (LineString) g));
                } else if (g instanceof MultiLineString) {
                    for (int i = 0; i < g.getNumGeometries(); i++) {
                        scenario.network.add(new Scenario.NetworkLine(id, dn, (LineString) g.getGeometryN(i)));
                    }
                }
                break;
            }
            case IngestProcessor.HEAT_CHAMBER:
                scenario.chambers.add(new Scenario.Chamber(id, (Point) g));
                break;
            case IngestProcessor.OKS_POINT:
                scenario.oks.add(new Scenario.Oks(id, props(f).path("flow_tph").asDouble(), (Point) g));
                break;
            case IngestProcessor.RESTRICTION:
                scenario.restrictions.add(new Scenario.RestrictionFeature(id, f.restrictionType, g));
                break;
            default:
                // неизвестные object_type в расчёте не участвуют
        }
    }

    @Override
    public void flush() {
    }

    private JsonNode props(IngestedFeature f) {
        try {
            return mapper.readTree(f.propertiesJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
