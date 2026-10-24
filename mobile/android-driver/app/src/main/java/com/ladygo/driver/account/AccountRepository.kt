package com.ladygo.driver.account

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.apis.RatingControllerApi
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only account data for the Me tab and Help: the rating summary, document and vehicle review status, and the
 * driver's own id (used only to make an anonymised reference). Each section loads on its own, so one failing
 * endpoint does not hide the others.
 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val ratingApi: RatingControllerApi,
        private val driverApi: DriverControllerApi,
    ) {
        suspend fun fetchOverview(): AccountOverview =
            coroutineScope {
                val rating = async { load("Could not load your rating.", { ratingApi.getMyRatingSummary() }) { it.data?.toSummary() } }
                val documents =
                    async {
                        load("Could not load your documents.", { driverApi.listDocuments() }) { body -> body.data?.map { it.toItem() } }
                    }
                val vehicle = async { fetchVehicle() }
                AccountOverview(rating = rating.await(), documents = documents.await(), vehicle = vehicle.await())
            }

        /** `GET /drivers/me`; null when it cannot be read (the diagnostics then say the reference is unavailable). */
        suspend fun fetchDriverId(): String? =
            runCatching { driverApi.getProfile1() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.takeIf { it.isSuccessful }
                ?.body()
                ?.data
                ?.id
                ?.toString()

        /** A 404 means no vehicle on file yet - a normal state, not an error. */
        private suspend fun fetchVehicle(): Part<VehicleInfo?> {
            val response =
                runCatching { driverApi.getVehicle() }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return Part.Failed(NETWORK_ERROR_MESSAGE)
                }
            if (response.code() == HTTP_NOT_FOUND) return Part.Loaded(null)
            if (!response.isSuccessful) return Part.Failed(errorMessageOrDefault(response, "Could not load your vehicle."))
            return Part.Loaded(response.body()?.data?.toInfo())
        }

        private suspend fun <B, T> load(
            defaultError: String,
            request: suspend () -> Response<B>,
            map: (B) -> T?,
        ): Part<T> {
            val response =
                runCatching { request() }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return Part.Failed(NETWORK_ERROR_MESSAGE)
                }
            if (!response.isSuccessful) return Part.Failed(errorMessageOrDefault(response, defaultError))
            val value = response.body()?.let(map) ?: return Part.Failed("Unexpected response from the server.")
            return Part.Loaded(value)
        }

        private companion object {
            const val NETWORK_ERROR_MESSAGE = "Could not reach the server. Check your connection and try again."
            const val HTTP_NOT_FOUND = 404
        }
    }
