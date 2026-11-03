package com.ladygo.customer.auth

import com.ladygo.customer.api.generated.apis.AuthControllerApi
import com.ladygo.customer.api.generated.apis.CustomerAuthControllerApi
import com.ladygo.customer.api.generated.models.ApiResponseMessageResponse
import com.ladygo.customer.api.generated.models.ApiResponseTokenResponse
import com.ladygo.customer.api.generated.models.CustomerLoginRequest
import com.ladygo.customer.api.generated.models.CustomerRegisterRequest
import com.ladygo.customer.api.generated.models.MessageResponse
import com.ladygo.customer.api.generated.models.TokenResponse
import com.ladygo.customer.push.PushTokenRegistrar
import com.ladygo.customer.testing.FakeTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AuthRepositoryTest {
    private val authApi = mockk<AuthControllerApi>()
    private val customerApi = mockk<CustomerAuthControllerApi>()
    private val tokenStore = FakeTokenStore()
    private val sessionManager = SessionManager(tokenStore)
    private val pushTokens = mockk<PushTokenRegistrar>(relaxed = true)
    private val repository = AuthRepository(authApi, customerApi, tokenStore, sessionManager, pushTokens)

    private fun <T> errorResponse(
        code: Int,
        message: String,
    ): Response<T> =
        Response.error(
            code,
            """{"success":false,"error":{"code":"X","message":"$message"}}""".toResponseBody("application/json".toMediaType()),
        )

    private fun tokens() =
        Response.success(ApiResponseTokenResponse(data = TokenResponse(accessToken = "access-1", refreshToken = "refresh-1")))

    private fun message() = Response.success(ApiResponseMessageResponse(data = MessageResponse(message = "ok")))

    @Test
    fun theRegistrationCodeIsRequestedForThePhoneAndEmail() =
        runTest {
            coEvery { customerApi.requestRegistrationOtp(any()) } returns message()

            assertEquals(AuthOutcome.Success, repository.requestRegistrationOtp("+919876543210", "a@example.com"))

            coVerify { customerApi.requestRegistrationOtp(match { it.phone == "+919876543210" && it.email == "a@example.com" }) }
        }

    @Test
    fun anExistingAccountShowsTheServersMessage() =
        runTest {
            coEvery { customerApi.requestRegistrationOtp(any()) } returns errorResponse(409, "Please log in instead.")

            assertEquals(AuthOutcome.Failed("Please log in instead."), repository.requestRegistrationOtp("+919876543210", "a@example.com"))
        }

    @Test
    fun registeringStoresTheTokensAndStartsTheSession() =
        runTest {
            val sent = slot<CustomerRegisterRequest>()
            coEvery { customerApi.register1(capture(sent)) } returns tokens()

            val outcome = repository.register("+919876543210", "a@example.com", "123456", "Sunrise2026")

            assertEquals(AuthOutcome.Success, outcome)
            assertEquals("access-1", tokenStore.getAccessToken())
            assertEquals("refresh-1", tokenStore.getRefreshToken())
            assertTrue(sessionManager.isLoggedIn.value)
            assertEquals("123456", sent.captured.code)
            assertEquals("Sunrise2026", sent.captured.password)
        }

    @Test
    fun aWrongCodeLeavesHerSignedOut() =
        runTest {
            coEvery { customerApi.register1(any()) } returns errorResponse(400, "The code is invalid or has expired.")

            val outcome = repository.register("+919876543210", "a@example.com", "000000", "Sunrise2026")

            assertEquals(AuthOutcome.Failed("The code is invalid or has expired."), outcome)
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun loggingInSendsTheIdentifierAndStoresTheTokens() =
        runTest {
            val sent = slot<CustomerLoginRequest>()
            coEvery { customerApi.login(capture(sent)) } returns tokens()

            assertEquals(AuthOutcome.Success, repository.login("a@example.com", "Sunrise2026"))

            assertEquals("a@example.com", sent.captured.identifier)
            assertTrue(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aWrongPasswordIsAFriendlyFailureNotACrash() =
        runTest {
            coEvery { customerApi.login(any()) } returns errorResponse(401, "Authentication is required.")

            val outcome = repository.login("a@example.com", "nope")

            assertEquals(AuthOutcome.Failed("Authentication is required."), outcome)
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aNetworkErrorFailsClosedWithAHelpfulMessage() =
        runTest {
            coEvery { customerApi.login(any()) } throws IOException("offline")
            coEvery { customerApi.requestRegistrationOtp(any()) } throws IOException("offline")

            assertTrue((repository.login("a@example.com", "x") as AuthOutcome.Failed).message.contains("connection"))
            assertTrue(
                (repository.requestRegistrationOtp("+919876543210", "a@example.com") as AuthOutcome.Failed).message.contains("connection"),
            )
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aResponseWithoutTokensIsAFailure() =
        runTest {
            coEvery { customerApi.login(any()) } returns Response.success(ApiResponseTokenResponse(data = null))

            assertTrue(repository.login("a@example.com", "x") is AuthOutcome.Failed)
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun passwordResetRequestAndResetGoThroughTheCustomerEndpoints() =
        runTest {
            coEvery { customerApi.forgotPassword(any()) } returns message()
            coEvery { customerApi.resetPassword(any()) } returns message()

            assertEquals(AuthOutcome.Success, repository.requestPasswordReset("a@example.com"))
            assertEquals(AuthOutcome.Success, repository.resetPassword("a@example.com", "123456", "Moonlight2027"))

            coVerify {
                customerApi.resetPassword(
                    match {
                        it.email == "a@example.com" &&
                            it.code == "123456" &&
                            it.newPassword == "Moonlight2027"
                    },
                )
            }
        }

    @Test
    fun logoutRevokesTheRefreshTokenAndClearsTheSessionEvenIfTheCallFails() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.logout(any()) } throws IOException("no network")

            repository.logout()

            coVerify(exactly = 1) { authApi.logout(any()) }
            assertNull(tokenStore.getAccessToken())
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
