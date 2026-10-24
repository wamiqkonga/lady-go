package com.ladygo.driver.rating

import com.ladygo.driver.api.generated.apis.RatingControllerApi
import com.ladygo.driver.api.generated.models.SubmitRatingRequest
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The tags a driver can give a rider in v1 (the backend's `RatingTag` values allowed for the DRIVER rater). No free-text comment. */
enum class RiderRatingTag(
    val wire: SubmitRatingRequest.Tags,
) {
    RESPECTFUL(SubmitRatingRequest.Tags.RESPECTFUL),
    ON_TIME(SubmitRatingRequest.Tags.ON_TIME),
}

sealed interface RateRiderOutcome {
    data object Success : RateRiderOutcome

    /** HTTP 409: this driver already rated this ride. */
    data object AlreadyRated : RateRiderOutcome

    /** Any other 4xx (window closed, ride not completed, bad input): the server's own message. */
    data class Rejected(
        val message: String,
    ) : RateRiderOutcome

    /** No connection, or a 5xx: the rating may or may not have landed; the driver can retry (the server treats an identical resubmit as success). */
    data object NetworkFailure : RateRiderOutcome
}

/** `POST /api/v1/drivers/me/bookings/{bookingId}/rating` - score and tags only. */
@Singleton
class RatingRepository
    @Inject
    constructor(
        private val ratingApi: RatingControllerApi,
    ) {
        suspend fun rateRider(
            bookingId: UUID,
            score: Int,
            tags: Set<RiderRatingTag>,
        ): RateRiderOutcome {
            val request = SubmitRatingRequest(score = score, tags = tags.sortedBy { it.ordinal }.map { it.wire }.ifEmpty { null })
            val response =
                runCatching { ratingApi.rateRider(bookingId, request) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return RateRiderOutcome.NetworkFailure
                }
            return when {
                response.isSuccessful -> RateRiderOutcome.Success
                response.code() == HTTP_CONFLICT -> RateRiderOutcome.AlreadyRated
                response.code() >= HTTP_SERVER_ERROR_FLOOR -> RateRiderOutcome.NetworkFailure
                else -> RateRiderOutcome.Rejected(errorMessageOrDefault(response, "Could not save your rating."))
            }
        }

        private companion object {
            const val HTTP_CONFLICT = 409
            const val HTTP_SERVER_ERROR_FLOOR = 500
        }
    }
