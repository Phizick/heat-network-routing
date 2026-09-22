package ru.lct.heat.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Параметры сервиса (раздел 10 docs/rules.md). Всё настраиваемое живёт здесь, в коде ничего не зашито. */
@Data
@ConfigurationProperties("heat")
public class HeatProperties {

    private Crs crs = new Crs();
    private Ingest ingest = new Ingest();
    /** Таблица 1: ДУ, пропускная способность, предельная длина, стоимость, габарит. */
    private List<Pipe> pipes = new ArrayList<>();
    private Chambers chambers = new Chambers();
    /** Таблица 2: правила по restriction_type. */
    private Map<String, Restriction> restrictions = new LinkedHashMap<>();
    /** Поведение для restriction_type, которых нет в таблице 2. */
    private Restriction unknownRestriction = new Restriction();
    /** Пересечение существующей heat_network без врезки (строка таблицы 2). */
    private Restriction existingNetworkCrossing = new Restriction();
    private Geometry geometry = new Geometry();
    private Score score = new Score();
    private Solver solver = new Solver();
    private Variants variants = new Variants();

    @Data
    public static class Crs {
        /** CRS расчётов: все длины, расстояния и углы считаются в ней. */
        private String calc = "EPSG:32637";
    }

    @Data
    public static class Ingest {
        /** Временный диск для загружаемых файлов (вход до 3 ГБ в RAM не грузим). */
        private String tempDir = "/tmp/heat";
        private int batchSize = 1000;
        private int maxIssueSamples = 500;
        private int workers = 2;
        /** restriction_type, для которых есть правила. Остальные принимаются с предупреждением (R-IN-03). */
        private Set<String> knownRestrictionTypes = new LinkedHashSet<>();
    }

    @Data
    public static class Pipe {
        private int dn;
        private double qMax;
        private double lMax;
        private double costM;
        private double width;
        private double height;
    }

    @Data
    public static class Chambers {
        private int maxConnections = 4;
        private double existingSnapRadiusM = 10;
        private double existingTieInCost = 5_000_000;
        private List<DnRange> newCostByMaxDn = new ArrayList<>();
    }

    @Data
    public static class DnRange {
        private int from;
        private int to;
        private double value;
    }

    public enum Rule { FORBID, SPECIAL }

    public enum Zone { POLYGON_PLUS, POINT_PM }

    @Data
    public static class Restriction {
        private Rule rule = Rule.FORBID;
        /** Мин. горизонтальное расстояние, м. */
        private double offset = 1.0;
        /** Отступ, зависящий от ДУ (oks). Если задан, важнее offset. */
        private List<DnRange> offsetByDn = new ArrayList<>();
        /** Мин. угол пересечения, градусы (0 = не задан). */
        private double minAngleDeg;
        private Zone zone = Zone.POINT_PM;
        /** Полигон + extra с каждой стороны или ±extra от точки пересечения, м. */
        private double extraM;
        private double kSpec = 1.0;
        /** Собственная ширина габарита ограничения, м (газ, кабель). */
        private double ownWidth;
    }

    @Data
    public static class Geometry {
        private double maxTurnAngleDeg = 90;
        /** Буфер вокруг охвата входных объектов, дальше трасса не уходит (R-GEO-06). */
        private double searchAreaBufferM = 200;
        /** Допуск склейки концов существующих линий и камер. */
        private double topologySnapToleranceM = 0.5;
        /** Точка ОКС вне полигона, но ближе этого - считается на его границе. */
        private double oksPolygonToleranceM = 1.0;
        /** Упрощение контуров ограничений перед расширением (отступ увеличивается на ту же величину). */
        private double simplifyToleranceM = 0.3;
        /** Запас к отступу, чтобы численные погрешности не давали нарушений. */
        private double clearanceMarginM = 0.05;
        /** Шаг вспомогательных узлов вдоль дорог для поворота сразу после спецперехода. */
        private double roadSampleStepM = 10;
        /** Штраф за поворот в метрах трассы: только для выбора пути, в стоимость не входит. */
        private double turnPenaltyM = 1.0;
    }

    @Data
    public static class Score {
        private double wCost = 0.7;
        private double costNorm = 25_000_000;
        private double wLen = 0.3;
        private double lenNorm = 100;
        private double penaltyBase = 100_000_000;
        private double penaltyPerTph = 500_000;
    }

    @Data
    public static class Solver {
        /** Бюджет времени на улучшение одного режима, с. */
        private double timeBudgetS = 60;
        private int greedyRestarts = 8;
        private long seed = 42;
        private int maxSizingIterations = 10;
    }

    @Data
    public static class Variants {
        private int maxCount = 3;
        /** Минимальное различие вариантов (1 - Жаккар по коридорам), R-VAR-02. */
        private double minDifference = 0.2;
        private double corridorBufferM = 5;
    }
}
