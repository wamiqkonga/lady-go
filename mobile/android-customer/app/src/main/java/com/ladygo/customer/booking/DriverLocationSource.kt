package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.BookingControllerApi
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where the rider's own driver is, as the server reports it (docs/product/map-design.md section 5). */
sealed interface DriverLocationOutcome {
    data class Live(
        val point: GeoPoint,
    ) : DriverLocationOutcome

    /** Nothing to show: the ride is not in a state where the driver may be shown, or the driver has not reported a
     *  recent position. The map drops the car rather than leave a stale one. */
    data object NotLive : DriverLocationOutcome

    /** The server could not be reached. The caller keeps the last position until the next poll. */
    data object Failed : DriverLocationOutcome
}

/** An interface so the tracking flow is testable without a network. */
interface DriverLocationSource {
    suspend fun locate(bookingId: UUID): DriverLocationOutcome
}

@Singleton
class ServerDriverLocationSource
    @Inject
    constructor(
        private val api: BookingControllerApi,
    ) : DriverLocationSource {
        override suspend fun locate(bookingId: UUID): DriverLocationOutcome {
            val response =
                try {
                    api.driverLocation(bookingId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return DriverLocationOutcome.Failed
                }
            if (!response.isSuccessful) return DriverLocationOutcome.Failed
            val body = response.body()?.data ?: return DriverLocationOutcome.Failed
            val latitude = body.latitude
            val longitude = body.longitude
            return if (body.live == true && latitude != null && longitude != null) {
                DriverLocationOutcome.Live(GeoPoint(latitude, longitude))
            } else {
                DriverLocationOutcome.NotLive
            }
        }
    }
