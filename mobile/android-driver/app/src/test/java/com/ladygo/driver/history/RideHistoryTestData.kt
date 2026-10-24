package com.ladygo.driver.history

import java.time.Instant
import java.util.UUID

fun rideItem(
    id: UUID = UUID.randomUUID(),
    status: RideHistoryStatus = RideHistoryStatus.COMPLETED,
    pickup: String? = "12 Lal Chowk",
    destination: String? = "Dal Gate",
    fare: Long? = 18_500,
    myRating: Int? = null,
    ratedByRider: Boolean = false,
) = RideHistoryItem(
    bookingId = id,
    status = status,
    completedAt = Instant.parse("2026-10-24T10:35:00Z"),
    pickupAddress = pickup,
    destinationAddress = destination,
    fare = fare,
    currency = "INR",
    payment = RidePayment.CASH,
    myRating = myRating,
    ratedByRider = ratedByRider,
)
