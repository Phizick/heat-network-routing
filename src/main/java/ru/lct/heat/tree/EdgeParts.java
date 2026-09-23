package ru.lct.heat.tree;

import java.util.List;

import org.locationtech.jts.geom.Coordinate;

/** Кэш разбиения ребра на участки: пересчитывается, только если сменилась трасса или ДУ. */
final class EdgeParts {

    final List<Coordinate> path;
    final int dn;
    final List<Eval.Part> parts;
    final int invalidSegments;

    EdgeParts(List<Coordinate> path, int dn, List<Eval.Part> parts, int invalidSegments) {
        this.path = path;
        this.dn = dn;
        this.parts = parts;
        this.invalidSegments = invalidSegments;
    }

    boolean valid(List<Coordinate> currentPath, int currentDn) {
        return path == currentPath && dn == currentDn;
    }
}
