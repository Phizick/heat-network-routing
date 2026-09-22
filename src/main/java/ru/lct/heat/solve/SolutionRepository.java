package ru.lct.heat.solve;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import ru.lct.heat.ingest.IngestedFeature;
import ru.lct.heat.model.Scenario;
import ru.lct.heat.model.ScenarioAssembler;

@Repository
public class SolutionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SolutionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void create(UUID id, UUID datasetId) {
        jdbc.update("insert into solution(id, dataset_id, status) values (?, ?, 'PROCESSING')", id, datasetId);
    }

    public void markReady(UUID id, Object summary, String result, String resultDepth) {
        jdbc.update("update solution set status = 'READY', summary = ?::jsonb, result = ?, result_depth = ?, "
                + "finished_at = now() where id = ?", toJson(summary), result, resultDepth, id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("update solution set status = 'FAILED', error = ?, finished_at = now() where id = ?", error, id);
    }

    public int failInterrupted() {
        return jdbc.update("update solution set status = 'FAILED', error = 'расчёт прерван перезапуском сервиса', "
                + "finished_at = now() where status = 'PROCESSING'");
    }

    public Map<String, Object> find(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, dataset_id, mode, status, created_at, finished_at, "
                + "error, summary::text as summary from solution where id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        Object summary = row.get("summary");
        row.put("summary", summary == null ? null : fromJson((String) summary));
        return row;
    }

    public List<Map<String, Object>> listByDataset(UUID datasetId) {
        return jdbc.queryForList("select id, mode, status, created_at, finished_at, error from solution "
                + "where dataset_id = ? order by created_at desc", datasetId);
    }

    public String result(UUID id, boolean depth) {
        List<String> rows = jdbc.queryForList("select " + (depth ? "result_depth" : "result") + " from solution where id = ?",
                String.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Загружает объекты датасета для расчёта. Ограничения - только в районе объектов сети и ОКС
     * (bbox + буфер), чтобы на входе в 3 ГБ не тянуть в память весь город.
     */
    public Scenario loadScenario(UUID datasetId, double bufferM, int srid) {
        ScenarioAssembler assembler = new ScenarioAssembler(mapper);
        WKBReader wkb = new WKBReader();
        String select = "select src_id, src_id_is_number, object_type, restriction_type, props::text as props, "
                + "ST_AsBinary(geom) as wkb from feature ";
        jdbc.query(select + "where dataset_id = ? and object_type <> 'restriction'", rs -> {
            assembler.accept(row(rs, wkb, srid));
        }, datasetId);
        jdbc.query(select + "where dataset_id = ? and object_type = 'restriction' and geom && ("
                + "select ST_Expand(ST_Extent(geom)::geometry, ?) from feature "
                + "where dataset_id = ? and object_type <> 'restriction')", rs -> {
            assembler.accept(row(rs, wkb, srid));
        }, datasetId, bufferM + 50, datasetId);
        return assembler.scenario();
    }

    private static IngestedFeature row(java.sql.ResultSet rs, WKBReader wkb, int srid) throws java.sql.SQLException {
        try {
            org.locationtech.jts.geom.Geometry g = wkb.read(rs.getBytes("wkb"));
            g.setSRID(srid);
            return new IngestedFeature(rs.getString("src_id"), rs.getBoolean("src_id_is_number"),
                    rs.getString("object_type"), rs.getString("restriction_type"), rs.getString("props"), g);
        } catch (ParseException e) {
            throw new java.sql.SQLException("битая геометрия в БД", e);
        }
    }

    /** Входные объекты в WGS 84 для карты (в районе расчёта). */
    public List<Map<String, Object>> inputFeatures(UUID datasetId, double bufferM, int limit) {
        return jdbc.queryForList("select src_id, object_type, restriction_type, props::text as props, "
                + "ST_AsGeoJSON(ST_Transform(geom, 4326), 7) as geometry from feature where dataset_id = ? and ("
                + "object_type <> 'restriction' or geom && (select ST_Expand(ST_Extent(geom)::geometry, ?) from feature "
                + "where dataset_id = ? and object_type <> 'restriction')) limit ?", datasetId, bufferM, datasetId, limit);
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
}
