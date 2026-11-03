package com.ladygo.driver.map

import com.google.android.gms.maps.model.LatLng

/**
 * Decodes Google's encoded polyline format (precision 5) - the road the server returns for a leg of the ride.
 * Malformed input returns whatever decoded cleanly before the break, never throws: a bad route must degrade to a
 * shorter or straight line on the map, not crash the ride screen.
 */
fun decodePolyline(encoded: String): List<LatLng> {
    val points = mutableListOf<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0

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

    while (index < encoded.length) {
        val dLat = nextDelta() ?: return points
        val dLng = nextDelta() ?: return points
        lat += dLat
        lng += dLng
        points += LatLng(lat / POLYLINE_SCALE, lng / POLYLINE_SCALE)
    }
    return points
}

private const val POLYLINE_SCALE = 1e5
private const val ASCII_OFFSET = 63
private const val CHUNK_MASK = 0x1f
private const val CHUNK_BITS = 5
private const val CONTINUATION_BIT = 0x20
private const val MAX_SHIFT = 30
