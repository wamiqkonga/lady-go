package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.BookingControllerApi
import com.ladygo.customer.api.generated.models.ApiResponseDriverLocationForRiderResponse
import com.ladygo.customer.api.generated.models.DriverLocationForRiderResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.UUID

class DriverLocationSourceTest {
    private val api = mockk<BookingControllerApi>()
    private val source = ServerDriverLocationSource(api)
    private val id = UUID.randomUUID()

    private fun ok(body: DriverLocationForRiderResponse) =
        Response.success(ApiResponseDriverLocationForRiderResponse(success = true, data = body))

    @Test
    fun aLivePositionIsReturnedAsAPoint() =
        runTest {
            coEvery { api.driverLocation(id) } returns ok(DriverLocationForRiderResponse(live = true, latitude = 34.08, longitude = 74.80))

            assertEquals(DriverLocationOutcome.Live(GeoPoint(34.08, 74.80)), source.locate(id))
        }

    @Test
    fun aNotLiveAnswerNeverCarriesCoordinates() =
        runTest {
            coEvery { api.driverLocation(id) } returns ok(DriverLocationForRiderResponse(live = false))
            assertEquals(DriverLocationOutcome.NotLive, source.locate(id))

            // Even a malformed "live" answer with a missing coordinate must not become a point at (0, 0).
            coEvery { api.driverLocation(id) } returns ok(DriverLocationForRiderResponse(live = true, latitude = 34.08))
            assertEquals(DriverLocationOutcome.NotLive, source.locate(id))
        }

    @Test
    fun aServerErrorOrAnOfflinePhoneIsFailedNotACrash() =
        runTest {
            coEvery { api.driverLocation(id) } returns Response.error(503, "".toResponseBody("application/json".toMediaType()))
            assertEquals(DriverLocationOutcome.Failed, source.locate(id))

            coEvery { api.driverLocation(id) } throws IOException("offline")
            assertEquals(DriverLocationOutcome.Failed, source.locate(id))
        }
}
