package com.ladygo.driver.ride

import com.ladygo.driver.api.generated.models.BookingDetailResponse.Status

/** What the active-ride screen shows. Derived only from the server's [Status] - never authored
 *  client-side (design doc §3). */
enum class RideStage {
    TO_PICKUP,
    ARRIVED,
    IN_TRIP,
    PAYMENT,
    FINISHED,
    ENDED,
    ;

    /** The ride still needs the driver (so the active booking id is worth remembering across an app kill). */
    val isOngoing: Boolean get() = this != FINISHED && this != ENDED

    /** Call 112 is offered only across the window trip-sharing/safety applies to (design doc §1/§4). */
    val showsEmergencyCall: Boolean get() = this == TO_PICKUP || this == ARRIVED || this == IN_TRIP
}

object RideStateMachine {
    /** Forward progress order of the statuses a tracked ride moves through; `null` for statuses that
     *  mean the ride is no longer this driver's. A response ranked below the one already shown is a
     *  stale one (a slow reconcile overtaken by an action result) and must not win. */
    private fun progressRank(status: Status?): Int? =
        when (status) {
            Status.DRIVER_ACCEPTED -> 0
            Status.DRIVER_ARRIVING -> 1
            Status.DRIVER_ARRIVED -> 2
            Status.RIDE_STARTED -> 3
            Status.RIDE_COMPLETED -> 4
            Status.PAYMENT_PENDING -> 5
            Status.COMPLETED -> 6
            else -> null
        }

    /** Whether [incoming] may replace [current]. Moving backwards is only legitimate when the ride has
     *  left this driver (cancelled / bounced back to the pool), never for another in-flight status. */
    fun isStaleRegression(
        current: Status?,
        incoming: Status?,
    ): Boolean {
        val currentRank = progressRank(current) ?: return false
        val incomingRank = progressRank(incoming) ?: return false
        return incomingRank < currentRank
    }

    /** `null` for a status the app doesn't know about (a newer server enum value): the caller keeps
     *  whatever it was already showing instead of guessing. */
    fun stageFor(status: Status?): RideStage? =
        when (status) {
            Status.DRIVER_ACCEPTED, Status.DRIVER_ARRIVING -> RideStage.TO_PICKUP
            Status.DRIVER_ARRIVED -> RideStage.ARRIVED
            Status.RIDE_STARTED -> RideStage.IN_TRIP
            Status.RIDE_COMPLETED, Status.PAYMENT_PENDING -> RideStage.PAYMENT
            Status.COMPLETED -> RideStage.FINISHED
            // A booking that is no longer this driver's: cancelled, or bounced back to the search pool.
            Status.CANCELLED, Status.REQUESTED, Status.SEARCHING_DRIVER, Status.DRIVER_ASSIGNED -> RideStage.ENDED
            null -> null
        }
}
