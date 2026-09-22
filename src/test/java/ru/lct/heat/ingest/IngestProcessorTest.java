package ru.lct.heat.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

import ru.lct.heat.geo.Projection;

class IngestProcessorTest {

    private static final Set<String> KNOWN = Set.of("oks", "park", "social_area", "prohibited_site", "water",
            "railway", "road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network");

    private final List<IngestedFeature> stored = new ArrayList<>();
    private final FeatureSink sink = new FeatureSink() {
        @Override
        public void accept(IngestedFeature feature) {
            stored.add(feature);
        }

        @Override
        public void flush() {
        }
    };

    private IngestReport run(InputStream in) throws IOException {
        return new IngestProcessor(new ObjectMapper(), new Projection("EPSG:32637"), KNOWN, 100).process(in, sink);
    }

    private IngestReport run(String json) throws IOException {
        return run(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void competitionDatasetIsFullyAccepted() throws IOException {
        IngestReport report;
        try (InputStream in = getClass().getResourceAsStream("/lct-dataset.geojson")) {
            report = run(in);
        }

        assertThat(report.featuresRejected).isZero();
        assertThat(report.issueCounts.keySet()).noneMatch(code -> code.startsWith("ERROR:"));
        assertThat(report.objectTypeCounts)
                .containsEntry("source", 1L)
                .containsEntry("heat_network", 29L)
                .containsEntry("heat_chamber", 9L)
                .containsEntry("oks_connection_point", 17L)
                .containsEntry("restriction", 88L);
        assertThat(report.restrictionTypeCounts)
                .containsEntry("oks", 85L)
                .containsEntry("water", 2L)
                .containsEntry("railway", 1L);
        assertThat(report.totalFlowTph).isBetween(488.0, 489.5);
        assertThat(stored).hasSize((int) report.featuresStored);
        assertThat(stored).allMatch(f -> f.srcIdIsNumber);
    }

    @Test
    void geometriesAreProjectedToUtm37() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/lct-dataset.geojson")) {
            run(in);
        }
        for (IngestedFeature f : stored) {
            Envelope e = f.geometry.getEnvelopeInternal();
            // Москва в UTM 37N: восток ~ 410 км, север ~ 6 170 км
            assertThat(e.getMinX()).as("easting %s", f.srcId).isBetween(350_000.0, 450_000.0);
            assertThat(e.getMinY()).as("northing %s", f.srcId).isBetween(6_100_000.0, 6_250_000.0);
            assertThat(f.geometry.getSRID()).isEqualTo(32637);
        }
    }

    @Test
    void projectedDistanceMatchesHaversine() throws IOException {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                + point(1, 37.6344054041544, 55.6994810644531) + ","
                + point(2, 37.6363818514204, 55.7001551990147) + "]}";
        run(json);
        Geometry a = stored.get(0).geometry;
        Geometry b = stored.get(1).geometry;
        double haversine = haversine(37.6344054041544, 55.6994810644531, 37.6363818514204, 55.7001551990147);
        // масштаб UTM у 37.6° в.д. отличается от 1 на доли процента
        assertThat(a.distance(b)).isCloseTo(haversine, org.assertj.core.data.Percentage.withPercentage(0.5));
    }

    @Test
    void invalidFeaturesAreRejectedWithDiagnostics() throws IOException {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
                // нет flow_tph
                + "{\"type\":\"Feature\",\"properties\":{\"id\":\"a\",\"object_type\":\"oks_connection_point\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}},"
                // камера линией
                + "{\"type\":\"Feature\",\"properties\":{\"id\":7,\"object_type\":\"heat_chamber\"},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.7],[37.61,55.7]]}},"
                // неизвестный restriction_type принимается с предупреждением
                + "{\"type\":\"Feature\",\"properties\":{\"id\":8,\"object_type\":\"restriction\",\"restriction_type\":\"metro\"},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6,55.7],[37.61,55.7]]}},"
                // нет object_type
                + "{\"type\":\"Feature\",\"properties\":{\"id\":9},\"geometry\":null}"
                + "]}";
        IngestReport report = run(json);

        assertThat(report.featuresRead).isEqualTo(4);
        assertThat(report.featuresStored).isEqualTo(1);
        assertThat(report.issueCounts)
                .containsEntry("ERROR:BAD_FLOW", 1L)
                .containsEntry("ERROR:WRONG_GEOMETRY_TYPE", 1L)
                .containsEntry("ERROR:NO_OBJECT_TYPE", 1L)
                .containsEntry("WARN:UNKNOWN_RESTRICTION_TYPE", 1L);
        assertThat(stored.get(0).restrictionType).isEqualTo("metro");
        assertThat(stored.get(0).srcIdIsNumber).isTrue();
    }

    @Test
    void nonFeatureCollectionIsRefused() {
        assertThatThrownBy(() -> run("{\"type\":\"Feature\"}")).isInstanceOf(GeoJsonFormatException.class);
    }

    private static String point(int id, double lon, double lat) {
        return "{\"type\":\"Feature\",\"properties\":{\"id\":" + id + ",\"object_type\":\"heat_chamber\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}}";
    }

    private static double haversine(double lon1, double lat1, double lon2, double lat2) {
        double r = 6_371_008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
