package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.MapsControllerApi
import com.ladygo.customer.api.generated.models.ApiResponseReverseGeocodeResponse
import com.ladygo.customer.api.generated.models.ReverseGeocodeResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class PinLookupTest {
    private val api = mockk<MapsControllerApi>()
    private val lookup = ServerPinLookup(api)
    private val point = GeoPoint(34.08, 74.80)

    private fun ok(response: ReverseGeocodeResponse) = Response.success(ApiResponseReverseGeocodeResponse(success = true, data = response))

    @Test
    fun aServedPinReturnsTheAddressAndTheVerdict() =
        runTest {
            coEvery { api.reverseGeocode(any()) } returns
                ok(
                    ReverseGeocodeResponse(
                        latitude = 34.08,
                        longitude = 74.80,
                        address = "Lal Chowk",
                        inServiceArea = true,
                        cityName = "Srinagar",
                    ),
                )

            assertEquals(PinLookupOutcome.Resolved("Lal Chowk", true, "Srinagar"), lookup.lookup(point))
        }

    @Test
    fun aPinOutsideEveryCityIsReportedAsOutOfArea() =
        runTest {
            coEvery { api.reverseGeocode(any()) } returns
                ok(ReverseGeocodeResponse(latitude = 10.0, longitude = 10.0, address = "Somewhere", inServiceArea = false))

            assertEquals(PinLookupOutcome.Resolved("Somewhere", false, null), lookup.lookup(point))
        }

    @Test
    fun aServerErrorOrAnOfflineDeviceIsUnavailableNotACrash() =
        runTest {
            coEvery { api.reverseGeocode(any()) } returns Response.error(503, "".toResponseBody("application/json".toMediaType()))
            assertEquals(PinLookupOutcome.Unavailable, lookup.lookup(point))

            coEvery { api.reverseGeocode(any()) } throws IOException("offline")
            assertEquals(PinLookupOutcome.Unavailable, lookup.lookup(point))
        }

    @Test
    fun anEmptyAddressIsTreatedAsUnavailable() =
        runTest {
            coEvery { api.reverseGeocode(any()) } returns ok(ReverseGeocodeResponse(address = " ", inServiceArea = true))

            assertEquals(PinLookupOutcome.Unavailable, lookup.lookup(point))
        }
}
