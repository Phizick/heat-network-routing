package ru.lct.heat.route;

import org.locationtech.jts.geom.Coordinate;

/** Точка, в которой трасса может закончиться, и цена узла в ней (в единицах S). */
public final class Goal {

    public enum Kind {
        /** Новая камера на существующей линии (R-TIE-03). */
        NEW_ON_LINE,
        /** Существующая камера (R-TIE-02). */
        EXISTING_CHAMBER,
        /** Новая камера ветвления на ребре новой сети. */
        SPLIT_EDGE,
        /** Уже существующий узел новой сети со свободным примыканием. */
        TREE_NODE,
        /** Фиксированная точка (перетрассировка ребра между известными узлами). */
        POINT
    }

    public final Kind kind;
    public final Coordinate point;
    public final double nodeScore;
    /** Индекс линии сети, камеры, ребра или узла дерева - по kind. */
    public final int ref;
    /** Линии существующей сети, чей коридор не проверяется на последнем отрезке. */
    public final int[] exemptLines;

    public Goal(Kind kind, Coordinate point, double nodeScore, int ref, int[] exemptLines) {
        this.kind = kind;
        this.point = point;
        this.nodeScore = nodeScore;
        this.ref = ref;
        this.exemptLines = exemptLines;
    }
}
