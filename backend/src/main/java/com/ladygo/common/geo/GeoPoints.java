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
}
