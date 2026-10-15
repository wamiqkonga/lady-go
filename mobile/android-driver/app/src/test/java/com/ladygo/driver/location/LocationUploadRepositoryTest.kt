package com.ladygo.driver.location

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.DriverLocationRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.Instant

class LocationUploadRepositoryTest {
    private val driverApi = mockk<DriverControllerApi>()
    private val repository = LocationUploadRepository(driverApi)
    private val now = Instant.parse("2026-10-14T12:00:00Z")

    @Test
    fun aSuccessfulUpdateIsReportedAsSent() =
        runTest {
            coEvery { driverApi.updateLocation(any()) } returns Response.success(ApiResponseVoid())

            val outcome = repository.send(12.9, 77.6, 8.0, now)

            assertTrue(outcome is LocationSendOutcome.Sent)
        }

    @Test
    fun sendsTheRequestWithTheGivenFields() =
        runTest {
            coEvery { driverApi.updateLocation(any()) } returns Response.success(ApiResponseVoid())

            repository.send(12.9, 77.6, 8.0, now)

            coVerify {
                driverApi.updateLocation(
                    DriverLocationRequest(latitude = 12.9, longitude = 77.6, recordedAt = now.toString(), accuracyMeters = 8.0),
                )
            }
        }

    /** DriverLocationService.updateLocation: "Driver is OFFLINE." - the real reconciliation signal
     *  (see LocationUploadRepository's own doc comment) - must be recognised as that specific
     *  outcome, not just lumped in with every other validation failure. */
    @Test
    fun recognisesTheServersDriverIsOfflineRejection() =
        runTest {
            coEvery { driverApi.updateLocation(any()) } returns
                errorResponse("""{"error":{"code":"VALIDATION_FAILED","message":"Driver is OFFLINE."}}""")

            val outcome = repository.send(12.9, 77.6, 8.0, now)

            assertTrue(outcome is LocationSendOutcome.DriverSetOffline)
        }

    /** Any OTHER validation failure on this same endpoint (rate limit, impossible speed, missing
     *  accuracy) carries the identical error code - only the exact message distinguishes the
     *  offline case, so a different message must NOT be misread as it. */
    @Test
    fun aDifferentValidationFailureIsNotMisreadAsDriverSetOffline() =
        runTest {
            coEvery { driverApi.updateLocation(any()) } returns
                errorResponse("""{"error":{"code":"VALIDATION_FAILED","message":"Location update implies an impossible speed."}}""")

            val outcome = repository.send(12.9, 77.6, 8.0, now)

            assertTrue(outcome is LocationSendOutcome.Failed)
            assertTrue((outcome as LocationSendOutcome.Failed).message.contains("impossible speed"))
        }

    @Test
    fun aNetworkFailureIsReportedAsFailedNotAsAnException() =
        runTest {
            coEvery { driverApi.updateLocation(any()) } throws java.io.IOException("no network")

            val outcome = repository.send(12.9, 77.6, 8.0, now)

            assertTrue(outcome is LocationSendOutcome.Failed)
        }

    @Test
    fun a429IsReportedAsAnOrdinaryFailure() =
        runTest {
            coEvery { driverApi.updateLocation(any()) } returns
                errorResponse("""{"error":{"code":"RATE_LIMITED","message":"Too many requests."}}""", code = 429)

            val outcome = repository.send(12.9, 77.6, 8.0, now)

            assertTrue(outcome is LocationSendOutcome.Failed)
        }

    private fun errorResponse(
        body: String,
        code: Int = 400,
    ): Response<ApiResponseVoid> = Response.error(code, body.toResponseBody("application/json".toMediaType()))
}
