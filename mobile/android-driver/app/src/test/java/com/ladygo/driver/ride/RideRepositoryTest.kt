package com.ladygo.driver.ride

import com.ladygo.driver.api.generated.apis.DriverBookingControllerApi
import com.ladygo.driver.api.generated.apis.RideLifecycleControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.EndRideEarlyRequest
import com.ladygo.driver.api.generated.models.StartRideRequest
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

class RideRepositoryTest {
    private val rideApi = mockk<RideLifecycleControllerApi>()
    private val bookingApi = mockk<DriverBookingControllerApi>()
    private val repository = RideRepository(rideApi, bookingApi)
    private val id = UUID.randomUUID()
    private val booking = BookingDetailResponse(id = id, status = BookingDetailResponse.Status.DRIVER_ARRIVED)

    private fun error(
        body: String,
        code: Int = 400,
    ): Response<ApiResponseBookingDetailResponse> = Response.error(code, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun successReturnsTheServersBooking() =
        runTest {
            coEvery { rideApi.arrived(id) } returns Response.success(ApiResponseBookingDetailResponse(data = booking))

            assertEquals(RideActionOutcome.Success(booking), repository.arrived(id))
        }

    @Test
    fun startSendsThePinInTheRequestBody() =
        runTest {
            coEvery { rideApi.start(id, StartRideRequest(pin = "1234")) } returns
                Response.success(ApiResponseBookingDetailResponse(data = booking))

            assertTrue(repository.start(id, "1234") is RideActionOutcome.Success)
        }

    @Test
    fun endEarlySendsReasonAndSafetyFlag() =
        runTest {
            coEvery { rideApi.endEarly(id, EndRideEarlyRequest(reason = "unwell", safetyConcern = true)) } returns
                Response.success(ApiResponseBookingDetailResponse(data = booking))

            assertTrue(repository.endEarly(id, "unwell", true) is RideActionOutcome.Success)
        }

    @Test
    fun a4xxIsARejectionCarryingTheServersRealMessage() =
        runTest {
            coEvery { rideApi.arrived(id) } returns
                error("""{"error":{"code":"VALIDATION","message":"You are too far from the pickup point."}}""")

            val outcome = repository.arrived(id)

            assertEquals(RideActionOutcome.Rejected("You are too far from the pickup point."), outcome)
        }

    @Test
    fun a5xxIsRetryableNotARejection() =
        runTest {
            coEvery { rideApi.complete(id) } returns error("""{"error":{"code":"INTERNAL","message":"boom"}}""", code = 503)

            assertTrue(repository.complete(id) is RideActionOutcome.NetworkFailure)
        }

    @Test
    fun anIoErrorIsRetryableNotARejection() =
        runTest {
            coEvery { bookingApi.get(id) } throws java.io.IOException("no network")

            assertTrue(repository.fetch(id) is RideActionOutcome.NetworkFailure)
        }

    @Test
    fun aSuccessWithNoBodyDataIsNotTreatedAsSuccess() =
        runTest {
            coEvery { rideApi.confirmCashCollected(id) } returns Response.success(ApiResponseBookingDetailResponse())

            assertTrue(repository.confirmCashCollected(id) is RideActionOutcome.NetworkFailure)
        }
}
