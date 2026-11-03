package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.apis.DriverAuthControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseMessageResponse
import com.ladygo.driver.api.generated.models.ApiResponseTokenResponse
import com.ladygo.driver.api.generated.models.MessageResponse
import com.ladygo.driver.api.generated.models.TokenResponse
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
    private val driverAuthApi = mockk<DriverAuthControllerApi>()
    private val tokenStore = FakeTokenStore()
    private val sessionManager = SessionManager(tokenStore)
    private val availabilityRepository = mockk<AvailabilityRepository>()
    private val appPreferences = mockk<AppPreferences>(relaxed = true)
    private val pushTokens = mockk<com.ladygo.driver.push.PushTokenRegistrar>(relaxed = true)
    private val repository =
        AuthRepository(authApi, driverAuthApi, tokenStore, sessionManager, availabilityRepository, appPreferences, pushTokens)

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
            coEvery { authApi.verifyDriverAppOtp(any()) } returns
                Response.success(ApiResponseTokenResponse(data = TokenResponse(accessToken = "access-1", refreshToken = "refresh-1")))

            val outcome = repository.verifyOtp("+14155550100", "123456")

            assertTrue(outcome is VerifyOtpOutcome.LoggedIn)
            assertEquals("access-1", tokenStore.getAccessToken())
            assertEquals("refresh-1", tokenStore.getRefreshToken())
            assertTrue(sessionManager.isLoggedIn.value)
        }

    @Test
    fun verifyOtpFailsIfTheServerReturnsNoTokens() =
        runTest {
            coEvery { authApi.verifyDriverAppOtp(any()) } returns Response.success(ApiResponseTokenResponse(data = null))

            val outcome = repository.verifyOtp("+14155550100", "123456")

            assertTrue(outcome is VerifyOtpOutcome.Failed)
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun verifyOtpSurfacesAnInvalidCodeError() =
        runTest {
            coEvery { authApi.verifyDriverAppOtp(any()) } returns
                errorResponse<ApiResponseTokenResponse>(401, """{"error":{"code":"INVALID_OTP","message":"That code is wrong."}}""")

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

    @Test
    fun passwordLoginStoresTokensAndSignsIn() =
        runTest {
            coEvery { driverAuthApi.driverAppPasswordLogin(any()) } returns
                Response.success(ApiResponseTokenResponse(data = TokenResponse(accessToken = "access-9", refreshToken = "refresh-9")))

            val outcome = repository.loginWithPassword(" +919900000001 ", "my little pink scooter")

            assertTrue(outcome is PasswordLoginOutcome.LoggedIn)
            assertEquals("access-9", tokenStore.getAccessToken())
            assertEquals("refresh-9", tokenStore.getRefreshToken())
            assertTrue(sessionManager.isLoggedIn.value)
            coVerify {
                driverAuthApi.driverAppPasswordLogin(
                    match { it.identifier == "+919900000001" && it.password == "my little pink scooter" && it.deviceId != null },
                )
            }
        }

    @Test
    fun passwordLoginGivesOnePlainMessageForAWrongPassword() =
        runTest {
            coEvery { driverAuthApi.driverAppPasswordLogin(any()) } returns
                errorResponse<ApiResponseTokenResponse>(
                    401,
                    """{"error":{"code":"UNAUTHORIZED","message":"Authentication is required."}}""",
                )

            val outcome = repository.loginWithPassword("captain@example.com", "wrong-password-1")

            assertEquals(PasswordLoginOutcome.Failed("Phone, email or password is not correct."), outcome)
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun passwordLoginExplainsAWaitWhenTheAccountIsRateLimited() =
        runTest {
            coEvery { driverAuthApi.driverAppPasswordLogin(any()) } returns
                errorResponse<ApiResponseTokenResponse>(429, """{"error":{"code":"RATE_LIMITED","message":"Too many requests."}}""")

            val outcome = repository.loginWithPassword("captain@example.com", "wrong-password-1")

            assertTrue((outcome as PasswordLoginOutcome.Failed).message.startsWith("Too many tries"))
        }

    @Test
    fun passwordLoginFailsClosedOnANetworkException() =
        runTest {
            coEvery { driverAuthApi.driverAppPasswordLogin(any()) } throws java.io.IOException("no network")

            val outcome = repository.loginWithPassword("captain@example.com", "my little pink scooter")

            assertTrue(outcome is PasswordLoginOutcome.Failed)
            assertFalse(sessionManager.isLoggedIn.value)
        }

    private val blockedCodes =
        mapOf(
            "DRIVER_UNDER_REVIEW" to BlockedReason.UNDER_REVIEW,
            "DRIVER_NOT_REGISTERED" to BlockedReason.NOT_REGISTERED,
            "DRIVER_REJECTED" to BlockedReason.REJECTED,
            "DRIVER_SUSPENDED" to BlockedReason.SUSPENDED,
        )

    @Test
    fun everyServerRefusalOfACodeBecomesItsOwnBlockedReasonAndLeavesThemSignedOut() =
        runTest {
            blockedCodes.forEach { (code, reason) ->
                coEvery { authApi.verifyDriverAppOtp(any()) } returns
                    errorResponse<ApiResponseTokenResponse>(403, """{"error":{"code":"$code","message":"No."}}""")

                assertEquals(code, VerifyOtpOutcome.Blocked(reason), repository.verifyOtp("+14155550100", "123456"))
            }
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun everyServerRefusalOfAPasswordBecomesItsOwnBlockedReasonAndLeavesThemSignedOut() =
        runTest {
            blockedCodes.forEach { (code, reason) ->
                coEvery { driverAuthApi.driverAppPasswordLogin(any()) } returns
                    errorResponse<ApiResponseTokenResponse>(403, """{"error":{"code":"$code","message":"No."}}""")

                assertEquals(code, PasswordLoginOutcome.Blocked(reason), repository.loginWithPassword("+919900000001", "a password 1"))
            }
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun logoutForgetsThePushTokenBeforeRevokingTheSession() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            coEvery { authApi.logout(any()) } returns Response.success(null)

            repository.logout()

            coVerify { pushTokens.forget() }
        }
}
