package com.ladygo.customer.onboarding

import com.ladygo.customer.api.generated.apis.CustomerControllerApi
import com.ladygo.customer.api.generated.models.DeclarationAcceptRequest
import com.ladygo.customer.api.generated.models.UpdateProfileRequest
import com.ladygo.customer.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** What the rider still has to do before she can book (docs/api/driver-onboarding.md: riders = phone +
 *  a fresh selfie per booking + a one-time self-declaration; there is no admin review). */
sealed interface OnboardingState {
    data object NeedsName : OnboardingState

    data class NeedsDeclaration(
        val version: String,
        val text: String,
    ) : OnboardingState

    data object Ready : OnboardingState

    data class LoadError(
        val message: String,
    ) : OnboardingState
}

sealed interface OnboardingStepOutcome {
    data object Done : OnboardingStepOutcome

    /** A real 4xx verdict - the server's own message. */
    data class Rejected(
        val message: String,
    ) : OnboardingStepOutcome

    /** Couldn't reach the server (or it failed): nothing is known to have been saved, retry is safe. */
    data class NetworkFailure(
        val message: String,
    ) : OnboardingStepOutcome
}

private const val NETWORK_ERROR_MESSAGE = "Could not reach the server. Check your connection and try again."
private const val HTTP_SERVER_ERROR_FLOOR = 500

@Singleton
class OnboardingRepository
    @Inject
    constructor(
        private val customerApi: CustomerControllerApi,
    ) {
        suspend fun fetchState(): OnboardingState = nameGate() ?: declarationGate()

        /** `null` when the name is already set and the next gate should be checked. */
        private suspend fun nameGate(): OnboardingState? {
            val profile = call { customerApi.getProfile() } ?: return OnboardingState.LoadError(NETWORK_ERROR_MESSAGE)
            if (!profile.isSuccessful) {
                return OnboardingState.LoadError(errorMessageOrDefault(profile, "Failed to load your profile."))
            }
            return if (profile
                    .body()
                    ?.data
                    ?.name
                    .isNullOrBlank()
            ) {
                OnboardingState.NeedsName
            } else {
                null
            }
        }

        private suspend fun declarationGate(): OnboardingState {
            val response = call { customerApi.getCustomerDeclarationStatus() } ?: return OnboardingState.LoadError(NETWORK_ERROR_MESSAGE)
            if (!response.isSuccessful) {
                return OnboardingState.LoadError(errorMessageOrDefault(response, "Failed to load the declaration."))
            }
            val status = response.body()?.data
            val version = status?.currentVersion
            val text = status?.currentText
            return when {
                status?.accepted == true -> OnboardingState.Ready
                version.isNullOrBlank() || text.isNullOrBlank() -> OnboardingState.LoadError("Failed to load the declaration.")
                else -> OnboardingState.NeedsDeclaration(version, text)
            }
        }

        suspend fun saveName(name: String): OnboardingStepOutcome =
            step("Could not save your name.") { customerApi.updateProfile(UpdateProfileRequest(name = name)) }

        suspend fun acceptDeclaration(version: String): OnboardingStepOutcome =
            step("Could not record your acceptance.") {
                customerApi.acceptDeclaration1(DeclarationAcceptRequest(declarationVersion = version))
            }

        private suspend fun step(
            defaultError: String,
            request: suspend () -> Response<*>,
        ): OnboardingStepOutcome {
            val response = call { request() } ?: return OnboardingStepOutcome.NetworkFailure(NETWORK_ERROR_MESSAGE)
            if (response.isSuccessful) return OnboardingStepOutcome.Done
            val message = errorMessageOrDefault(response, defaultError)
            return if (response.code() >= HTTP_SERVER_ERROR_FLOOR) {
                OnboardingStepOutcome.NetworkFailure(message)
            } else {
                OnboardingStepOutcome.Rejected(message)
            }
        }

        /** `null` when the request could not even be sent/completed (IO failure); cancellation propagates. */
        private suspend fun <T> call(request: suspend () -> Response<T>): Response<T>? =
            runCatching { request() }.getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
    }
