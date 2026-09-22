package ru.lct.heat.ingest;

/** Файл не является GeoJSON FeatureCollection: продолжать разбор бессмысленно. */
public class GeoJsonFormatException extends RuntimeException {

    public GeoJsonFormatException(String message) {
        super(message);
    }
}
