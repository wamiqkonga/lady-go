package com.ladygo.common.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/** Builds the {@code geography(Point,4326)} values every module's API layer needs (WGS84, SRID 4326). */
public final class GeoPoints {

    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private GeoPoints() {
    }

    /** API DTOs speak latitude-then-longitude; JTS/PostGIS convention is x=longitude, y=latitude. */
    public static Point of(double latitude, double longitude) {
        return FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    /** Great-circle distance in metres - used to reject an implausible speed jump between two
     *  consecutive driver location updates (no PostGIS round-trip needed for that check). */
    public static double haversineMeters(Point a, Point b) {
        double earthRadiusM = 6_371_000;
        double lat1 = Math.toRadians(a.getY());
        double lat2 = Math.toRadians(b.getY());
        double deltaLat = Math.toRadians(b.getY() - a.getY());
        double deltaLon = Math.toRadians(b.getX() - a.getX());
        double sinLat = Math.sin(deltaLat / 2);
        double sinLon = Math.sin(deltaLon / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2 * earthRadiusM * Math.asin(Math.sqrt(h));
    }

    /** Rounds a point onto a grid of roughly {@code gridMeters} cells - the "approximate pickup area"
     *  shown to an offered-but-not-yet-accepting driver (docs/api/matching.md). Deliberately coarse and
     *  deterministic (not random jitter): simple to reason about and to test. */
    public static Point roundToGrid(Point point, double gridMeters) {
        double metersPerDegreeLat = 111_320;
        double metersPerDegreeLon = 111_320 * Math.cos(Math.toRadians(point.getY()));
        double gridDegLat = gridMeters / metersPerDegreeLat;
        double gridDegLon = metersPerDegreeLon == 0 ? gridDegLat : gridMeters / metersPerDegreeLon;
        double roundedLat = Math.round(point.getY() / gridDegLat) * gridDegLat;
        double roundedLon = Math.round(point.getX() / gridDegLon) * gridDegLon;
        return of(roundedLat, roundedLon);
    }
}
