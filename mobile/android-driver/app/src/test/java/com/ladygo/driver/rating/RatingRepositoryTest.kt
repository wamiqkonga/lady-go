package com.ladygo.driver.rating

import com.ladygo.driver.api.generated.apis.RatingControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseRatingResponse
import com.ladygo.driver.api.generated.models.SubmitRatingRequest
import io.mockk.coEvery
import io.mockk.coVerify
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

class RatingRepositoryTest {
    private val api = mockk<RatingControllerApi>()
    private val repository = RatingRepository(api)
    private val id = UUID.randomUUID()

    private fun error(
        code: Int,
        body: String,
    ) = Response.error<ApiResponseRatingResponse>(code, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun sendsScoreAndTagsOnlyAndNeverAComment() =
        runTest {
            val sent = slot<SubmitRatingRequest>()
            coEvery { api.rateRider(id, capture(sent)) } returns Response.success(ApiResponseRatingResponse())

            val outcome = repository.rateRider(id, 4, setOf(RiderRatingTag.ON_TIME, RiderRatingTag.RESPECTFUL))

            assertEquals(RateRiderOutcome.Success, outcome)
            assertEquals(4, sent.captured.score)
            assertEquals(listOf(SubmitRatingRequest.Tags.RESPECTFUL, SubmitRatingRequest.Tags.ON_TIME), sent.captured.tags)
            assertNull(sent.captured.comment)
        }

    @Test
    fun noTagsIsSentAsNoTagsField() =
        runTest {
            val sent = slot<SubmitRatingRequest>()
            coEvery { api.rateRider(id, capture(sent)) } returns Response.success(ApiResponseRatingResponse())

            repository.rateRider(id, 5, emptySet())

            assertNull(sent.captured.tags)
        }

    @Test
    fun http409MeansAlreadyRated() =
        runTest {
            coEvery { api.rateRider(any(), any()) } returns error(409, """{"success":false,"error":{"code":"CONFLICT","message":"x"}}""")

            assertEquals(RateRiderOutcome.AlreadyRated, repository.rateRider(id, 3, emptySet()))
        }

    @Test
    fun anotherFourXxCarriesTheServersRealMessage() =
        runTest {
            val body =
                """{"success":false,"error":{"code":"RATING_WINDOW_CLOSED","message":"The rating window for this ride has closed."}}"""
            coEvery { api.rateRider(any(), any()) } returns error(422, body)

            assertEquals(
                RateRiderOutcome.Rejected("The rating window for this ride has closed."),
                repository.rateRider(id, 3, emptySet()),
            )
        }

    @Test
    fun aFourXxWithoutAParseableBodyUsesAPlainMessage() =
        runTest {
            coEvery { api.rateRider(any(), any()) } returns error(400, "<html>")

            assertEquals(RateRiderOutcome.Rejected("Could not save your rating."), repository.rateRider(id, 3, emptySet()))
        }

    @Test
    fun aServerErrorOrNoConnectionIsANetworkFailure() =
        runTest {
            coEvery { api.rateRider(any(), any()) } returns error(503, "{}")
            assertEquals(RateRiderOutcome.NetworkFailure, repository.rateRider(id, 3, emptySet()))

            coEvery { api.rateRider(any(), any()) } throws IOException("offline")
            assertEquals(RateRiderOutcome.NetworkFailure, repository.rateRider(id, 3, emptySet()))
            coVerify(exactly = 2) { api.rateRider(id, any()) }
        }
}
