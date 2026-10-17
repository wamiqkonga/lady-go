package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.BookingControllerApi
import com.ladygo.customer.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.customer.api.generated.models.ApiResponseFareEstimateResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseBookingDetailResponse
import com.ladygo.customer.api.generated.models.ApiResponseRideSelfieResponse
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingDetailResponse.Status
import com.ladygo.customer.api.generated.models.BookingRequest
import com.ladygo.customer.api.generated.models.FareEstimateResponse
import com.ladygo.customer.api.generated.models.PageResponseBookingDetailResponse
import com.ladygo.customer.api.generated.models.RideSelfieResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.UUID

class BookingRepositoryTest {
    private val bookingApi = mockk<BookingControllerApi>()
    private val selfieApi = mockk<RideSelfieApi>()
    private val repository = BookingRepository(bookingApi, selfieApi)
    private val id = UUID.randomUUID()

    private fun <T> error(
        code: Int,
        body: String = """{"success":false,"error":{"code":"X","message":"Server said no."}}""",
    ): Response<T> = Response.error(code, body.toResponseBody("application/json".toMediaType()))

    private val request =
        BookingRequest(
            1.0,
            2.0,
            "A",
            3.0,
            4.0,
            "B",
            BookingRequest.VehicleType.CAB,
            BookingRequest.PaymentMethod.CASH,
            18500L,
            UUID.randomUUID(),
        )

