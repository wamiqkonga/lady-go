package com.ladygo.driver.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationServicePolicyTest {
    @Test
    fun runsWhileOnline() {
        assertTrue(shouldRunLocationService(online = true, rideOngoing = false))
    }

    @Test
    fun keepsRunningDuringARideEvenWhenHomeFlagIsOffline() {
        assertTrue(shouldRunLocationService(online = false, rideOngoing = true))
    }

    @Test
    fun stopsWhenOfflineAndNoRide() {
        assertFalse(shouldRunLocationService(online = false, rideOngoing = false))
    }
}
