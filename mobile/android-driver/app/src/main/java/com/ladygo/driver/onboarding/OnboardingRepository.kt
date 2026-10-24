package com.ladygo.driver.onboarding

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import com.ladygo.driver.api.generated.models.DriverProfileResponse
import com.ladygo.driver.network.errorMessageOrDefault
import javax.inject.Inject
import javax.inject.Singleton

data class DocumentNote(
    val type: String,
    val status: String,
    val note: String?,
)

sealed interface OnboardingStatus {
    /**
     * `GET /drivers/me` 404s for a driver with no `Driver` row at all - confirmed against the real
     * backend (`DriverService.resolveDriver` throws `ResourceNotFoundException`), not a 200 with
     * `verificationStatus: "NOT_SUBMITTED"`. The driver-registration website hit this exact gap
     * first (frontend/driver-registration/src/pages/Dashboard.tsx's `noProfileYet`); this is the
     * same distinct, expected state, not a load error.
     */
    data object NotStarted : OnboardingStatus

    data class Known(
        val verificationStatus: DriverProfileResponse.VerificationStatus,
        val documentNotes: List<DocumentNote> = emptyList(),
    ) : OnboardingStatus

    data class LoadError(
        val message: String,
    ) : OnboardingStatus
}

@Singleton
class OnboardingRepository
    @Inject
    constructor(
        private val driverApi: DriverControllerApi,
    ) {
        suspend fun fetchStatus(): OnboardingStatus {
            val profileResponse =
                runCatching { driverApi.getProfile1() }
                    .getOrElse {
                        return OnboardingStatus.LoadError(
                            "Could not reach the server. Check your connection and try again.",
                        )
                    }

            if (profileResponse.code() == 404) {
                return OnboardingStatus.NotStarted
            }
            if (!profileResponse.isSuccessful) {
                return OnboardingStatus.LoadError(
                    errorMessageOrDefault(profileResponse, "Failed to load your profile."),
                )
            }
            val status =
                profileResponse.body()?.data?.verificationStatus
                    ?: return OnboardingStatus.LoadError("Failed to load your profile.")

            if (status != DriverProfileResponse.VerificationStatus.REJECTED) {
                return OnboardingStatus.Known(status)
            }
            val documentNotes =
                runCatching { driverApi.listDocuments() }
                    .getOrNull()
                    ?.takeIf { it.isSuccessful }
                    ?.body()
                    ?.data
                    ?.filter { it.status == DriverDocumentResponse.Status.REJECTED }
                    ?.map {
                        DocumentNote(
                            type = it.type?.value ?: "UNKNOWN",
                            status = it.status?.value ?: "UNKNOWN",
                            note = it.reviewNote,
                        )
                    }
                    ?: emptyList()
            return OnboardingStatus.Known(status, documentNotes)
        }
    }
