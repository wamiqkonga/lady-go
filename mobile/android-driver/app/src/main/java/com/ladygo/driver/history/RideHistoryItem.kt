package com.ladygo.driver.history

import com.ladygo.driver.api.generated.models.DriverRideHistoryItemResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class RideHistoryStatus { COMPLETED, CANCELLED, ENDED_EARLY, OTHER }

enum class RidePayment { CASH, ONLINE, UNKNOWN }

/**
 * One past ride as the app shows it. Built only from the history endpoint's own fields, which carry no rider
 * name, phone or photo (`docs/api/driver-earnings-ratings.md` §4.1) - the detail screen reuses exactly this row.
 * Addresses are null when the server hides them (a cancelled ride).
 */
data class RideHistoryItem(
    val bookingId: UUID,
    val status: RideHistoryStatus,
    val completedAt: Instant?,
    val pickupAddress: String?,
    val destinationAddress: String?,
    val fare: Long?,
    val currency: String?,
    val payment: RidePayment,
    /** The score this driver gave the rider, 1-5. */
    val myRating: Int?,
    /** Only a hint that the rider rated; the score itself is never sent. */
    val ratedByRider: Boolean,
)

/** The server's rating window (`docs/api/driver-earnings-ratings.md` §4.3). The server decides; this only decides whether to offer the button. */
private val RATING_WINDOW: Duration = Duration.ofDays(7)

/** Offer "Rate the rider": a completed ride she has not rated yet, inside the 7-day window. */
fun RideHistoryItem.canRate(now: Instant): Boolean {
    val finished = completedAt ?: return false
    return status == RideHistoryStatus.COMPLETED && myRating == null && now.isBefore(finished.plus(RATING_WINDOW))
}

/** Null for a row without a booking id (nothing to key or open it by). */
fun DriverRideHistoryItemResponse.toItem(): RideHistoryItem? {
    val id = bookingId ?: return null
    return RideHistoryItem(
        bookingId = id,
        status =
            when (status) {
                "COMPLETED" -> RideHistoryStatus.COMPLETED
                "CANCELLED" -> RideHistoryStatus.CANCELLED
                "ENDED_EARLY" -> RideHistoryStatus.ENDED_EARLY
                else -> RideHistoryStatus.OTHER
            },
        completedAt = completedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        pickupAddress = pickupAddress?.takeIf { it.isNotBlank() },
        destinationAddress = destinationAddress?.takeIf { it.isNotBlank() },
        fare = finalFare,
        currency = currency,
        payment =
            when (paymentMethod) {
                "CASH" -> RidePayment.CASH
                "ONLINE" -> RidePayment.ONLINE
                else -> RidePayment.UNKNOWN
            },
        myRating = myRating?.takeIf { it in 1..5 },
        ratedByRider = ratedByRider == true,
    )
}
