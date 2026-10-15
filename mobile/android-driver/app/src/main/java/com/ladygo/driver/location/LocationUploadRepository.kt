package com.ladygo.driver.location

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.models.DriverLocationRequest
import com.ladygo.driver.network.parseErrorBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LocationSendOutcome {
    data object Sent : LocationSendOutcome

    /**
     * The server's own, pre-existing signal that this driver was set OFFLINE server-side while the
     * app still thinks it's ONLINE (`DriverLocationService.updateLocation`: "Driver is OFFLINE." -
     * confirmed from the real backend source, not guessed). There is no dedicated endpoint to just
     * *read* current availability (`PUT .../availability` is the only exposed operation, and it
     * only ever accepts ONLINE/OFFLINE as a driver-initiated request - it cannot be used as a
     * side-effect-free probe), so this rejection on the location endpoint is the one real
     * reconciliation signal this app has for "the server disagrees that I'm online" - documented
     * as a confirmed gap in docs/product/driver-android-design.md, not papered over.
     */
    data object DriverSetOffline : LocationSendOutcome

    data class Failed(
        val message: String,
    ) : LocationSendOutcome
}

@Singleton
class LocationUploadRepository
    @Inject
    constructor(
        private val driverApi: DriverControllerApi,
    ) {
        suspend fun send(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Double,
            recordedAt: Instant,
        ): LocationSendOutcome {
            val response =
                runCatching {
                    driverApi.updateLocation(
                        DriverLocationRequest(
                            latitude = latitude,
                            longitude = longitude,
                            recordedAt = recordedAt.toString(),
                            accuracyMeters = accuracyMeters,
                        ),
                    )
                }.getOrElse { return LocationSendOutcome.Failed("Could not reach the server.") }

            if (response.isSuccessful) return LocationSendOutcome.Sent

            val error = parseErrorBody(response)
            if (error?.message == DRIVER_OFFLINE_MESSAGE) {
                return LocationSendOutcome.DriverSetOffline
            }
            return LocationSendOutcome.Failed(error?.message ?: "Location update failed (status ${response.code()}).")
        }

        private companion object {
            // Exact literal from DriverLocationService.updateLocation - matched precisely, not as a
            // prefix/substring, so a future unrelated wording change on the server fails this match
            // loudly (LocationSendOutcome.Failed, same as any other rejection) instead of silently.
            const val DRIVER_OFFLINE_MESSAGE = "Driver is OFFLINE."
        }
    }
