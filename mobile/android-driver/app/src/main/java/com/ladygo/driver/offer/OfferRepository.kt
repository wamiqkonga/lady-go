package com.ladygo.driver.offer

import com.ladygo.driver.api.generated.apis.DriverOfferControllerApi
import com.ladygo.driver.api.generated.models.BookingDetailResponse
import com.ladygo.driver.api.generated.models.OfferRejectRequest
import com.ladygo.driver.api.generated.models.OfferResponse
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AcceptOfferOutcome {
    data class Accepted(
        val booking: BookingDetailResponse,
    ) : AcceptOfferOutcome

    /** A real server rejection, including the already-claimed/expired/cancelled cases (design doc
     *  §1/§3 rule 7) - `MatchingService.accept()` throws a `ValidationException` with a real message
     *  for exactly these ("This offer is no longer available."/"This offer has expired."). Terminal:
     *  the offer really is gone, never retryable. */
    data class Rejected(
        val message: String,
    ) : AcceptOfferOutcome

    /** Couldn't even reach the server - the offer may still genuinely be live, unlike [Rejected].
     *  Retryable: the caller should let the driver try again, not treat this as the offer being
     *  gone (design doc §3 rule 1's general "surface a retryable error" rule, applied here). */
    data class NetworkFailure(
        val message: String,
    ) : AcceptOfferOutcome
}

sealed interface DeclineOfferOutcome {
    data object Declined : DeclineOfferOutcome

    data class Rejected(
        val message: String,
    ) : DeclineOfferOutcome

    data class NetworkFailure(
        val message: String,
    ) : DeclineOfferOutcome
}

sealed interface CurrentOfferOutcome {
    data class Present(
        val offer: OfferResponse,
    ) : CurrentOfferOutcome

    data object None : CurrentOfferOutcome

    data class Failed(
        val message: String,
    ) : CurrentOfferOutcome
}

/**
 * Accept/decline against the existing `DriverOfferControllerApi` endpoints, plus the HTTP polling
 * fallback (`GET .../offers/current`) used for reconciliation on connect/resume - the backend's own
 * documented "in case the WebSocket push never arrived" path (design doc §2.2/§3 rule 4).
 */
@Singleton
class OfferRepository
    @Inject
    constructor(
        private val offerApi: DriverOfferControllerApi,
    ) {
        suspend fun accept(offerId: UUID): AcceptOfferOutcome {
            val response =
                runCatching { offerApi.accept(offerId) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return AcceptOfferOutcome.NetworkFailure("Could not reach the server. Check your connection and try again.")
                }
            if (!response.isSuccessful) {
                // A 5xx is the server's own failure, not a verdict that the offer is gone - only a
                // real 4xx rejection (already claimed/expired/cancelled) is terminal (fresh-context
                // review finding: an accept-outcome mapping mirroring TokenRefresher's own fix).
                return if (isServerFailure(response)) {
                    AcceptOfferOutcome.NetworkFailure(errorMessageOrDefault(response, "Could not accept this offer."))
                } else {
                    AcceptOfferOutcome.Rejected(errorMessageOrDefault(response, "Could not accept this offer."))
                }
            }
            val booking =
                response.body()?.data
                    ?: return AcceptOfferOutcome.NetworkFailure("Unexpected response from the server.")
            return AcceptOfferOutcome.Accepted(booking)
        }

        suspend fun decline(
            offerId: UUID,
            reason: String? = null,
        ): DeclineOfferOutcome {
            val response =
                runCatching { offerApi.reject(offerId, reason?.let { OfferRejectRequest(reason = it) }) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return DeclineOfferOutcome.NetworkFailure("Could not reach the server. Check your connection and try again.")
                }
            if (response.isSuccessful) return DeclineOfferOutcome.Declined
            return if (isServerFailure(response)) {
                DeclineOfferOutcome.NetworkFailure(errorMessageOrDefault(response, "Could not decline this offer."))
            } else {
                DeclineOfferOutcome.Rejected(errorMessageOrDefault(response, "Could not decline this offer."))
            }
        }

        private fun isServerFailure(response: Response<*>): Boolean = response.code() >= HTTP_SERVER_ERROR_FLOOR

        suspend fun currentOffer(): CurrentOfferOutcome {
            val response =
                runCatching { offerApi.currentOffer() }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return CurrentOfferOutcome.Failed("Could not reach the server. Check your connection and try again.")
                }
            if (response.code() == HTTP_NOT_FOUND) return CurrentOfferOutcome.None
            if (!response.isSuccessful) {
                return CurrentOfferOutcome.Failed(errorMessageOrDefault(response, "Could not check for a current offer."))
            }
            val offer = response.body()?.data ?: return CurrentOfferOutcome.None
            return CurrentOfferOutcome.Present(offer)
        }

        private companion object {
            const val HTTP_NOT_FOUND = 404
            const val HTTP_SERVER_ERROR_FLOOR = 500
        }
    }
