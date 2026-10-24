package com.ladygo.driver.onboarding

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseDriverProfileResponse
import com.ladygo.driver.api.generated.models.ApiResponseListDriverDocumentResponse
import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import com.ladygo.driver.api.generated.models.DriverProfileResponse
import com.ladygo.driver.api.generated.models.DriverProfileResponse.VerificationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class OnboardingRepositoryTest {
    private val driverApi = mockk<DriverControllerApi>()
    private val repository = OnboardingRepository(driverApi)

    @Test
    fun aDriverWithNoRowAtAllIsNotStartedNotAnError() =
        runTest {
            // Real backend behaviour for GET /drivers/me on a driver who never called bootstrapProfile:
            // DriverService.resolveDriver throws ResourceNotFoundException -> 404, not a 200 with
            // verificationStatus "NOT_SUBMITTED". The driver-registration website hit this exact gap first.
            coEvery { driverApi.getProfile1() } returns
                Response.error(404, "{}".toResponseBody("application/json".toMediaType()))

            val status = repository.fetchStatus()

            assertTrue(status is OnboardingStatus.NotStarted)
        }

    @Test
    fun approvedStatusIsReturnedAsIs() =
        runTest {
            coEvery { driverApi.getProfile1() } returns
                Response.success(
                    ApiResponseDriverProfileResponse(data = DriverProfileResponse(verificationStatus = VerificationStatus.APPROVED)),
                )

            val status = repository.fetchStatus() as OnboardingStatus.Known

            assertEquals(VerificationStatus.APPROVED, status.verificationStatus)
            assertTrue(status.documentNotes.isEmpty())
        }

    @Test
    fun pendingReviewDoesNotFetchDocuments() =
        runTest {
            coEvery { driverApi.getProfile1() } returns
                Response.success(
                    ApiResponseDriverProfileResponse(data = DriverProfileResponse(verificationStatus = VerificationStatus.PENDING_REVIEW)),
                )

            repository.fetchStatus()

            coVerify(exactly = 0) { driverApi.listDocuments() }
        }

    @Test
    fun rejectedFetchesOnlyTheRejectedDocumentsWithTheirReviewNotes() =
        runTest {
            coEvery { driverApi.getProfile1() } returns
                Response.success(
                    ApiResponseDriverProfileResponse(data = DriverProfileResponse(verificationStatus = VerificationStatus.REJECTED)),
                )
            coEvery { driverApi.listDocuments() } returns
                Response.success(
                    ApiResponseListDriverDocumentResponse(
                        data =
                            listOf(
                                DriverDocumentResponse(
                                    type = DriverDocumentResponse.Type.LICENSE,
                                    status = DriverDocumentResponse.Status.REJECTED,
                                    reviewNote = "Expired.",
                                ),
                                DriverDocumentResponse(
                                    type = DriverDocumentResponse.Type.INSURANCE,
                                    status = DriverDocumentResponse.Status.APPROVED,
                                ),
                            ),
                    ),
                )

            val status = repository.fetchStatus() as OnboardingStatus.Known

            assertEquals(1, status.documentNotes.size)
            assertEquals("Expired.", status.documentNotes.single().note)
            assertEquals("LICENSE", status.documentNotes.single().type)
        }

    @Test
    fun rejectedStillReturnsKnownIfTheDocumentsFetchFails() =
        runTest {
            coEvery { driverApi.getProfile1() } returns
                Response.success(
                    ApiResponseDriverProfileResponse(data = DriverProfileResponse(verificationStatus = VerificationStatus.REJECTED)),
                )
            coEvery { driverApi.listDocuments() } throws java.io.IOException("no network")

            val status = repository.fetchStatus() as OnboardingStatus.Known

            assertEquals(VerificationStatus.REJECTED, status.verificationStatus)
            assertTrue(status.documentNotes.isEmpty())
        }

    @Test
    fun aNetworkFailureIsALoadErrorNotNotStarted() =
        runTest {
            coEvery { driverApi.getProfile1() } throws java.io.IOException("no network")

            val status = repository.fetchStatus()

            assertTrue(status is OnboardingStatus.LoadError)
        }
}
