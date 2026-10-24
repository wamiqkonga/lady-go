package com.ladygo.driver.ride

import com.ladygo.driver.api.generated.apis.DriverBookingControllerApi
import com.ladygo.driver.api.generated.apis.RideLifecycleControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.CancelBookingRequest
import com.ladygo.driver.api.generated.models.EndRideEarlyRequest
import com.ladygo.driver.api.generated.models.StartRideRequest
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RideActionOutcome {
    data class Success(
        val booking: BookingDetailResponse,
    ) : RideActionOutcome

    /** A real 4xx verdict (too far from pickup, stale GPS, wrong PIN, PIN lockout, wrong state...) -
     *  the server's own message, shown as-is. The action did not happen. */
    data class Rejected(
        val message: String,
        val httpCode: Int = HTTP_BAD_REQUEST,
    ) : RideActionOutcome

    /** Couldn't reach the server, or it failed (5xx): the action may or may not have happened, so the
     *  caller must not assume either - it re-fetches the booking (design doc §3 rule 5). */
    data class NetworkFailure(
        val message: String,
    ) : RideActionOutcome
}

private const val NETWORK_ERROR_MESSAGE = "Could not reach the server. Check your connection and try again."
private const val HTTP_SERVER_ERROR_FLOOR = 500
private const val HTTP_BAD_REQUEST = 400

/**
 * Every driver-side ride-lifecycle call (`docs/api/ride-lifecycle.md` §2) plus the booking re-fetch
 * used for reconciliation. Each returns the server's resulting [BookingDetailResponse]; none of them
 * decides anything locally.
 */
@Singleton
class RideRepository
    @Inject
    constructor(
        private val rideApi: RideLifecycleControllerApi,
        private val bookingApi: DriverBookingControllerApi,
    ) {
        suspend fun fetch(bookingId: UUID): RideActionOutcome = call("Could not load this ride.") { bookingApi.get(bookingId) }

        suspend fun arriving(bookingId: UUID): RideActionOutcome =
            call("Could not start heading to pickup.") { rideApi.arriving(bookingId) }

        suspend fun arrived(bookingId: UUID): RideActionOutcome = call("Could not confirm arrival.") { rideApi.arrived(bookingId) }

        suspend fun start(
            bookingId: UUID,
            pin: String,
        ): RideActionOutcome = call("Could not start the ride.") { rideApi.start(bookingId, StartRideRequest(pin = pin)) }

        suspend fun complete(bookingId: UUID): RideActionOutcome = call("Could not complete the ride.") { rideApi.complete(bookingId) }

        suspend fun endEarly(
            bookingId: UUID,
            reason: String,
            safetyConcern: Boolean,
        ): RideActionOutcome =
            call("Could not end the ride.") {
                rideApi.endEarly(bookingId, EndRideEarlyRequest(reason = reason, safetyConcern = safetyConcern))
            }

        /** Cancels without penalty before the trip starts and files an eligibility report server-side. */
        suspend fun cancelForEligibilityConcern(
            bookingId: UUID,
            reason: String,
        ): RideActionOutcome =
            call("Could not cancel the ride.") {
                bookingApi.cancelForEligibilityConcern(bookingId, CancelBookingRequest(reason = reason))
            }

        suspend fun confirmCashCollected(bookingId: UUID): RideActionOutcome =
            call("Could not confirm the cash payment.") { rideApi.confirmCashCollected(bookingId) }

        private suspend fun call(
            defaultError: String,
            request: suspend () -> Response<ApiResponseBookingDetailResponse>,
        ): RideActionOutcome {
            val response =
                runCatching { request() }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return RideActionOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
                }
            if (!response.isSuccessful) {
                val message = errorMessageOrDefault(response, defaultError)
                return if (response.code() >= HTTP_SERVER_ERROR_FLOOR) {
                    RideActionOutcome.NetworkFailure(message)
                } else {
                    RideActionOutcome.Rejected(message, response.code())
                }
            }
            val booking =
                response.body()?.data
                    ?: return RideActionOutcome.NetworkFailure("Unexpected response from the server.")
            return RideActionOutcome.Success(booking)
        }
    }
