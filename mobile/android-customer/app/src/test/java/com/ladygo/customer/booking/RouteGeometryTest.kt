package com.ladygo.customer.booking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    /** The worked example in Google's polyline algorithm documentation. */
    @Test
    fun decodesTheDocumentedExample() {
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 1e-9)
        assertEquals(-120.2, points[0].longitude, 1e-9)
        assertEquals(40.7, points[1].latitude, 1e-9)
        assertEquals(-120.95, points[1].longitude, 1e-9)
        assertEquals(43.252, points[2].latitude, 1e-9)
        assertEquals(-126.453, points[2].longitude, 1e-9)
    }

    @Test
    fun anEmptyStringIsAnEmptyRoute() {
        assertTrue(decodePolyline("").isEmpty())
    }

    @Test
    fun aTruncatedRouteKeepsTheCompletePointsAndNeverThrows() {
        val full = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        val cut = decodePolyline("_p~iF~ps|U_ulLnnqC_mqN")

        assertEquals(2, cut.size)
        assertEquals(full.take(2), cut)
    }

    @Test
    fun garbageNeverThrows() {
        decodePolyline(" \u0000 ~~~~~~~~~~~~~~~~~~~~~~~~~~")
        decodePolyline("!!!")
    }
}
