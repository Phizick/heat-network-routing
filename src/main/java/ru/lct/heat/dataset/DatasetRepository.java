package ru.lct.heat.dataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.io.WKBWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import ru.lct.heat.ingest.FeatureSink;
import ru.lct.heat.ingest.IngestReport;
import ru.lct.heat.ingest.IngestedFeature;

@Repository
public class DatasetRepository {

    public enum Status { PROCESSING, READY, FAILED }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatasetRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void create(UUID id, String name, long fileSize) {
        jdbc.update("insert into dataset(id, name, status, file_size) values (?, ?, ?, ?)",
                id, name, Status.PROCESSING.name(), fileSize);
    }

    public void markReady(UUID id, IngestReport report) {
        jdbc.update("update dataset set status = ?, report = ?::jsonb, finished_at = now() where id = ?",
                Status.READY.name(), toJson(report), id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("update dataset set status = ?, error = ?, finished_at = now() where id = ?",
                Status.FAILED.name(), error, id);
    }

    /** Загрузки, прерванные перезапуском сервиса, иначе навсегда останутся в PROCESSING. */
    public int failInterrupted() {
        return jdbc.update("update dataset set status = ?, error = 'обработка прервана перезапуском сервиса', "
                + "finished_at = now() where status = ?", Status.FAILED.name(), Status.PROCESSING.name());
    }

    public void deleteFeatures(UUID id) {
        jdbc.update("delete from feature where dataset_id = ?", id);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("select id, name, status, file_size, created_at, finished_at, error "
                + "from dataset order by created_at desc");
    }

    public Map<String, Object> find(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, name, status, file_size, created_at, finished_at, error, report::text as report "
                        + "from dataset where id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        Object report = row.get("report");
        row.put("report", report == null ? null : fromJson((String) report));
        return row;
    }

    /** Состав данных по факту в БД: счётчики по типам, протяжённость сети, охват. */
    public Map<String, Object> summary(UUID id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", jdbc.queryForList(
                "select object_type, restriction_type, count(*) as count from feature where dataset_id = ? "
                        + "group by object_type, restriction_type order by object_type, restriction_type", id));
        result.put("heatNetworkLengthM", jdbc.queryForObject(
                "select coalesce(round(sum(ST_Length(geom))::numeric, 1), 0) from feature "
                        + "where dataset_id = ? and object_type = 'heat_network'", Double.class, id));
        result.put("extentCalc", jdbc.queryForMap(
                "select ST_XMin(e) as min_x, ST_YMin(e) as min_y, ST_XMax(e) as max_x, ST_YMax(e) as max_y "
                        + "from (select ST_Extent(geom) as e from feature where dataset_id = ?) t", id));
        return result;
    }

    public FeatureSink sink(UUID datasetId, int srid, int batchSize) {
        return new JdbcFeatureSink(datasetId, srid, batchSize);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object fromJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Пакетная вставка: геометрия уходит в WKB, SRID проставляется на стороне БД. */
    private final class JdbcFeatureSink implements FeatureSink {

        private static final String INSERT = "insert into feature"
                + "(dataset_id, src_id, src_id_is_number, object_type, restriction_type, props, geom) "
                + "values (?, ?, ?, ?, ?, ?::jsonb, ST_SetSRID(ST_GeomFromWKB(?), ?))";

        private final UUID datasetId;
        private final int srid;
        private final int batchSize;
        private final WKBWriter wkb = new WKBWriter();
        private final List<Object[]> batch = new ArrayList<>();

        JdbcFeatureSink(UUID datasetId, int srid, int batchSize) {
            this.datasetId = datasetId;
            this.srid = srid;
            this.batchSize = batchSize;
        }

        @Override
        public void accept(IngestedFeature f) {
            batch.add(new Object[] {datasetId, f.srcId, f.srcIdIsNumber, f.objectType, f.restrictionType,
                    f.propertiesJson, wkb.write(f.geometry), srid});
            if (batch.size() >= batchSize) {
                flush();
            }
        }

        @Override
        public void flush() {
            if (!batch.isEmpty()) {
                jdbc.batchUpdate(INSERT, batch);
                batch.clear();
            }
        }
    }
}
