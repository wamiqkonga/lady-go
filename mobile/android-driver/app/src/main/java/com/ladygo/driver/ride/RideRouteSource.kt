package com.ladygo.driver.ride

import androidx.lifecycle.ViewModel
import com.google.android.gms.maps.model.LatLng
import com.ladygo.driver.api.generated.apis.DriverBookingControllerApi
import com.ladygo.driver.map.decodePolyline
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The road for one leg of her ride (to the pickup, or on to the destination). An interface so the map is testable. */
interface RideRouteSource {
    /** The points of the road from [from], or an empty list when the server has none (the map then draws a straight line). */
    suspend fun leg(
        bookingId: UUID,
        from: LatLng,
        toDestination: Boolean,
    ): List<LatLng>
}

@Singleton
class ServerRideRouteSource
    @Inject
    constructor(
        private val api: DriverBookingControllerApi,
    ) : RideRouteSource {
        override suspend fun leg(
            bookingId: UUID,
            from: LatLng,
            toDestination: Boolean,
        ): List<LatLng> {
            val response =
                try {
                    api.route(bookingId, from.latitude, from.longitude, if (toDestination) "DESTINATION" else "PICKUP")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return emptyList()
                }
            return response
                .body()
                ?.data
                ?.polyline
                ?.let(::decodePolyline)
                .orEmpty()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RideRouteBindsModule {
    @Binds
    @Singleton
    abstract fun bindRideRouteSource(impl: ServerRideRouteSource): RideRouteSource
}

/** Gives the ride map its route without the map knowing about the network. */
@HiltViewModel
class RideRouteViewModel
    @Inject
    constructor(
        private val source: RideRouteSource,
    ) : ViewModel() {
        suspend fun leg(
            bookingId: UUID,
            from: LatLng,
            toDestination: Boolean,
        ): List<LatLng> = source.leg(bookingId, from, toDestination)
    }
