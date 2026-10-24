package com.ladygo.driver.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {
    private val id = "0b0f6c1e-1f6a-4a52-9a53-2f5f0f1d9c11"

    @Test
    fun theRideStartAndRiderSelfiePathsAreNotBodyLogged() {
        assertTrue(isBodyLoggingSensitive("/api/v1/drivers/me/rides/$id/start"))
        assertTrue(isBodyLoggingSensitive("/api/v1/drivers/me/rides/$id/start/"))
        assertTrue(isBodyLoggingSensitive("/api/v1/drivers/me/bookings/$id/rider-selfie"))
    }

    @Test
    fun ratingBodiesAreNotBodyLogged() {
        assertTrue(isBodyLoggingSensitive("/api/v1/drivers/me/bookings/$id/rating"))
        assertTrue(isBodyLoggingSensitive("/api/v1/bookings/$id/rating"))
    }

    @Test
    fun otherPathsStillGetBodyLogging() {
        assertFalse(isBodyLoggingSensitive("/api/v1/drivers/me/bookings/$id"))
        assertFalse(isBodyLoggingSensitive("/api/v1/drivers/me/bookings/$id/arrived"))
        assertFalse(isBodyLoggingSensitive("/api/v1/drivers/me/offers/current"))
    }
}
