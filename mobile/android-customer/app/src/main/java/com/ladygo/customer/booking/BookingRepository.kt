package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.apis.BookingControllerApi
import com.ladygo.customer.api.generated.infrastructure.Serializer
import com.ladygo.customer.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.customer.api.generated.models.ApiResponseFareEstimateResponse
import com.ladygo.customer.api.generated.models.BookingDetailResponse
import com.ladygo.customer.api.generated.models.BookingRequest
import com.ladygo.customer.api.generated.models.CancelBookingRequest
import com.ladygo.customer.api.generated.models.FareEstimateRequest
import com.ladygo.customer.api.generated.models.FareEstimateResponse
import com.ladygo.customer.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EstimateOutcome {
    data class Success(
        val estimate: FareEstimateResponse,
    ) : EstimateOutcome

    /** A real 4xx verdict: outside the service area, over the distance limit, no pricing rule... */
    data class Rejected(
        val message: String,
    ) : EstimateOutcome

    data class NetworkFailure(
        val message: String,
    ) : EstimateOutcome
}

sealed interface SelfieUploadOutcome {
    data class Uploaded(
        val selfieId: UUID,
    ) : SelfieUploadOutcome

    data class Rejected(
        val message: String,
    ) : SelfieUploadOutcome

    data class NetworkFailure(
        val message: String,
    ) : SelfieUploadOutcome
}

sealed interface CreateBookingOutcome {
    data class Created(
        val booking: BookingDetailResponse,
    ) : CreateBookingOutcome

    /** 422 FARE_CHANGED: the price moved beyond tolerance since the estimate. Carries the fresh quote
     *  to re-confirm - the app never silently accepts a new price (docs/api/booking.md section 2). */
    data class FareChanged(
        val freshEstimate: FareEstimateResponse,
        val message: String,
    ) : CreateBookingOutcome

    data class Rejected(
        val message: String,
    ) : CreateBookingOutcome

    /** The request may or may not have landed. Retrying with the **same** idempotency key is safe. */
    data class NetworkFailure(
        val message: String,
    ) : CreateBookingOutcome
}

sealed interface BookingFetchOutcome {
    data class Found(
        val booking: BookingDetailResponse,
    ) : BookingFetchOutcome

    /** 404: not this rider's booking (or gone). */
    data object NotFound : BookingFetchOutcome

    data class NetworkFailure(
        val message: String,
    ) : BookingFetchOutcome
}

sealed interface CancelOutcome {
    data class Cancelled(
        val booking: BookingDetailResponse,
    ) : CancelOutcome

    data class Rejected(
        val message: String,
    ) : CancelOutcome

    data class NetworkFailure(
        val message: String,
    ) : CancelOutcome
}

/** What the server says cancelling right now would cost. [Failed] means "could not find out", never "free". */
sealed interface CancelPreviewOutcome {
    data class Ready(
        val fee: Long,
        val currency: String?,
        /** Epoch millis until which cancelling is free, when the free window is still open. */
        val freeUntilMillis: Long?,
    ) : CancelPreviewOutcome

    data object NotCancellable : CancelPreviewOutcome

    data object Failed : CancelPreviewOutcome
}

/** Result of asking the server for the rider's in-progress ride. [Failed] must never be read as "none". */
sealed interface ActiveBookingOutcome {
    data class Found(
        val booking: BookingDetailResponse,
    ) : ActiveBookingOutcome

    data object None : ActiveBookingOutcome

    data object Failed : ActiveBookingOutcome
}

private const val NETWORK_ERROR_MESSAGE = "Could not reach the server. Check your connection and try again."
private const val HTTP_SERVER_ERROR_FLOOR = 500
private const val HTTP_NOT_FOUND = 404
private const val ERROR_FARE_CHANGED = "FARE_CHANGED"
private const val ACTIVE_LOOKUP_PAGE_SIZE = 10

/** Statuses that no longer block a new booking. `PAYMENT_PENDING` is here on purpose: for a cash ride the booking
 *  stays there for the whole dispute window (default 60 min) after the driver confirmed the cash - the trip is
 *  over and the rider must be free to book again. */
private val NOT_ACTIVE_STATUSES =
    setOf(
        BookingDetailResponse.Status.PAYMENT_PENDING,
        BookingDetailResponse.Status.COMPLETED,
        BookingDetailResponse.Status.CANCELLED,
    )

/** Estimate / selfie / create / fetch / cancel against the booking API (docs/api/booking.md). Each
 *  returns the server's verdict; nothing here decides a fare, a status or eligibility locally. */
