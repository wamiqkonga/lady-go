package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.apis.DriverAuthControllerApi
import com.ladygo.driver.api.generated.models.DriverAppVerifyRequest
import com.ladygo.driver.api.generated.models.DriverPasswordLoginRequest
import com.ladygo.driver.api.generated.models.LogoutRequest
import com.ladygo.driver.api.generated.models.OtpRequestRequest
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.location.AvailabilityRepository
import com.ladygo.driver.network.errorMessageOrDefault
import com.ladygo.driver.network.parseErrorBody
import com.ladygo.driver.push.PushTokenRegistrar
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why the server would not let this driver in. Each has its own message in the app, and all point at support. */
enum class BlockedReason(
    val serverCode: String,
) {
    UNDER_REVIEW("DRIVER_UNDER_REVIEW"),
    NOT_REGISTERED("DRIVER_NOT_REGISTERED"),
    REJECTED("DRIVER_REJECTED"),
    SUSPENDED("DRIVER_SUSPENDED"),
}

private fun blockedReasonOf(code: String?): BlockedReason? = BlockedReason.entries.firstOrNull { it.serverCode == code }

sealed interface VerifyOtpOutcome {
    data object LoggedIn : VerifyOtpOutcome

    /** The code was right but the driver is not (yet) allowed to use the app. */
    data class Blocked(
        val reason: BlockedReason,
    ) : VerifyOtpOutcome

    /** The backend's own union response for `purpose=LOGIN` always returns tokens (see
     *  AuthController.verifyOtp) - a bare "phone verified" message here would mean the server
     *  changed contract, not a normal outcome, so it is surfaced as a failure, not modeled as a
     *  third success case this app would need a screen for. */
    data class Failed(
        val message: String,
    ) : VerifyOtpOutcome
}

sealed interface PasswordLoginOutcome {
    data object LoggedIn : PasswordLoginOutcome

    /** The password was right but the driver is not (yet) allowed to use the app. */
    data class Blocked(
        val reason: BlockedReason,
    ) : PasswordLoginOutcome

    data class Failed(
        val message: String,
    ) : PasswordLoginOutcome
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
        private val driverAuthApi: DriverAuthControllerApi,
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
        private val availabilityRepository: AvailabilityRepository,
        private val appPreferences: AppPreferences,
        private val pushTokens: PushTokenRegistrar,
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

        /** Checks the code through the driver-app endpoint, which only admits fully approved drivers. */
        suspend fun verifyOtp(
            phone: String,
            code: String,
        ): VerifyOtpOutcome {
            val response =
                runCatching { authApi.verifyDriverAppOtp(DriverAppVerifyRequest(phone = phone, code = code, deviceId = deviceId)) }
                    .getOrElse {
                        return VerifyOtpOutcome.Failed("Could not reach the server. Check your connection and try again.")
                    }
            if (!response.isSuccessful) {
                val error = parseErrorBody(response)
                blockedReasonOf(error?.code)?.let { return VerifyOtpOutcome.Blocked(it) }
                return VerifyOtpOutcome.Failed(error?.message ?: "That code didn't work. Check it and try again.")
            }
            val tokens = response.body()?.data
            val accessToken = tokens?.accessToken
            val refreshToken = tokens?.refreshToken
            if (accessToken == null || refreshToken == null) {
                return VerifyOtpOutcome.Failed("Unexpected response from the server.")
            }
            tokenStore.setTokens(accessToken, refreshToken)
            sessionManager.onLoggedIn()
            return VerifyOtpOutcome.LoggedIn
        }

        /**
         * Password sign-in (phone or email + password), the same credential the captain set on the website. One
         * plain message for every wrong-credential case so the app never reveals which part was wrong; the server
         * answers 429 while an account is waiting after failed tries.
         */
        suspend fun loginWithPassword(
            identifier: String,
            password: String,
        ): PasswordLoginOutcome {
            val response =
                runCatching {
                    driverAuthApi.driverAppPasswordLogin(
                        DriverPasswordLoginRequest(identifier = identifier.trim(), password = password, deviceId = deviceId),
                    )
                }.getOrElse {
                    return PasswordLoginOutcome.Failed("Could not reach the server. Check your connection and try again.")
                }
            if (!response.isSuccessful) {
                blockedReasonOf(parseErrorBody(response)?.code)?.let { return PasswordLoginOutcome.Blocked(it) }
                return PasswordLoginOutcome.Failed(
                    when (response.code()) {
                        401 -> "Phone, email or password is not correct."
                        429 -> "Too many tries. Wait a little and try again, or sign in with a code."
                        else -> errorMessageOrDefault(response, "Could not sign in. Try again.")
                    },
                )
            }
            val tokens = response.body()?.data
            val accessToken = tokens?.accessToken
            val refreshToken = tokens?.refreshToken
            if (accessToken == null || refreshToken == null) {
                return PasswordLoginOutcome.Failed("Unexpected response from the server.")
            }
            tokenStore.setTokens(accessToken, refreshToken)
            sessionManager.onLoggedIn()
            return PasswordLoginOutcome.LoggedIn
        }

        /** Best-effort server-side revoke, same as the website: tokens are cleared locally either way.
         *  An explicit logout also goes OFFLINE on the server first (while the access token still works) so
         *  the driver isn't left matchable with nobody holding the app, and forgets the remembered ride -
         *  unlike a forced logout (see [SessionManager.onLoggedOut]), which keeps it so the ride resumes
         *  after the next sign-in. Neither step may ever hold up or fail the logout. */
        suspend fun logout() {
            withTimeoutOrNull(GO_OFFLINE_TIMEOUT_MILLIS) { runCatching { availabilityRepository.goOffline() } }
            appPreferences.setActiveBookingId(null)
            runCatching { pushTokens.forget() } // while the access token is still valid
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
