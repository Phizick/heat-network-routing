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
    /** Поведение для restriction_type, которых нет в таблице 2 (ответ организаторов: игнорировать). */
    private Restriction unknownRestriction = new Restriction();
    /** Пересечение существующей heat_network без врезки (строка таблицы 2). */
    private Restriction existingNetworkCrossing = new Restriction();
    private Geometry geometry = new Geometry();
    private Score score = new Score();
    private Solver solver = new Solver();
    private Variants variants = new Variants();
    private Depth depth = new Depth();
    /** Монтажный режим: углы 45/90 по сетке квартала, вдоль улиц, без параллельных прокладок. */
    private Installation installation = new Installation();

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
        /** Габарит при специальной прокладке (0 - как при обычной). */
        private double widthSpecial;
        private double heightSpecial;
        /** Минимальная глубина верха габарита, м (0 - общая depth.min-depth). */
        private double minDepth;
        private double minDepthSpecial;
    }

    @Data
    public static class Chambers {
        private int maxConnections = 4;
        private double existingSnapRadiusM = 10;
        private double existingTieInCost = 5_000_000;
        /**
         * Мягкое расширение правила R-TIE-02: радиус, в котором новая камера не ставится, если рядом
         * есть свободная существующая. 0 - выключено, действует только правило ТЗ (10 м).
         * На конкурсном наборе включение (25 м) удлиняет трассу примерно на 240 м и ухудшает S с 13,7 до 14,9.
         */
        private double preferExistingWithinM = 0;
        /**
         * Врезаться ближе этого расстояния к месту, где новая сеть пересекает ту же линию, нельзя
         * (ответ организаторов на вопрос 3).
         */
        private double minDistanceToCrossingM = 2;
        private List<DnRange> newCostByMaxDn = new ArrayList<>();
    }

    @Data
    public static class DnRange {
        private int from;
        private int to;
        private double value;
    }

    public enum Rule { FORBID, SPECIAL, IGNORE }

    public enum Zone { POLYGON_PLUS, POINT_PM }

    /** Вертикальное правило пересечения в режиме с глубиной (последний столбец таблицы 2). */
    public enum Vertical { NONE, BELOW, ABOVE_OR_BELOW }

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
        /** Режим с глубиной: как проходить пересечение по вертикали. */
        private Vertical vertical = Vertical.NONE;
        /** BELOW: верх габарита новой сети не мельче, м (дорога 1,0, трамвай 1,2). */
        private double minTopDepth;
        /** Условная глубина верха чужой коммуникации, м (газ 2,8, кабель 2,7, теплосеть 3,0). */
        private double depthTop;
        /** Высота габарита чужой коммуникации, м (для теплосети берётся из таблицы 1 по её ДУ). */
        private double ownHeight;
        /** Просвет по вертикали, м. */
        private double clearance;
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
        /**
         * Верхние границы классов ДУ для слоёв препятствий. Граница класса не должна пересекать смену
         * отступа oks (ДУ 500 и 900), иначе класс перестанет быть консервативным.
         */
        private List<Integer> clearanceClasses = new ArrayList<>(List.of(100, 150, 200, 300, 400, 600, 800, 1000, 1400));
        /** Радиус видимости в графе: дальние узлы связываются через промежуточные (масштаб на больших данных). */
        private double visibilityRadiusM = 600;
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
        /** Сколько расчётов идёт одновременно (остальные ждут в очереди). */
        private int workers = 2;
    }

    /** Режим расчёта: экономичный (минимум S) или монтажный (удобство строительства). */
    public enum Mode { ECONOMY, INSTALLATION }

    @Data
    public static class Installation {
        private boolean enabled = true;
        /** Шаг сетки квартала, м: по ней идут трассы, диагонали дают ровно 45°. */
        private double gridStepM = 8;
        /** Базовое направление сетки, градусы. Пусто - берём преобладающее направление застройки. */
        private Double gridAngleDeg;
        /** Ближе этого расстояния к существующей линии при малом угле трасса считается параллельной. */
        private double parallelDistanceM = 15;
        private double parallelAngleDeg = 20;
        /** Длина совпадения, начиная с которой параллельная прокладка запрещена. */
        private double parallelLengthM = 15;
        /** Во сколько раз дороже для поиска отрезок, идущий параллельно другой трубе. */
        private double parallelPenalty = 8;
        /** Предпочтение уличного коридора: во сколько раз дешевле для поиска (на стоимость не влияет). */
        private double streetPreference = 0.75;
        /** Ширина коридора вдоль дороги, в котором ведём трассу (от края зоны отступа), м. */
        private double streetCorridorM = 12;
    }

    @Data
    public static class Depth {
        private boolean enabled = true;
        private double normalDepth = 3.0;
        /** Запасная минимальная глубина, если в таблице 1 для ДУ она не задана. */
        private double minDepth = 0.7;
        /** 0 - не ограничена. */
        private double maxDepth;
        /** Шаг выбора глубины, м (правило 4.2). */
        private double stepM = 0.5;
        private double maxSlope = 0.10;
        /**
         * Таблица коэффициента стоимости по глубине с шагом step-m (правило 5).
         * Пустая - работает запасная формула k-threshold / k-per-m.
         */
        private List<DepthCost> costByDepth = new ArrayList<>();
        /** K_гл = 1 при h ≤ threshold, иначе 1 + perM · (h − threshold). */
        private double kThreshold = 3.0;
        private double kPerM = 0.10;
    }

    /** Строка таблицы стоимости по глубине: [from, to) метров -> коэффициент. */
    @Data
    public static class DepthCost {
        private double from;
        private double to;
        private double value = 1.0;
    }

    @Data
    public static class Variants {
        private int maxCount = 3;
        /** Минимальное различие вариантов (1 - Жаккар по коридорам), R-VAR-02. */
        private double minDifference = 0.2;
        private double corridorBufferM = 5;
    }
}
