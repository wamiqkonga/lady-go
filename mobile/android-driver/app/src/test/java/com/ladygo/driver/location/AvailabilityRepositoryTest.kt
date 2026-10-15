package com.ladygo.driver.location

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseDriverAvailabilityResponse
import com.ladygo.driver.api.generated.models.DriverAvailabilityRequest
import com.ladygo.driver.api.generated.models.DriverAvailabilityResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AvailabilityRepositoryTest {
    private val driverApi = mockk<DriverControllerApi>()
    private val repository = AvailabilityRepository(driverApi)

    @Test
    fun goOnlineSendsTheRealOnlineRequestAndReportsSuccess() =
        runTest {
            coEvery { driverApi.setAvailability(DriverAvailabilityRequest(DriverAvailabilityRequest.Status.ONLINE)) } returns
                Response.success(
                    ApiResponseDriverAvailabilityResponse(data = DriverAvailabilityResponse(DriverAvailabilityResponse.Status.ONLINE)),
                )

            val outcome = repository.goOnline()

            assertTrue(outcome is SetAvailabilityOutcome.Success)
        }

    @Test
    fun goOfflineSendsTheRealOfflineRequest() =
        runTest {
            coEvery { driverApi.setAvailability(DriverAvailabilityRequest(DriverAvailabilityRequest.Status.OFFLINE)) } returns
                Response.success(
                    ApiResponseDriverAvailabilityResponse(data = DriverAvailabilityResponse(DriverAvailabilityResponse.Status.OFFLINE)),
                )

            val outcome = repository.goOffline()

            assertTrue(outcome is SetAvailabilityOutcome.Success)
        }

    /** DriverAvailabilityService.setAvailability: going online can fail for reasons that have
     *  nothing to do with permissions - no approved vehicle, driver not approved, a pending
     *  re-verification - and the real message must reach the driver, not a generic one. */
    @Test
    fun goOnlineSurfacesTheRealServerRejectionMessage() =
        runTest {
            coEvery { driverApi.setAvailability(any()) } returns
                errorResponse("""{"error":{"code":"FORBIDDEN","message":"No approved vehicle on file."}}""")

            val outcome = repository.goOnline()

            assertTrue(outcome is SetAvailabilityOutcome.Failed)
            assertEquals("No approved vehicle on file.", (outcome as SetAvailabilityOutcome.Failed).message)
        }

    /** Confirmed from DriverAvailabilityService: going OFFLINE is rejected too while the driver is
     *  actually BUSY/ON_RIDE/SUSPENDED - this is not an ONLINE-only restriction. */
    @Test
    fun goOfflineSurfacesTheRealServerRejectionMessageWhileBusy() =
        runTest {
            coEvery { driverApi.setAvailability(any()) } returns
                errorResponse("""{"error":{"code":"FORBIDDEN","message":"Availability cannot be changed directly while BUSY."}}""")

            val outcome = repository.goOffline()

            assertTrue(outcome is SetAvailabilityOutcome.Failed)
            assertEquals(
                "Availability cannot be changed directly while BUSY.",
                (outcome as SetAvailabilityOutcome.Failed).message,
            )
        }

    @Test
    fun aNetworkFailureIsReportedAsFailedNotAsAnException() =
        runTest {
            coEvery { driverApi.setAvailability(any()) } throws java.io.IOException("no network")

            val outcome = repository.goOnline()

            assertTrue(outcome is SetAvailabilityOutcome.Failed)
        }

    private fun errorResponse(body: String): Response<ApiResponseDriverAvailabilityResponse> =
        Response.error(403, body.toResponseBody("application/json".toMediaType()))
}