    @Test
    fun estimateSuccessReturnsTheServersQuote() =
        runTest {
            val quote = FareEstimateResponse(estimatedFare = 18500L, currency = "INR")
            coEvery { bookingApi.estimate(any()) } returns Response.success(ApiResponseFareEstimateResponse(data = quote))

            assertEquals(EstimateOutcome.Success(quote), repository.estimate(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)))
        }

    @Test
    fun anOutOfServiceAreaEstimateIsARejectionWithTheServersMessage() =
        runTest {
            coEvery { bookingApi.estimate(any()) } returns
                error(422, """{"error":{"code":"OUT_OF_SERVICE_AREA","message":"Lady Go is not available in this area yet."}}""")

            assertEquals(
                EstimateOutcome.Rejected("Lady Go is not available in this area yet."),
                repository.estimate(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)),
            )
        }

    @Test
    fun estimateIoOr5xxIsRetryableNotARejection() =
        runTest {
            coEvery { bookingApi.estimate(any()) } throws java.io.IOException("offline")
            assertTrue(repository.estimate(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)) is EstimateOutcome.NetworkFailure)

            coEvery { bookingApi.estimate(any()) } returns error(503)
            assertTrue(repository.estimate(GeoPoint(1.0, 2.0), GeoPoint(3.0, 4.0)) is EstimateOutcome.NetworkFailure)
        }

    @Test
    fun createReturnsTheCreatedBookingAndSendsTheIdempotencyKey() =
        runTest {
            val booking = BookingDetailResponse(id = id, status = Status.SEARCHING_DRIVER)
            coEvery { bookingApi.create("key-1", request) } returns Response.success(ApiResponseBookingDetailResponse(data = booking))

            assertEquals(CreateBookingOutcome.Created(booking), repository.create("key-1", request))
        }

    @Test
    fun fareChangedCarriesTheFreshQuoteFromTheErrorBody() =
        runTest {
            coEvery { bookingApi.create(any(), any()) } returns
                error(
                    422,
                    """{"success":false,"data":{"estimatedFare":21000,"currency":"INR"},""" +
                        """"error":{"code":"FARE_CHANGED","message":"The fare has changed since your estimate."}}""",
                )

            val outcome = repository.create("k", request)

            assertTrue(outcome is CreateBookingOutcome.FareChanged)
            outcome as CreateBookingOutcome.FareChanged
            assertEquals(21000L, outcome.freshEstimate.estimatedFare)
            assertEquals("The fare has changed since your estimate.", outcome.message)
        }

    @Test
    fun fareChangedWithAnUnreadableBodyIsAPlainRejectionNeverASilentAccept() =
        runTest {
            coEvery { bookingApi.create(any(), any()) } returns error(422, """{"error":{"code":"FARE_CHANGED","message":"changed"}}""")

            assertTrue(repository.create("k", request) is CreateBookingOutcome.Rejected)
        }

    @Test
    fun createRejection4xxVersusRetryable5xxOrIo() =
        runTest {
            coEvery { bookingApi.create(any(), any()) } returns error(422)
            assertEquals(CreateBookingOutcome.Rejected("Server said no."), repository.create("k", request))

            coEvery { bookingApi.create(any(), any()) } returns error(500)
            assertTrue(repository.create("k", request) is CreateBookingOutcome.NetworkFailure)

            coEvery { bookingApi.create(any(), any()) } throws java.io.IOException("offline")
            assertTrue(repository.create("k", request) is CreateBookingOutcome.NetworkFailure)
        }

    @Test
    fun uploadSelfieReturnsTheIdAndMapsFailures() =
        runTest {
            val selfieId = UUID.randomUUID()
            coEvery { selfieApi.upload(any()) } returns
                Response.success(ApiResponseRideSelfieResponse(data = RideSelfieResponse(rideSelfieId = selfieId)))
            assertEquals(SelfieUploadOutcome.Uploaded(selfieId), repository.uploadSelfie(byteArrayOf(1, 2, 3)))

            coEvery { selfieApi.upload(any()) } returns error(400)
            assertEquals(SelfieUploadOutcome.Rejected("Server said no."), repository.uploadSelfie(byteArrayOf(1)))

            coEvery { selfieApi.upload(any()) } throws java.io.IOException("offline")
            assertTrue(repository.uploadSelfie(byteArrayOf(1)) is SelfieUploadOutcome.NetworkFailure)
        }

    @Test
    fun fetchDistinguishesFoundNotFoundAndNetworkFailure() =
        runTest {
            val booking = BookingDetailResponse(id = id, status = Status.DRIVER_ASSIGNED)
            coEvery { bookingApi.get1(id) } returns Response.success(ApiResponseBookingDetailResponse(data = booking))
            assertEquals(BookingFetchOutcome.Found(booking), repository.fetch(id))

            coEvery { bookingApi.get1(id) } returns error(404)
            assertEquals(BookingFetchOutcome.NotFound, repository.fetch(id))

            coEvery { bookingApi.get1(id) } returns error(500)
            assertTrue(repository.fetch(id) is BookingFetchOutcome.NetworkFailure)
        }

    @Test
    fun cancelMapsTheOutcomes() =
        runTest {
            val cancelled = BookingDetailResponse(id = id, status = Status.CANCELLED)
            coEvery { bookingApi.cancel(id, any()) } returns Response.success(ApiResponseBookingDetailResponse(data = cancelled))
            assertEquals(CancelOutcome.Cancelled(cancelled), repository.cancel(id, "why"))

            coEvery { bookingApi.cancel(id, any()) } returns error(409)
            assertEquals(CancelOutcome.Rejected("Server said no."), repository.cancel(id, "why"))

            coEvery { bookingApi.cancel(id, any()) } throws java.io.IOException("offline")
            assertTrue(repository.cancel(id, "why") is CancelOutcome.NetworkFailure)
        }

    @Test
    fun findActiveBookingPicksTheFirstNonTerminalRideOnly() =
        runTest {
            val done = BookingDetailResponse(id = UUID.randomUUID(), status = Status.COMPLETED)
            val live = BookingDetailResponse(id = UUID.randomUUID(), status = Status.DRIVER_ARRIVING)
            val page = PageResponseBookingDetailResponse(content = listOf(done, live))
            coEvery { bookingApi.history(0, any(), any()) } returns
                Response.success(ApiResponsePageResponseBookingDetailResponse(data = page))

            assertEquals(ActiveBookingOutcome.Found(live), repository.findActiveBooking())
        }

    @Test
    fun findActiveBookingDistinguishesNoneFromAFailedLookup() =
        runTest {
            val page = PageResponseBookingDetailResponse(content = listOf(BookingDetailResponse(status = Status.CANCELLED)))
            coEvery { bookingApi.history(0, any(), any()) } returns
                Response.success(ApiResponsePageResponseBookingDetailResponse(data = page))
            assertEquals(ActiveBookingOutcome.None, repository.findActiveBooking())

            coEvery { bookingApi.history(0, any(), any()) } throws java.io.IOException("offline")
            assertEquals(ActiveBookingOutcome.Failed, repository.findActiveBooking())

            coEvery { bookingApi.history(0, any(), any()) } returns error(500)
            assertEquals(ActiveBookingOutcome.Failed, repository.findActiveBooking())
        }
}
