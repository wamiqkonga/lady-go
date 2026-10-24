package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.models.LogoutRequest
import com.ladygo.driver.api.generated.models.OtpRequestRequest
import com.ladygo.driver.api.generated.models.OtpVerifyRequest
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.location.AvailabilityRepository
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface VerifyOtpOutcome {
    data object LoggedIn : VerifyOtpOutcome

    /** The backend's own union response for `purpose=LOGIN` always returns tokens (see
     *  AuthController.verifyOtp) - a bare "phone verified" message here would mean the server
     *  changed contract, not a normal outcome, so it is surfaced as a failure, not modeled as a
     *  third success case this app would need a screen for. */
    data class Failed(
        val message: String,
    ) : VerifyOtpOutcome
}

sealed interface RequestOtpOutcome {
    data object Sent : RequestOtpOutcome

    data class Failed(
        val message: String,
    ) : RequestOtpOutcome
}

/**
 * OTP login for this app is always `purpose=LOGIN`, `intendedRole=DRIVER` - there is no
 * PHONE_VERIFY/ADMIN_MFA use here, those belong to the registration website and the admin app
 * respectively.
 */
@Singleton
class AuthRepository
    @Inject
    constructor(
        private val authApi: AuthControllerApi,
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
        private val availabilityRepository: AvailabilityRepository,
        private val appPreferences: AppPreferences,
    ) {
        /** One per process, never persisted - only distinguishes refresh-token "families" for the
         *  server's reuse detection, matching the web client's same reasoning. */
        private val deviceId: String = UUID.randomUUID().toString()

        suspend fun requestOtp(phone: String): RequestOtpOutcome {
            val response =
                runCatching {
                    authApi.requestOtp(
                        OtpRequestRequest(
                            phone = phone,
                            purpose = OtpRequestRequest.Purpose.LOGIN,
                            intendedRole = OtpRequestRequest.IntendedRole.DRIVER,
                        ),
                    )
                }.getOrElse {
                    return RequestOtpOutcome.Failed(
                        "Could not reach the server. Check your connection and try again.",
                    )
                }
            return if (response.isSuccessful) {
                RequestOtpOutcome.Sent
            } else {
                RequestOtpOutcome.Failed(errorMessageOrDefault(response, "Failed to request a code."))
            }
        }

        suspend fun verifyOtp(
            phone: String,
            code: String,
        ): VerifyOtpOutcome {
            val response =
                runCatching {
                    authApi.verifyOtp(
                        OtpVerifyRequest(
                            phone = phone,
                            code = code,
                            purpose = OtpVerifyRequest.Purpose.LOGIN,
                            intendedRole = OtpVerifyRequest.IntendedRole.DRIVER,
                            deviceId = deviceId,
                        ),
                    )
                }.getOrElse {
                    return VerifyOtpOutcome.Failed(
                        "Could not reach the server. Check your connection and try again.",
                    )
                }

            if (!response.isSuccessful) {
                return VerifyOtpOutcome.Failed(
                    errorMessageOrDefault(response, "That code didn't work. Check it and try again."),
                )
            }
            val data = response.body()?.data as? Map<*, *>
            val accessToken = data?.get("accessToken") as? String
            val refreshToken = data?.get("refreshToken") as? String
            if (accessToken == null || refreshToken == null) {
                return VerifyOtpOutcome.Failed("Unexpected response from the server.")
            }
            tokenStore.setTokens(accessToken, refreshToken)
            sessionManager.onLoggedIn()
            return VerifyOtpOutcome.LoggedIn
        }

        /** Best-effort server-side revoke, same as the website: tokens are cleared locally either way.
         *  An explicit logout also goes OFFLINE on the server first (while the access token still works) so
         *  the driver isn't left matchable with nobody holding the app, and forgets the remembered ride -
         *  unlike a forced logout (see [SessionManager.onLoggedOut]), which keeps it so the ride resumes
         *  after the next sign-in. Neither step may ever hold up or fail the logout. */
        suspend fun logout() {
            withTimeoutOrNull(GO_OFFLINE_TIMEOUT_MILLIS) { runCatching { availabilityRepository.goOffline() } }
            appPreferences.setActiveBookingId(null)
            val refreshToken = tokenStore.getRefreshToken()
            if (refreshToken != null) {
                runCatching { authApi.logout(LogoutRequest(refreshToken = refreshToken, logoutAll = false)) }
            }
            sessionManager.onLoggedOut()
        }

        private companion object {
            const val GO_OFFLINE_TIMEOUT_MILLIS = 3_000L
        }
    }
