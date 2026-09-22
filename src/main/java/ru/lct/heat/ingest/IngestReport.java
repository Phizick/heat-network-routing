package ru.lct.heat.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Итог загрузки и диагностика данных. Сериализуется в dataset.report (jsonb). */
public class IngestReport {

    public enum Severity { ERROR, WARN }

    /** Одна проблема в данных. ERROR: объект отброшен, WARN: принят. */
    public static class Issue {
        public long featureIndex;
        public String srcId;
        public String objectType;
        public Severity severity;
        public String code;
        public String message;

        public Issue() {
        }

        Issue(long featureIndex, String srcId, String objectType, Severity severity, String code, String message) {
            this.featureIndex = featureIndex;
            this.srcId = srcId;
            this.objectType = objectType;
            this.severity = severity;
            this.code = code;
            this.message = message;
        }
    }

    public String sourceCrs;
    public long featuresRead;
    public long featuresStored;
    public long featuresRejected;
    public Map<String, Long> objectTypeCounts = new TreeMap<>();
    public Map<String, Long> restrictionTypeCounts = new TreeMap<>();
    public double totalFlowTph;
    /** [minLon, minLat, maxLon, maxLat] принятых объектов. */
    public double[] bboxWgs84;
    public Map<String, Long> issueCounts = new TreeMap<>();
    public List<Issue> issueSamples = new ArrayList<>();
    public boolean issueSamplesTruncated;

    void addIssue(int maxSamples, long index, String srcId, String objectType, Severity severity, String code, String message) {
        issueCounts.merge(severity + ":" + code, 1L, Long::sum);
        if (issueSamples.size() < maxSamples) {
            issueSamples.add(new Issue(index, srcId, objectType, severity, code, message));
        } else {
            issueSamplesTruncated = true;
        }
    }
}
