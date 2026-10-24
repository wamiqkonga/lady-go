package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.apis.DriverOfferControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponseOfferResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.OfferRejectRequest
import com.ladygo.driver.api.generated.models.OfferResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.UUID

class OfferRepositoryTest {
    private val offerApi = mockk<DriverOfferControllerApi>()
    private val repository = OfferRepository(offerApi)
    private val offerId = UUID.randomUUID()

    @Test
    fun acceptReturnsTheBookingOnSuccess() =
        runTest {
            val booking = BookingDetailResponse(id = UUID.randomUUID(), status = BookingDetailResponse.Status.DRIVER_ACCEPTED)
            coEvery { offerApi.accept(offerId) } returns Response.success(ApiResponseBookingDetailResponse(data = booking))

            val outcome = repository.accept(offerId)

            assertTrue(outcome is AcceptOfferOutcome.Accepted)
            assertEquals(booking, (outcome as AcceptOfferOutcome.Accepted).booking)
        }

    /** `MatchingService.accept()` throws a real `ValidationException` for the already-claimed/expired
     *  cases - this is the terminal, non-retryable path (design doc §3 rule 7). */
    @Test
    fun acceptSurfacesTheRealServerRejectionAsTerminal() =
        runTest {
            coEvery { offerApi.accept(offerId) } returns
                errorResponse("""{"error":{"code":"CONFLICT","message":"This offer is no longer available."}}""")

            val outcome = repository.accept(offerId)

            assertTrue(outcome is AcceptOfferOutcome.Rejected)
            assertEquals("This offer is no longer available.", (outcome as AcceptOfferOutcome.Rejected).message)
        }

    @Test
    fun acceptSurfacesANetworkFailureAsRetryableNotTerminal() =
        runTest {
            coEvery { offerApi.accept(offerId) } throws java.io.IOException("no network")

            val outcome = repository.accept(offerId)

            assertTrue(outcome is AcceptOfferOutcome.NetworkFailure)
        }

    @Test
    fun aServerErrorOnAcceptIsRetryableNotTerminal() =
        runTest {
            coEvery { offerApi.accept(offerId) } returns
                errorResponse("""{"error":{"code":"INTERNAL","message":"boom"}}""", code = 500)

            val outcome = repository.accept(offerId)

            assertTrue(outcome is AcceptOfferOutcome.NetworkFailure)
        }

    @Test
    fun declineReturnsDeclinedOnSuccess() =
        runTest {
            coEvery { offerApi.reject(offerId, null) } returns Response.success(ApiResponseVoid())

            val outcome = repository.decline(offerId)

            assertEquals(DeclineOfferOutcome.Declined, outcome)
        }

    @Test
    fun declineSendsTheReasonWhenProvided() =
        runTest {
            coEvery { offerApi.reject(any(), any()) } returns Response.success(ApiResponseVoid())

            repository.decline(offerId, reason = "too far")

            coVerify { offerApi.reject(offerId, OfferRejectRequest(reason = "too far")) }
        }

    @Test
    fun aServerErrorOnDeclineIsRetryableNotTerminal() =
        runTest {
            coEvery { offerApi.reject(offerId, null) } returns
                errorResponse("""{"error":{"code":"INTERNAL","message":"boom"}}""", code = 503)

            val outcome = repository.decline(offerId)

            assertTrue(outcome is DeclineOfferOutcome.NetworkFailure)
        }

    @Test
    fun currentOfferReturnsPresentWhenOneExists() =
        runTest {
            val offer = OfferResponse(offerId = offerId, estimatedFare = 300L)
            coEvery { offerApi.currentOffer() } returns Response.success(ApiResponseOfferResponse(data = offer))

            val outcome = repository.currentOffer()

            assertTrue(outcome is CurrentOfferOutcome.Present)
            assertEquals(offer, (outcome as CurrentOfferOutcome.Present).offer)
        }

    @Test
    fun currentOfferReturnsNoneOn404() =
        runTest {
            coEvery { offerApi.currentOffer() } returns Response.error(404, "{}".toResponseBody("application/json".toMediaType()))

            val outcome = repository.currentOffer()

            assertEquals(CurrentOfferOutcome.None, outcome)
        }

    private fun <T> errorResponse(
        body: String,
        code: Int = 409,
    ): Response<T> = Response.error(code, body.toResponseBody("application/json".toMediaType()))
}
