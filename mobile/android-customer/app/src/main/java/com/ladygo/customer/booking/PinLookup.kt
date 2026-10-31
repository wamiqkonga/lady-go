package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.MapsControllerApi
import com.ladygo.customer.api.generated.models.ReverseGeocodeRequest
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** What the server says about a dropped pin (docs/product/map-design.md section 5.1). */
sealed interface PinLookupOutcome {
    data class Resolved(
        val address: String,
        /** `false` means Lady Go does not serve this point, so booking from or to it will be refused. */
        val inServiceArea: Boolean,
        val cityName: String?,
    ) : PinLookupOutcome

    /** The server could not answer (offline, provider outage, rate limit). The caller falls back to the
     *  device geocoder so the pin still works; the server re-checks the area when the fare is quoted. */
    data object Unavailable : PinLookupOutcome
}

/** The address behind a map pin, and whether the point is inside a served city. An interface so the
 *  booking flow is testable without a network. */
interface PinLookup {
    suspend fun lookup(point: GeoPoint): PinLookupOutcome
}

@Singleton
class ServerPinLookup
    @Inject
    constructor(
        private val api: MapsControllerApi,
    ) : PinLookup {
        override suspend fun lookup(point: GeoPoint): PinLookupOutcome {
            val response =
                try {
                    api.reverseGeocode(ReverseGeocodeRequest(latitude = point.latitude, longitude = point.longitude))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return PinLookupOutcome.Unavailable
                }
            val body = response.body()?.data
            val address = body?.address
            if (!response.isSuccessful || body == null || address.isNullOrBlank()) return PinLookupOutcome.Unavailable
            return PinLookupOutcome.Resolved(address, body.inServiceArea ?: false, body.cityName)
        }
    }
