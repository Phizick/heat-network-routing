package ru.lct.heat.config;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Параметры сервиса (раздел 10 docs/rules.md). Всё настраиваемое живёт здесь, в коде ничего не зашито. */
@ConfigurationProperties("heat")
public class HeatProperties {

    private final Crs crs = new Crs();
    private final Ingest ingest = new Ingest();

    public Crs getCrs() {
        return crs;
    }

    public Ingest getIngest() {
        return ingest;
    }

    public static class Crs {
        /** CRS расчётов: все длины, расстояния и углы считаются в ней. */
        private String calc = "EPSG:32637";

        public String getCalc() {
            return calc;
        }

        public void setCalc(String calc) {
            this.calc = calc;
        }
    }

    public static class Ingest {
        /** Временный диск для загружаемых файлов (вход до 3 ГБ в RAM не грузим). */
        private String tempDir = "/tmp/heat";
        private int batchSize = 1000;
        private int maxIssueSamples = 500;
        private int workers = 2;
        /** restriction_type, для которых есть правила. Остальные принимаются с предупреждением (R-IN-03). */
        private Set<String> knownRestrictionTypes = new LinkedHashSet<>();

        public String getTempDir() {
            return tempDir;
        }

        public void setTempDir(String tempDir) {
            this.tempDir = tempDir;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxIssueSamples() {
            return maxIssueSamples;
        }

        public void setMaxIssueSamples(int maxIssueSamples) {
            this.maxIssueSamples = maxIssueSamples;
        }

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }

        public Set<String> getKnownRestrictionTypes() {
            return knownRestrictionTypes;
        }

        public void setKnownRestrictionTypes(Set<String> knownRestrictionTypes) {
            this.knownRestrictionTypes = knownRestrictionTypes;
        }
    }
}
