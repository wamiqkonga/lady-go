package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.RatingControllerApi
import com.ladygo.customer.api.generated.models.SubmitRatingRequest
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RatingOutcome {
    data object Submitted : RatingOutcome

    /** The server already holds a different rating for this ride; hers is final, so there is nothing more to ask. */
    data object AlreadyRated : RatingOutcome

    /** The rating was not saved (offline, server error, or the rating window has closed); she can try again. */
    data object Failed : RatingOutcome
}

/** An interface so the rating flow is testable without a network. */
interface RatingSource {
    suspend fun rateDriver(
        bookingId: UUID,
        score: Int,
        tags: List<SubmitRatingRequest.Tags>,
        comment: String,
    ): RatingOutcome
}

private const val HTTP_CONFLICT = 409

@Singleton
class ServerRatingSource
    @Inject
    constructor(
        private val api: RatingControllerApi,
    ) : RatingSource {
        override suspend fun rateDriver(
            bookingId: UUID,
            score: Int,
            tags: List<SubmitRatingRequest.Tags>,
            comment: String,
        ): RatingOutcome {
            val request = SubmitRatingRequest(score = score, comment = comment.trim().ifEmpty { null }, tags = tags.ifEmpty { null })
            val response =
                try {
                    api.rateDriver(bookingId, request)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return RatingOutcome.Failed
                }
            return when {
                response.isSuccessful -> RatingOutcome.Submitted
                response.code() == HTTP_CONFLICT -> RatingOutcome.AlreadyRated
                else -> RatingOutcome.Failed
            }
        }
    }
