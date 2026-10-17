package com.ladygo.customer.onboarding

import com.ladygo.customer.api.generated.apis.CustomerControllerApi
import com.ladygo.customer.api.generated.models.ApiResponseCustomerProfileResponse
import com.ladygo.customer.api.generated.models.ApiResponseDeclarationResponse
import com.ladygo.customer.api.generated.models.ApiResponseDeclarationStatusResponse
import com.ladygo.customer.api.generated.models.CustomerProfileResponse
import com.ladygo.customer.api.generated.models.DeclarationAcceptRequest
import com.ladygo.customer.api.generated.models.DeclarationStatusResponse
import com.ladygo.customer.api.generated.models.UpdateProfileRequest
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
    private val api = mockk<CustomerControllerApi>()
    private val repository = OnboardingRepository(api)

    private fun profile(name: String?) = Response.success(ApiResponseCustomerProfileResponse(data = CustomerProfileResponse(name = name)))

    private fun declaration(
        accepted: Boolean,
        version: String? = "v1",
        text: String? = "I confirm...",
    ) = Response.success(
        ApiResponseDeclarationStatusResponse(
            data = DeclarationStatusResponse(currentVersion = version, currentText = text, accepted = accepted),
        ),
    )

    private fun <T> error(
        code: Int,
        body: String = """{"error":{"code":"X","message":"Server said no."}}""",
    ): Response<T> = Response.error(code, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun aBlankNameNeedsTheNameStep() =
        runTest {
            coEvery { api.getProfile() } returns profile("  ")

            assertEquals(OnboardingState.NeedsName, repository.fetchState())
        }

    @Test
    fun aMissingNameNeedsTheNameStepAndNeverAsksForTheDeclarationFirst() =
        runTest {
            coEvery { api.getProfile() } returns profile(null)

            repository.fetchState()

            coVerify(exactly = 0) { api.getCustomerDeclarationStatus() }
        }

    @Test
    fun anUnacceptedDeclarationCarriesTheVersionAndTextToShow() =
        runTest {
            coEvery { api.getProfile() } returns profile("Aisha")
            coEvery { api.getCustomerDeclarationStatus() } returns declaration(accepted = false)

            assertEquals(OnboardingState.NeedsDeclaration("v1", "I confirm..."), repository.fetchState())
        }

    @Test
    fun anAcceptedDeclarationMeansReady() =
        runTest {
            coEvery { api.getProfile() } returns profile("Aisha")
            coEvery { api.getCustomerDeclarationStatus() } returns declaration(accepted = true)

            assertEquals(OnboardingState.Ready, repository.fetchState())
        }

    @Test
    fun aDeclarationWithNoTextIsALoadErrorNotAnEmptyAcceptButton() =
        runTest {
            coEvery { api.getProfile() } returns profile("Aisha")
            coEvery { api.getCustomerDeclarationStatus() } returns declaration(accepted = false, text = null)

            assertTrue(repository.fetchState() is OnboardingState.LoadError)
        }

    @Test
    fun anIoFailureOrAServerErrorIsALoadErrorNeverReady() =
        runTest {
            coEvery { api.getProfile() } throws java.io.IOException("offline")
            assertTrue(repository.fetchState() is OnboardingState.LoadError)

            coEvery { api.getProfile() } returns error(500)
            assertTrue(repository.fetchState() is OnboardingState.LoadError)
        }

    @Test
    fun saveNameSendsTheNameAndReportsDone() =
        runTest {
            coEvery { api.updateProfile(UpdateProfileRequest(name = "Aisha")) } returns profile("Aisha")

            assertEquals(OnboardingStepOutcome.Done, repository.saveName("Aisha"))
        }

    @Test
    fun a4xxIsRejectedWithTheServersMessageAnd5xxOrIoIsRetryable() =
        runTest {
            coEvery { api.updateProfile(any()) } returns error(400)
            assertEquals(OnboardingStepOutcome.Rejected("Server said no."), repository.saveName("x"))

            coEvery { api.updateProfile(any()) } returns error(503)
            assertTrue(repository.saveName("x") is OnboardingStepOutcome.NetworkFailure)

            coEvery { api.updateProfile(any()) } throws java.io.IOException("offline")
            assertTrue(repository.saveName("x") is OnboardingStepOutcome.NetworkFailure)
        }

    @Test
    fun acceptDeclarationSendsTheVersionTheServerShowed() =
        runTest {
            coEvery { api.acceptDeclaration1(DeclarationAcceptRequest(declarationVersion = "v7")) } returns
                Response.success(ApiResponseDeclarationResponse())

            assertEquals(OnboardingStepOutcome.Done, repository.acceptDeclaration("v7"))
        }
}
