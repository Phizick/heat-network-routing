package ru.lct.heat.dataset;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import ru.lct.heat.config.HeatProperties;
import ru.lct.heat.geo.Projection;
import ru.lct.heat.ingest.IngestProcessor;
import ru.lct.heat.ingest.IngestReport;

@Service
public class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetRepository repository;
    private final ObjectMapper mapper;
    private final HeatProperties props;
    private final ThreadPoolTaskExecutor ingestExecutor;
    private final Path uploadDir;

    public DatasetService(DatasetRepository repository, ObjectMapper mapper, HeatProperties props,
                          ThreadPoolTaskExecutor ingestExecutor, Path uploadDir) {
        this.repository = repository;
        this.mapper = mapper;
        this.props = props;
        this.ingestExecutor = ingestExecutor;
        this.uploadDir = uploadDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        int count = repository.failInterrupted();
        if (count > 0) {
            log.warn("{} загрузок прервано перезапуском, помечены как FAILED", count);
        }
    }

    /**
     * Файл переносится на временный диск (multipart-буфер удаляется после ответа),
     * разбор идёт в фоне. Статус смотрят через GET /api/datasets/{id}.
     */
    public UUID upload(MultipartFile file) throws IOException {
        UUID id = UUID.randomUUID();
        Path target = uploadDir.resolve(id + ".geojson");
        file.transferTo(target);
        repository.create(id, file.getOriginalFilename(), file.getSize());
        ingestExecutor.execute(() -> ingest(id, target));
        return id;
    }

    private void ingest(UUID id, Path file) {
        long started = System.currentTimeMillis();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            Projection projection = new Projection(props.getCrs().getCalc());
            IngestProcessor processor = new IngestProcessor(mapper, projection,
                    props.getIngest().getKnownRestrictionTypes(), props.getIngest().getMaxIssueSamples());
            IngestReport report = processor.process(in,
                    repository.sink(id, projection.calcSrid(), props.getIngest().getBatchSize()));
            repository.markReady(id, report);
            log.info("датасет {}: принято {}, отброшено {}, {} мс", id, report.featuresStored,
                    report.featuresRejected, System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("датасет {}: ошибка загрузки", id, e);
            repository.deleteFeatures(id);
            repository.markFailed(id, e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("не удалось удалить временный файл {}", file, e);
            }
        }
    }
}
