package com.ladygo.driver.ride

import com.ladygo.driver.api.generated.models.BookingDetailResponse.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStateMachineTest {
    @Test
    fun everyServerStatusMapsToTheExpectedStage() {
        val expected =
            mapOf(
                Status.DRIVER_ACCEPTED to RideStage.TO_PICKUP,
                Status.DRIVER_ARRIVING to RideStage.TO_PICKUP,
                Status.DRIVER_ARRIVED to RideStage.ARRIVED,
                Status.RIDE_STARTED to RideStage.IN_TRIP,
                Status.RIDE_COMPLETED to RideStage.PAYMENT,
                Status.PAYMENT_PENDING to RideStage.PAYMENT,
                Status.COMPLETED to RideStage.FINISHED,
                Status.CANCELLED to RideStage.ENDED,
                Status.REQUESTED to RideStage.ENDED,
                Status.SEARCHING_DRIVER to RideStage.ENDED,
                Status.DRIVER_ASSIGNED to RideStage.ENDED,
            )
        assertEquals("every Status value must be covered", Status.values().toSet(), expected.keys)
        expected.forEach { (status, stage) -> assertEquals(status.name, stage, RideStateMachine.stageFor(status)) }
    }

    @Test
    fun anUnknownStatusYieldsNoStage() {
        assertNull(RideStateMachine.stageFor(null))
    }

    @Test
    fun onlyLiveStagesAreOngoingAndOnlyTheInRideWindowShowsCall112() {
        assertTrue(RideStage.PAYMENT.isOngoing)
        assertFalse(RideStage.FINISHED.isOngoing)
        assertFalse(RideStage.ENDED.isOngoing)
        assertTrue(RideStage.TO_PICKUP.showsEmergencyCall)
        assertTrue(RideStage.ARRIVED.showsEmergencyCall)
        assertTrue(RideStage.IN_TRIP.showsEmergencyCall)
        assertFalse(RideStage.PAYMENT.showsEmergencyCall)
    }

    @Test
    fun onlyABackwardsMoveWithinTheTrackedRideIsStale() {
        assertTrue(RideStateMachine.isStaleRegression(Status.RIDE_STARTED, Status.DRIVER_ARRIVED))
        assertTrue(RideStateMachine.isStaleRegression(Status.PAYMENT_PENDING, Status.RIDE_STARTED))
        assertFalse(RideStateMachine.isStaleRegression(Status.DRIVER_ARRIVED, Status.RIDE_STARTED))
        assertFalse(RideStateMachine.isStaleRegression(Status.RIDE_STARTED, Status.RIDE_STARTED))
        // Leaving the driver (cancelled / bounced back) is real news, never "stale".
        assertFalse(RideStateMachine.isStaleRegression(Status.RIDE_STARTED, Status.CANCELLED))
        assertFalse(RideStateMachine.isStaleRegression(Status.DRIVER_ARRIVING, Status.SEARCHING_DRIVER))
        assertFalse(RideStateMachine.isStaleRegression(null, Status.DRIVER_ACCEPTED))
    }
}
