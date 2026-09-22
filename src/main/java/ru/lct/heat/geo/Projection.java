package ru.lct.heat.geo;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Перепроецирование WGS 84 (lon, lat) ↔ расчётная CRS.
 * Не потокобезопасен (proj4j держит внутреннее состояние): один экземпляр на поток или задачу.
 */
public final class Projection {

    private static final String WGS84 = "EPSG:4326";

    private final CoordinateTransform toCalc;
    private final CoordinateTransform toWgs84;
    private final GeometryFactory calcFactory;
    private final GeometryFactory wgs84Factory;

    public Projection(String calcCrs) {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem wgs = crsFactory.createFromName(WGS84);
        CoordinateReferenceSystem calc = crsFactory.createFromName(calcCrs);
        CoordinateTransformFactory ctf = new CoordinateTransformFactory();
        this.toCalc = ctf.createTransform(wgs, calc);
        this.toWgs84 = ctf.createTransform(calc, wgs);
        this.calcFactory = new GeometryFactory(new PrecisionModel(), srid(calcCrs));
        this.wgs84Factory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    public int calcSrid() {
        return calcFactory.getSRID();
    }

    public GeometryFactory wgs84Factory() {
        return wgs84Factory;
    }

    /** Возвращает новую геометрию в расчётной CRS, исходная не меняется. */
    public Geometry toCalc(Geometry wgs84) {
        return transform(wgs84, toCalc, calcFactory);
    }

    public Geometry toWgs84(Geometry calc) {
        return transform(calc, toWgs84, wgs84Factory);
    }

    private static Geometry transform(Geometry geometry, CoordinateTransform transform, GeometryFactory factory) {
        Geometry copy = factory.createGeometry(geometry);
        ProjCoordinate src = new ProjCoordinate();
        ProjCoordinate dst = new ProjCoordinate();
        copy.apply(new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                src.x = seq.getX(i);
                src.y = seq.getY(i);
                transform.transform(src, dst);
                seq.setOrdinate(i, CoordinateSequence.X, dst.x);
                seq.setOrdinate(i, CoordinateSequence.Y, dst.y);
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        return copy;
    }

    private static int srid(String crs) {
        int colon = crs.indexOf(':');
        return Integer.parseInt(crs.substring(colon + 1).trim());
    }
}