@Singleton
class BookingRepository
    @Inject
    constructor(
        private val bookingApi: BookingControllerApi,
        private val selfieApi: RideSelfieApi,
    ) {
        suspend fun estimate(
            pickup: GeoPoint,
            destination: GeoPoint,
        ): EstimateOutcome {
            val request =
                FareEstimateRequest(
                    pickupLatitude = pickup.latitude,
                    pickupLongitude = pickup.longitude,
                    destinationLatitude = destination.latitude,
                    destinationLongitude = destination.longitude,
                    vehicleType = FareEstimateRequest.VehicleType.CAB,
                )
            val response = send { bookingApi.estimate(request) } ?: return EstimateOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
            if (!response.isSuccessful) {
                val message = errorMessageOrDefault(response, "Could not get a fare estimate.")
                return if (isServerFailure(response)) EstimateOutcome.NetworkFailure(message) else EstimateOutcome.Rejected(message)
            }
            val estimate = response.body()?.data ?: return EstimateOutcome.NetworkFailure("Unexpected response from the server.")
            return EstimateOutcome.Success(estimate)
        }

        suspend fun uploadSelfie(jpeg: ByteArray): SelfieUploadOutcome {
            val part =
                MultipartBody.Part.createFormData("file", "selfie.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            val response = send { selfieApi.upload(part) } ?: return SelfieUploadOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
            if (!response.isSuccessful) {
                val message = errorMessageOrDefault(response, "Could not upload your photo.")
                return if (isServerFailure(response)) SelfieUploadOutcome.NetworkFailure(message) else SelfieUploadOutcome.Rejected(message)
            }
            val id =
                response.body()?.data?.rideSelfieId ?: return SelfieUploadOutcome.NetworkFailure("Unexpected response from the server.")
            return SelfieUploadOutcome.Uploaded(id)
        }

        suspend fun create(
            idempotencyKey: String,
            request: BookingRequest,
        ): CreateBookingOutcome {
            val response =
                send { bookingApi.create(idempotencyKey, request) }
                    ?: return CreateBookingOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
            if (response.isSuccessful) {
                val booking = response.body()?.data ?: return CreateBookingOutcome.NetworkFailure("Unexpected response from the server.")
                return CreateBookingOutcome.Created(booking)
            }
            // The error body can be read only once, so read it once and parse both the error and, for
            // FARE_CHANGED, the fresh quote carried in `data` from that single string.
            val envelope = parseEnvelope(response.errorBody()?.string())
            val message = envelope?.error?.message ?: "Could not book your ride."
            if (envelope?.error?.code == ERROR_FARE_CHANGED) {
                envelope.data?.let { fresh -> return CreateBookingOutcome.FareChanged(fresh, message) }
            }
            return if (isServerFailure(response)) CreateBookingOutcome.NetworkFailure(message) else CreateBookingOutcome.Rejected(message)
        }

        suspend fun fetch(bookingId: UUID): BookingFetchOutcome {
            val response = send { bookingApi.get1(bookingId) } ?: return BookingFetchOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
            if (response.code() == HTTP_NOT_FOUND) return BookingFetchOutcome.NotFound
            if (!response.isSuccessful) {
                return BookingFetchOutcome.NetworkFailure(errorMessageOrDefault(response, "Could not load your ride."))
            }
            val booking = response.body()?.data ?: return BookingFetchOutcome.NetworkFailure("Unexpected response from the server.")
            return BookingFetchOutcome.Found(booking)
        }

        suspend fun cancel(
            bookingId: UUID,
            reason: String,
        ): CancelOutcome = cancelWith("Could not cancel your ride.") { bookingApi.cancel(bookingId, CancelBookingRequest(reason = reason)) }

        /** The fee-free cancel for a rider who feels unsafe (the server applies its own abuse caps and flags it for review). */
        suspend fun cancelForSafety(
            bookingId: UUID,
            reason: String,
        ): CancelOutcome =
            cancelWith("Could not cancel your ride.") {
                bookingApi.cancelForSafetyConcern(bookingId, CancelBookingRequest(reason = reason))
            }

        /** Tells the rider what cancelling would cost before she confirms. */
        suspend fun cancellationPreview(bookingId: UUID): CancelPreviewOutcome {
            val response = send { bookingApi.cancellationPreview(bookingId) } ?: return CancelPreviewOutcome.Failed
            if (!response.isSuccessful) return CancelPreviewOutcome.Failed
            val data = response.body()?.data ?: return CancelPreviewOutcome.Failed
            if (data.cancellable == false) return CancelPreviewOutcome.NotCancellable
            return CancelPreviewOutcome.Ready(
                fee = data.fee ?: 0L,
                currency = data.currency,
                freeUntilMillis = data.freeUntil?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }

        private suspend fun cancelWith(
            fallbackMessage: String,
            call: suspend () -> Response<ApiResponseBookingDetailResponse>,
        ): CancelOutcome {
            val response = send(call) ?: return CancelOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
            if (response.isSuccessful) {
                val booking = response.body()?.data ?: return CancelOutcome.NetworkFailure("Unexpected response from the server.")
                return CancelOutcome.Cancelled(booking)
            }
            val message = errorMessageOrDefault(response, fallbackMessage)
            return if (isServerFailure(response)) CancelOutcome.NetworkFailure(message) else CancelOutcome.Rejected(message)
        }

        /** The rider's most recent booking that is still in progress, if any - how the app finds a ride
         *  after being killed (there is no "current booking" endpoint; the server stays the source of truth). */
        suspend fun findActiveBooking(): ActiveBookingOutcome {
            val response = send { bookingApi.history(page = 0, size = ACTIVE_LOOKUP_PAGE_SIZE) } ?: return ActiveBookingOutcome.Failed
            if (!response.isSuccessful) return ActiveBookingOutcome.Failed
            val active =
                response
                    .body()
                    ?.data
                    ?.content
                    ?.firstOrNull { it.status != null && it.status !in NOT_ACTIVE_STATUSES }
            return if (active == null) ActiveBookingOutcome.None else ActiveBookingOutcome.Found(active)
        }

        private fun isServerFailure(response: Response<*>): Boolean = response.code() >= HTTP_SERVER_ERROR_FLOOR

        private fun parseEnvelope(raw: String?): ApiResponseFareEstimateResponse? =
            runCatching { raw?.let { Serializer.moshi.adapter(ApiResponseFareEstimateResponse::class.java).fromJson(it) } }.getOrNull()

        /** `null` when the request could not complete (IO failure); cancellation propagates. */
        private suspend fun <T> send(request: suspend () -> Response<T>): Response<T>? =
            runCatching { request() }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
    }
