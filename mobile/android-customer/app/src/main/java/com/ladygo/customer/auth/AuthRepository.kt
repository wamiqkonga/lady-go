package com.ladygo.customer.auth

import com.ladygo.customer.api.generated.apis.AuthControllerApi
import com.ladygo.customer.api.generated.apis.CustomerAuthControllerApi
import com.ladygo.customer.api.generated.models.CustomerLoginRequest
import com.ladygo.customer.api.generated.models.CustomerRegisterRequest
import com.ladygo.customer.api.generated.models.ForgotPasswordRequest
import com.ladygo.customer.api.generated.models.LogoutRequest
import com.ladygo.customer.api.generated.models.RegisterOtpRequest
import com.ladygo.customer.api.generated.models.ResetPasswordRequest
import com.ladygo.customer.api.generated.models.TokenResponse
import com.ladygo.customer.network.errorMessageOrDefault
import com.ladygo.customer.push.PushTokenRegistrar
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The result of one step in sign-up, sign-in or password reset. Nothing here ever throws at a caller. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome

    data class Failed(
        val message: String,
    ) : AuthOutcome
}

private const val OFFLINE_MESSAGE = "Could not reach the server. Check your connection and try again."

/**
 * Rider accounts: sign up with phone, email and a password (the email is proven by a code sent to it), sign in with
 * email or phone plus password, and reset a forgotten password by emailed code.
 */
@Singleton
class AuthRepository
    @Inject
    constructor(
        private val authApi: AuthControllerApi,
        private val customerAuthApi: CustomerAuthControllerApi,
        private val tokenStore: TokenStore,
        private val sessionManager: SessionManager,
        private val pushTokens: PushTokenRegistrar,
    ) {
        /** One per process, never persisted - only distinguishes refresh-token "families" for the
         *  server's reuse detection, matching the web client's same reasoning. */
        private val deviceId: String = UUID.randomUUID().toString()

        suspend fun requestRegistrationOtp(
            phone: String,
            email: String,
        ): AuthOutcome =
            simple("We could not send the code. Please try again.") {
                customerAuthApi.requestRegistrationOtp(RegisterOtpRequest(phone = phone, email = email))
            }

        suspend fun register(
            phone: String,
            email: String,
            code: String,
            password: String,
        ): AuthOutcome =
            signedIn("We could not create your account. Check the code and try again.") {
                customerAuthApi.register1(
                    CustomerRegisterRequest(phone = phone, email = email, code = code, password = password, deviceId = deviceId),
                )
            }

        suspend fun login(
            identifier: String,
            password: String,
        ): AuthOutcome =
            signedIn("Incorrect email, phone or password.") {
                customerAuthApi.login(CustomerLoginRequest(identifier = identifier, password = password, deviceId = deviceId))
            }

        suspend fun requestPasswordReset(email: String): AuthOutcome =
            simple("We could not send the code. Please try again.") {
                customerAuthApi.forgotPassword(ForgotPasswordRequest(email = email))
            }

        suspend fun resetPassword(
            email: String,
            code: String,
            newPassword: String,
        ): AuthOutcome =
            simple("We could not change your password. Check the code and try again.") {
                customerAuthApi.resetPassword(ResetPasswordRequest(email = email, code = code, newPassword = newPassword))
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

        private suspend fun <T> simple(
            fallback: String,
            call: suspend () -> Response<T>,
        ): AuthOutcome {
            val response = send(call) ?: return AuthOutcome.Failed(OFFLINE_MESSAGE)
            return if (response.isSuccessful) AuthOutcome.Success else AuthOutcome.Failed(errorMessageOrDefault(response, fallback))
        }

        /** Runs a call that returns tokens; on success stores them and starts the session. */
        private suspend fun signedIn(
            fallback: String,
            call: suspend () -> Response<com.ladygo.customer.api.generated.models.ApiResponseTokenResponse>,
        ): AuthOutcome {
            val response = send(call) ?: return AuthOutcome.Failed(OFFLINE_MESSAGE)
            if (!response.isSuccessful) return AuthOutcome.Failed(errorMessageOrDefault(response, fallback))
            val tokens: TokenResponse? = response.body()?.data
            val access = tokens?.accessToken
            val refresh = tokens?.refreshToken
            if (access == null || refresh == null) return AuthOutcome.Failed("Unexpected response from the server.")
            tokenStore.setTokens(access, refresh)
            sessionManager.onLoggedIn()
            return AuthOutcome.Success
        }

        private suspend fun <T> send(call: suspend () -> Response<T>): Response<T>? =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
    }
