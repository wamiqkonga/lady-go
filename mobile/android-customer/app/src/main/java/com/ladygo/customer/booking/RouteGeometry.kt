package com.ladygo.customer.booking

/**
 * Decodes Google's encoded polyline format (precision 5) - the route the server returns with a fare estimate.
 * Malformed input returns whatever decoded cleanly before the break, never throws: a bad route must degrade
 * to a shorter or straight line on the map, not crash the booking screen.
 */
fun decodePolyline(encoded: String): List<GeoPoint> {
    val reader = PolylineReader(encoded)
    val points = mutableListOf<GeoPoint>()
    var lat = 0
    var lng = 0
    while (reader.hasMore()) {
        val dLat = reader.nextDelta()
        val dLng = reader.nextDelta()
        if (dLat == null || dLng == null) return points
        lat += dLat
        lng += dLng
        points += GeoPoint(lat / POLYLINE_SCALE, lng / POLYLINE_SCALE)
    }
    return points
}

private class PolylineReader(
    private val encoded: String,
) {
    private var index = 0

    fun hasMore(): Boolean = index < encoded.length

    /** The next zig-zag-encoded delta, or `null` if the string ends mid-value or holds a character outside the format. */
    fun nextDelta(): Int? {
        var result = 0
        var shift = 0
        var chunk: Int
        do {
            if (index >= encoded.length) return null
            chunk = encoded[index++].code - ASCII_OFFSET
            if (chunk < 0 || shift > MAX_SHIFT) return null
            result = result or ((chunk and CHUNK_MASK) shl shift)
            shift += CHUNK_BITS
        } while (chunk >= CONTINUATION_BIT)
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
}

private const val POLYLINE_SCALE = 1e5
private const val ASCII_OFFSET = 63
private const val CHUNK_MASK = 0x1f
private const val CHUNK_BITS = 5
private const val CONTINUATION_BIT = 0x20
private const val MAX_SHIFT = 30
