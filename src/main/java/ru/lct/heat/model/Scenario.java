package ru.lct.heat.model;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

/** Входные данные одного расчёта в расчётной CRS. */
public final class Scenario {

    /** id объекта входа с сохранением исходного типа (строка или число). */
    public static final class SrcId {
        public final String value;
        public final boolean number;

        public SrcId(String value, boolean number) {
            this.value = value;
            this.number = number;
        }

        /** Значение для JSON: число остаётся числом. */
        public Object json() {
            if (!number) {
                return value;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return Double.parseDouble(value);
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public static final class NetworkLine {
        public final SrcId id;
        public final int dn;
        public final LineString geom;

        public NetworkLine(SrcId id, int dn, LineString geom) {
            this.id = id;
            this.dn = dn;
            this.geom = geom;
        }
    }

    public static final class Chamber {
        public final SrcId id;
        public final Point geom;

        public Chamber(SrcId id, Point geom) {
            this.id = id;
            this.geom = geom;
        }
    }

    public static final class Oks {
        public final SrcId id;
        public final double flow;
        public final Point geom;

        public Oks(SrcId id, double flow, Point geom) {
            this.id = id;
            this.flow = flow;
            this.geom = geom;
        }
    }

    public static final class RestrictionFeature {
        public final SrcId id;
        public final String type;
        public final Geometry geom;

        public RestrictionFeature(SrcId id, String type, Geometry geom) {
            this.id = id;
            this.type = type;
            this.geom = geom;
        }
    }

    public final List<Point> sources = new ArrayList<>();
    public final List<NetworkLine> network = new ArrayList<>();
    public final List<Chamber> chambers = new ArrayList<>();
    public final List<Oks> oks = new ArrayList<>();
    public final List<RestrictionFeature> restrictions = new ArrayList<>();
}
