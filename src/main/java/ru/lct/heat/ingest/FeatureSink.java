package ru.lct.heat.ingest;

/** Куда складываются принятые объекты: в PostGIS в сервисе, в память в тестах. */
public interface FeatureSink {

    void accept(IngestedFeature feature);

    void flush();
}
