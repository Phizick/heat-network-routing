package ru.lct.heat.ingest;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Потоковое чтение FeatureCollection: в памяти одновременно только один Feature,
 * поэтому размер файла (до 3 ГБ) на потребление RAM не влияет.
 */
public final class GeoJsonStreamReader {

    public interface Handler {
        void onCrs(String name);

        /** feature может быть не объектом: проверка остаётся на стороне обработчика. */
        void onFeature(long index, JsonNode feature);
    }

    private GeoJsonStreamReader() {
    }

    public static void read(InputStream in, ObjectMapper mapper, Handler handler) throws IOException {
        try (JsonParser p = mapper.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new GeoJsonFormatException("ожидался JSON-объект FeatureCollection");
            }
            boolean sawFeatures = false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                JsonToken value = p.nextToken();
                switch (field) {
                    case "type":
                        if (!"FeatureCollection".equals(p.getValueAsString())) {
                            throw new GeoJsonFormatException("type = " + p.getValueAsString() + ", ожидался FeatureCollection");
                        }
                        break;
                    case "crs": {
                        JsonNode crs = p.readValueAsTree();
                        handler.onCrs(crs == null ? null : crs.path("properties").path("name").asText(null));
                        break;
                    }
                    case "features": {
                        if (value != JsonToken.START_ARRAY) {
                            throw new GeoJsonFormatException("features должен быть массивом");
                        }
                        sawFeatures = true;
                        long index = 0;
                        while (p.nextToken() != JsonToken.END_ARRAY) {
                            handler.onFeature(index++, p.readValueAsTree());
                        }
                        break;
                    }
                    default:
                        p.skipChildren();
                }
            }
            if (!sawFeatures) {
                throw new GeoJsonFormatException("в файле нет массива features");
            }
        }
    }
}
