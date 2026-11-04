package com.ladygo.maps;

import java.util.List;

/**
 * Google's encoded polyline algorithm (precision 5), the format {@code directions/json} returns in
 * {@code overview_polyline.points}. Only encoding is needed on the server: it lets the dev/test mock hand the
 * apps a route in the same format the real provider does, so the fare-screen map can be exercised without a key.
 */
public final class PolylineCodec {

    private PolylineCodec() {
    }

    public static String encode(List<Coordinate> points) {
        StringBuilder out = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;
        for (Coordinate point : points) {
            long lat = Math.round(point.latitude() * 1e5);
            long lng = Math.round(point.longitude() * 1e5);
            append(out, lat - previousLat);
            append(out, lng - previousLng);
            previousLat = lat;
            previousLng = lng;
        }
        return out.toString();
    }

    private static void append(StringBuilder out, long delta) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            out.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        out.append((char) (value + 63));
    }
}
