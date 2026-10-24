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
            if (error?.message?.contains(DRIVER_OFFLINE_MARKER) == true) {
                return LocationSendOutcome.DriverSetOffline
            }
            return LocationSendOutcome.Failed(error?.message ?: "Location update failed (status ${response.code()}).")
        }

        private companion object {
            // DriverLocationService.updateLocation's rejection message is "Driver is OFFLINE." -
            // matched as a substring rather than exact equality, so a minor wording change
            // (punctuation, a trailing detail) on the server doesn't silently break this. Security
            // review finding: the error code alone isn't specific enough to use instead - every
            // validation failure on this endpoint (rate limit aside) shares the same generic
            // VALIDATION_FAILED code (confirmed from ValidationException's own source), so the
            // message is the only sufficiently specific signal available without a backend change.
            // No other rejection on this endpoint ("accuracyMeters is required", "Location update is
            // out of order", "...implies an impossible speed") contains this word, so the substring
            // match stays safely specific.
            const val DRIVER_OFFLINE_MARKER = "OFFLINE"
        }
    }
