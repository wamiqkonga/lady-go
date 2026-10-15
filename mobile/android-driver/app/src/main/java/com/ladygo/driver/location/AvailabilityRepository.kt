package com.ladygo.driver.location

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.models.DriverAvailabilityRequest
import com.ladygo.driver.network.errorMessageOrDefault
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SetAvailabilityOutcome {
    data object Success : SetAvailabilityOutcome

    data class Failed(
        val message: String,
    ) : SetAvailabilityOutcome
}

/**
 * `PUT /drivers/me/availability` only ever accepts `ONLINE`/`OFFLINE` as a driver-initiated request
 * - confirmed from `DriverAvailabilityService.setAvailability`, which rejects anything else outright,
 * and separately rejects *either* value while the driver is actually `BUSY`/`ON_RIDE`/`SUSPENDED`
 * ("Availability cannot be changed directly while X."). Going ONLINE can also fail for reasons that
 * have nothing to do with permissions - no approved vehicle on file, the driver itself isn't
 * APPROVED, or a pending re-verification blocks it - so every failure here must show the server's
 * real message, never a generic "something went wrong".
 *
 * There is no corresponding GET: this is the only availability-related endpoint that exists. See
 * [LocationUploadRepository] for the one indirect signal this app has for "the server disagrees".
 */
@Singleton
class AvailabilityRepository
    @Inject
    constructor(
        private val driverApi: DriverControllerApi,
    ) {
        suspend fun goOnline(): SetAvailabilityOutcome = setAvailability(DriverAvailabilityRequest.Status.ONLINE)

        suspend fun goOffline(): SetAvailabilityOutcome = setAvailability(DriverAvailabilityRequest.Status.OFFLINE)

        private suspend fun setAvailability(status: DriverAvailabilityRequest.Status): SetAvailabilityOutcome {
            val response =
                runCatching {
                    driverApi.setAvailability(DriverAvailabilityRequest(status = status))
                }.getOrElse { return SetAvailabilityOutcome.Failed("Could not reach the server. Check your connection and try again.") }

            return if (response.isSuccessful) {
                SetAvailabilityOutcome.Success
            } else {
                SetAvailabilityOutcome.Failed(errorMessageOrDefault(response, "Failed to update your availability."))
            }
        }
    }
