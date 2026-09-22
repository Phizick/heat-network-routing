package ru.lct.heat.dataset;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasets")
@Tag(name = "Датасеты", description = "Загрузка входного GeoJSON и диагностика данных")
public class DatasetController {

    private final DatasetService service;
    private final DatasetRepository repository;

    public DatasetController(DatasetService service, DatasetRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Operation(summary = "Загрузить GeoJSON FeatureCollection (до 3 ГБ), разбор идёт в фоне")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "пустой файл"));
        }
        UUID id = service.upload(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("id", id, "status", DatasetRepository.Status.PROCESSING.name()));
    }

    @Operation(summary = "Список загрузок")
    @GetMapping
    public List<Map<String, Object>> list() {
        return repository.list();
    }

    @Operation(summary = "Статус загрузки и отчёт диагностики")
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        Map<String, Object> dataset = repository.find(id);
        return dataset == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dataset);
    }

    @Operation(summary = "Состав загруженных данных по БД: счётчики по типам, длина сети, охват")
    @GetMapping("/{id}/summary")
    public ResponseEntity<Map<String, Object>> summary(@PathVariable UUID id) {
        if (repository.find(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.summary(id));
    }
}
