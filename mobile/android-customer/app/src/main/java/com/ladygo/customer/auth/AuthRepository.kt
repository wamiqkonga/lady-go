package com.ladygo.customer.auth

import com.ladygo.customer.api.generated.apis.AuthControllerApi
import com.ladygo.customer.api.generated.models.LogoutRequest
import com.ladygo.customer.api.generated.models.OtpRequestRequest
import com.ladygo.customer.api.generated.models.OtpVerifyRequest
import com.ladygo.customer.network.errorMessageOrDefault
import com.ladygo.customer.push.PushTokenRegistrar
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
 * OTP login for this app is always `purpose=LOGIN`, `intendedRole=CUSTOMER` - there is no
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
                            intendedRole = OtpRequestRequest.IntendedRole.CUSTOMER,
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
                            intendedRole = OtpVerifyRequest.IntendedRole.CUSTOMER,
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

        /** Best-effort server-side revoke, same as the website: tokens are cleared locally either way. */
        suspend fun logout() {
            runCatching { pushTokens.forget() } // while the access token is still valid
            val refreshToken = tokenStore.getRefreshToken()
            if (refreshToken != null) {
                runCatching { authApi.logout(LogoutRequest(refreshToken = refreshToken, logoutAll = false)) }
            }
            sessionManager.onLoggedOut()
        }
    }
