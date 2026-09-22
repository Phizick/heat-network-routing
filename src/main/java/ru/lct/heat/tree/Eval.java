package ru.lct.heat.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;

/** Стоимость и показатель S варианта (раздел 6 rules.md), плюс разбиение рёбер на выходные участки. */
public final class Eval {

    /** Выходной линейный участок: часть ребра с постоянными параметрами (R-GEO-05, R-RST-05). */
    public static final class Part {
        public final List<Coordinate> coords;
        public final boolean special;
        public final double k;
        public final String types;
        public final double length;
        public final double cost;

        Part(List<Coordinate> coords, boolean special, double k, String types, double length, double cost) {
            this.coords = coords;
            this.special = special;
            this.k = k;
            this.types = types;
            this.length = length;
            this.cost = cost;
        }
    }

    public double pipeCost;
    public double chamberCost;
    public int existingTieInCount;
    public double existingTieInCost;
    public double constructionCost;
    public double unconnectedPenalty;
    public double calculatedCost;
    public double length;
    public double score;
    /** Отрезки, не прошедшие проверку ограничений (должно быть 0). */
    public int invalidSegments;
    public final Map<Integer, List<Part>> parts = new LinkedHashMap<>();
    /** Стоимость новых камер по id узла. */
    public final Map<Integer, Double> chamberCosts = new LinkedHashMap<>();
    public final Map<Integer, Integer> chamberDn = new LinkedHashMap<>();
    public final List<String> problems = new ArrayList<>();

    /** Показатель для сравнения решений в оптимизаторе: невалидные решения хуже любых валидных. */
    public double rankScore() {
        return score + invalidSegments * 1000.0;
    }
}
