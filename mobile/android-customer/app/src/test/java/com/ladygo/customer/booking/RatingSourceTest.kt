package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.RatingControllerApi
import com.ladygo.customer.api.generated.models.ApiResponseRatingResponse
import com.ladygo.customer.api.generated.models.RatingResponse
import com.ladygo.customer.api.generated.models.SubmitRatingRequest
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.UUID

class RatingSourceTest {
    private val api = mockk<RatingControllerApi>()
    private val source = ServerRatingSource(api)
    private val id = UUID.randomUUID()

    private fun failure(code: Int): Response<ApiResponseRatingResponse> =
        Response.error(code, "".toResponseBody("application/json".toMediaType()))

    @Test
    fun theRatingIsSentWithItsTagsAndATrimmedComment() =
        runTest {
            val sent = slot<SubmitRatingRequest>()
            coEvery { api.rateDriver(id, capture(sent)) } returns
                Response.success(ApiResponseRatingResponse(data = RatingResponse(bookingId = id, score = 5)))

            val outcome = source.rateDriver(id, 5, listOf(SubmitRatingRequest.Tags.POLITE), "  great  ")

            assertEquals(RatingOutcome.Submitted, outcome)
            assertEquals(5, sent.captured.score)
            assertEquals("great", sent.captured.comment)
            assertEquals(listOf(SubmitRatingRequest.Tags.POLITE), sent.captured.tags)
        }

    @Test
    fun anEmptyCommentAndNoTagsAreOmittedRatherThanSentBlank() =
        runTest {
            val sent = slot<SubmitRatingRequest>()
            coEvery { api.rateDriver(id, capture(sent)) } returns
                Response.success(ApiResponseRatingResponse(data = RatingResponse(bookingId = id, score = 4)))

            source.rateDriver(id, 4, emptyList(), "   ")

            assertNull(sent.captured.comment)
            assertNull(sent.captured.tags)
        }

    @Test
    fun aConflictMeansAlreadyRatedAndOtherFailuresAreRetryable() =
        runTest {
            coEvery { api.rateDriver(id, any()) } returns failure(409)
            assertEquals(RatingOutcome.AlreadyRated, source.rateDriver(id, 5, emptyList(), ""))

            coEvery { api.rateDriver(id, any()) } returns failure(500)
            assertEquals(RatingOutcome.Failed, source.rateDriver(id, 5, emptyList(), ""))

            coEvery { api.rateDriver(id, any()) } throws IOException("offline")
            assertEquals(RatingOutcome.Failed, source.rateDriver(id, 5, emptyList(), ""))
        }
}
