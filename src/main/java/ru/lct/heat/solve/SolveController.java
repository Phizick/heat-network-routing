package ru.lct.heat.solve;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.lct.heat.config.HeatProperties;

@RestController
@Tag(name = "Расчёт", description = "Построение сети подключения и выгрузка вариантов")
public class SolveController {

    private static final int MAP_LIMIT = 50_000;

    private final SolveService service;
    private final SolutionRepository repository;
    private final HeatProperties props;
    private final ObjectMapper mapper;

    public SolveController(SolveService service, SolutionRepository repository, HeatProperties props, ObjectMapper mapper) {
        this.service = service;
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
    }

    @Operation(summary = "Запустить расчёт 2D по загруженному датасету (в фоне)")
    @PostMapping("/api/datasets/{id}/solve")
    public ResponseEntity<Map<String, Object>> solve(@PathVariable UUID id) {
        try {
            UUID solution = service.start(id);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", solution, "status", "PROCESSING"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Расчёты по датасету")
    @GetMapping("/api/datasets/{id}/solutions")
    public List<Map<String, Object>> solutions(@PathVariable UUID id) {
        return repository.listByDataset(id);
    }

    @Operation(summary = "Статус расчёта и сводка по вариантам (S, стоимость, проверка правил)")
    @GetMapping("/api/solutions/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        Map<String, Object> s = repository.find(id);
        return s == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(s);
    }

    @Operation(summary = "Выход в формате ТЗ: GeoJSON FeatureCollection в WGS 84 со всеми вариантами")
    @GetMapping(value = "/api/solutions/{id}/geojson", produces = "application/geo+json")
    public ResponseEntity<String> geojson(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean download) {
        String result = repository.result(id);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.parseMediaType("application/geo+json"));
        if (download) {
            b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"heat-routing-" + id + "-2d.geojson\"");
        }
        return b.body(result);
    }

    @Operation(summary = "Входные объекты датасета в WGS 84 для карты (в районе расчёта)")
    @GetMapping(value = "/api/datasets/{id}/geojson", produces = "application/geo+json")
    public Map<String, Object> input(@PathVariable UUID id) throws IOException {
        List<Object> features = new ArrayList<>();
        for (Map<String, Object> row : repository.inputFeatures(id, props.getGeometry().getSearchAreaBufferM(), MAP_LIMIT)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("properties", mapper.readTree((String) row.get("props")));
            f.put("geometry", mapper.readTree((String) row.get("geometry")));
            features.add(f);
        }
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        return fc;
    }
}
