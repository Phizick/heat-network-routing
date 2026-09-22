package ru.lct.heat.route;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;

/** Куда может прийти трасса: существующая сеть, новая сеть, конкретная точка. */
public interface TargetSet {

    /** Кандидаты концов трассы, видимые (предположительно) из from. */
    void candidates(Coordinate from, List<Goal> out);

    /** Нижняя оценка расстояния до любой цели (для эвристики A*). */
    double minDistance(Coordinate from);

    /** Нижняя оценка цены узла в конце трассы. */
    double minNodeScore();
}
