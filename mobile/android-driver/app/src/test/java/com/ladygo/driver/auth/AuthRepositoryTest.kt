package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseMessageResponse
import com.ladygo.driver.api.generated.models.ApiResponseObject
import com.ladygo.driver.api.generated.models.MessageResponse
import com.ladygo.driver.location.AppPreferences
import com.ladygo.driver.location.AvailabilityRepository
import com.ladygo.driver.location.SetAvailabilityOutcome
import com.ladygo.driver.testing.FakeTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class AuthRepositoryTest {
    private val authApi = mockk<AuthControllerApi>()
    private val tokenStore = FakeTokenStore()
    private val sessionManager = SessionManager(tokenStore)
    private val availabilityRepository = mockk<AvailabilityRepository>()
    private val appPreferences = mockk<AppPreferences>(relaxed = true)
    private val repository = AuthRepository(authApi, tokenStore, sessionManager, availabilityRepository, appPreferences)

    @Before
    fun goOfflineSucceeds() {
        coEvery { availabilityRepository.goOffline() } returns SetAvailabilityOutcome.Success
    }

    private fun <T> errorResponse(
        code: Int,
        body: String,
    ): Response<T> = Response.error(code, body.toResponseBody("application/json".toMediaType()))

    @Test
    fun requestOtpSucceeds() =
        runTest {
            coEvery { authApi.requestOtp(any()) } returns
                Response.success(ApiResponseMessageResponse(data = MessageResponse("If this number is valid, a code was sent.")))

            val outcome = repository.requestOtp("+14155550100")

            assertTrue(outcome is RequestOtpOutcome.Sent)
        }

    @Test
    fun requestOtpSurfacesTheServerErrorMessage() =
        runTest {
            coEvery { authApi.requestOtp(any()) } returns
                errorResponse<ApiResponseMessageResponse>(429, """{"error":{"code":"RATE_LIMITED","message":"Too many requests."}}""")

            val outcome = repository.requestOtp("+14155550100")

            assertTrue(outcome is RequestOtpOutcome.Failed)
            assertEquals("Too many requests.", (outcome as RequestOtpOutcome.Failed).message)
        }

    @Test
    fun requestOtpFailsClosedOnANetworkException() =
        runTest {
            coEvery { authApi.requestOtp(any()) } throws java.io.IOException("no network")

            val outcome = repository.requestOtp("+14155550100")

            assertTrue(outcome is RequestOtpOutcome.Failed)
        }

    @Test
    fun verifyOtpLogsInWhenTheServerReturnsTokens() =
        runTest {
            coEvery { authApi.verifyOtp(any()) } returns
                Response.success(ApiResponseObject(data = mapOf("accessToken" to "access-1", "refreshToken" to "refresh-1")))

            val outcome = repository.verifyOtp("+14155550100", "123456")

            assertTrue(outcome is VerifyOtpOutcome.LoggedIn)
            assertEquals("access-1", tokenStore.getAccessToken())
            assertEquals("refresh-1", tokenStore.getRefreshToken())
            assertTrue(sessionManager.isLoggedIn.value)
        }

    /** `purpose=LOGIN` always returns tokens server-side (AuthController.verifyOtp) - a bare
     *  message-only response here would mean the contract changed, and must fail, not be silently
     *  treated as some other kind of success. */
    @Test
    fun verifyOtpFailsIfTheServerReturnsOnlyAMessageForLoginPurpose() =
        runTest {
            coEvery { authApi.verifyOtp(any()) } returns
                Response.success(ApiResponseObject(data = mapOf("message" to "Phone verified.")))

            val outcome = repository.verifyOtp("+14155550100", "123456")

            assertTrue(outcome is VerifyOtpOutcome.Failed)
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun verifyOtpSurfacesAnInvalidCodeError() =
        runTest {
            coEvery { authApi.verifyOtp(any()) } returns
                errorResponse<ApiResponseObject>(401, """{"error":{"code":"INVALID_OTP","message":"That code is wrong."}}""")

            val outcome = repository.verifyOtp("+14155550100", "000000")

            assertTrue(outcome is VerifyOtpOutcome.Failed)
            assertEquals("That code is wrong.", (outcome as VerifyOtpOutcome.Failed).message)
        }

    @Test
    fun logoutRevokesTheRefreshTokenAndClearsTheSessionEvenIfTheCallFails() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.logout(any()) } throws java.io.IOException("no network")

            repository.logout()

            coVerify(exactly = 1) { authApi.logout(any()) }
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun logoutGoesOfflineOnTheServerForgetsTheRideAndStillCompletesWhenOfflineFails() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { availabilityRepository.goOffline() } throws java.io.IOException("no network")
            coEvery { authApi.logout(any()) } returns Response.success(null)

            repository.logout()

            coVerify(exactly = 1) { availabilityRepository.goOffline() }
            verify { appPreferences.setActiveBookingId(null) }
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun logoutIsANoOpCallWhenThereIsNoRefreshTokenToRevoke() =
        runTest {
            repository.logout()

            coVerify(exactly = 0) { authApi.logout(any()) }
            assertFalse(sessionManager.isLoggedIn.value)
        }
}
