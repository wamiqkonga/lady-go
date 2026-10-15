package com.ladygo.driver.location

import org.junit.Assert.assertEquals
import org.junit.Test

class GoOnlineFlowTest {
    @Test
    fun requestsFineLocationFirstWhenMissing() {
        val step =
            decideNextGoOnlineStep(
                hasFineLocationPermission = false,
                hasShownBatteryGuidance = true,
                shouldRequestNotificationPermission = true,
            )
        assertEquals(GoOnlineStep.REQUEST_FINE_LOCATION, step)
    }

    @Test
    fun showsBatteryGuidanceOnceFineLocationIsGranted() {
        val step =
            decideNextGoOnlineStep(
                hasFineLocationPermission = true,
                hasShownBatteryGuidance = false,
                shouldRequestNotificationPermission = true,
            )
        assertEquals(GoOnlineStep.SHOW_BATTERY_GUIDANCE, step)
    }

    @Test
    fun requestsNotificationPermissionAfterFineLocationAndBatteryGuidance() {
        val step =
            decideNextGoOnlineStep(
                hasFineLocationPermission = true,
                hasShownBatteryGuidance = true,
                shouldRequestNotificationPermission = true,
            )
        assertEquals(GoOnlineStep.REQUEST_NOTIFICATION_PERMISSION, step)
    }

    @Test
    fun proceedsWhenEverythingIsAlreadySatisfied() {
        val step =
            decideNextGoOnlineStep(
                hasFineLocationPermission = true,
                hasShownBatteryGuidance = true,
                shouldRequestNotificationPermission = false,
            )
        assertEquals(GoOnlineStep.PROCEED, step)
    }

    @Test
    fun fineLocationTakesPriorityOverEverythingElse() {
        val step =
            decideNextGoOnlineStep(
                hasFineLocationPermission = false,
                hasShownBatteryGuidance = false,
                shouldRequestNotificationPermission = false,
            )
        assertEquals(GoOnlineStep.REQUEST_FINE_LOCATION, step)
    }

    @Test
    fun batteryGuidanceTakesPriorityOverNotificationPermission() {
        val step =
            decideNextGoOnlineStep(
                hasFineLocationPermission = true,
                hasShownBatteryGuidance = false,
                shouldRequestNotificationPermission = false,
            )
        assertEquals(GoOnlineStep.SHOW_BATTERY_GUIDANCE, step)
    }
}
