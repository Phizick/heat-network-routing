package ru.lct.heat.ingest;

import org.locationtech.jts.geom.Geometry;

/** Объект входа после проверки, геометрия уже в расчётной CRS. */
public final class IngestedFeature {

    /** id как в исходном файле: строка или число (тип сохраняется для выхода). */
    public final String srcId;
    public final boolean srcIdIsNumber;
    public final String objectType;
    public final String restrictionType;
    /** Исходные properties в JSON: доп. атрибуты храним, но в расчёте не используем (R-IN-04). */
    public final String propertiesJson;
    public final Geometry geometry;

    public IngestedFeature(String srcId, boolean srcIdIsNumber, String objectType, String restrictionType,
                           String propertiesJson, Geometry geometry) {
        this.srcId = srcId;
        this.srcIdIsNumber = srcIdIsNumber;
        this.objectType = objectType;
        this.restrictionType = restrictionType;
        this.propertiesJson = propertiesJson;
        this.geometry = geometry;
    }
}
