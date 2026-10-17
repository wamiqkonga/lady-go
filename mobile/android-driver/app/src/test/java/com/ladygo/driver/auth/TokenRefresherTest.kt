package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseTokenResponse
import com.ladygo.driver.api.generated.models.TokenResponse
import com.ladygo.driver.testing.FakeTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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

class TokenRefresherTest {
    private val authApi = mockk<AuthControllerApi>()
    private val tokenStore = FakeTokenStore()
    private val sessionManager = SessionManager(tokenStore)
    private val refresher = TokenRefresher(tokenStore, sessionManager, authApi)

    @Test
    fun returnsNullWithNoRefreshTokenStoredAndNeverCallsTheNetwork() =
        runTest {
            val result = refresher.refresh(currentAccessToken = "expired")

            assertNull(result)
            coVerify(exactly = 0) { authApi.refresh(any()) }
        }

    @Test
    fun rotatesAndPersistsBothTokensOnSuccess() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            coEvery { authApi.refresh(any()) } returns
                Response.success(ApiResponseTokenResponse(data = TokenResponse("access-2", "refresh-2")))

            val result = refresher.refresh(currentAccessToken = "access-1")

            assertEquals("access-2", result)
            assertEquals("access-2", tokenStore.getAccessToken())
            assertEquals("refresh-2", tokenStore.getRefreshToken())
        }

    @Test
    fun forcesLogoutWhenTheRefreshTokenItselfIsRejected() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.refresh(any()) } returns
                Response.error(401, "{}".toResponseBody("application/json".toMediaType()))

            val result = refresher.refresh(currentAccessToken = "access-1")

            assertNull(result)
            assertNull(tokenStore.getAccessToken())
            assertFalse(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aNetworkFailureIsTransientAndDoesNotLogOut() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.refresh(any()) } throws IOException("no network")

            val result = refresher.refresh(currentAccessToken = "access-1")

            assertNull(result)
            assertEquals("access-1", tokenStore.getAccessToken())
            assertTrue(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aServerErrorIsTransientAndDoesNotLogOut() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.refresh(any()) } returns
                Response.error(500, "{}".toResponseBody("application/json".toMediaType()))

            val result = refresher.refresh(currentAccessToken = "access-1")

            assertNull(result)
            assertEquals("access-1", tokenStore.getAccessToken())
            assertTrue(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aSuccessfulResponseWithNoUsableTokensIsTransientAndDoesNotLogOut() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.refresh(any()) } returns Response.success(ApiResponseTokenResponse(data = null))

            val result = refresher.refresh(currentAccessToken = "access-1")

            assertNull(result)
            assertTrue(sessionManager.isLoggedIn.value)
        }

    @Test
    @Suppress("SwallowedException") // the catch below is the assertion itself, not an accidental swallow
    fun cancellationIsNeverSwallowedAsARejection() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            sessionManager.onLoggedIn()
            coEvery { authApi.refresh(any()) } throws CancellationException("coroutine cancelled")

            var threw = false
            try {
                refresher.refresh(currentAccessToken = "access-1")
            } catch (e: CancellationException) {
                threw = true
            }

            assertTrue("refresh() must rethrow CancellationException, not swallow it", threw)
            assertTrue(sessionManager.isLoggedIn.value)
        }

    @Test
    fun aCallerThatAlreadySeesARotatedTokenSkipsTheNetworkEntirely() =
        runTest {
            tokenStore.setTokens("access-2", "refresh-2")

            val result = refresher.refresh(currentAccessToken = "access-1")

            assertEquals("access-2", result)
            coVerify(exactly = 0) { authApi.refresh(any()) }
        }

    @Test
    fun concurrentCallersOnlyTriggerOneRealRefresh() =
        runTest {
            tokenStore.setTokens("access-1", "refresh-1")
            coEvery { authApi.refresh(any()) } returns
                Response.success(ApiResponseTokenResponse(data = TokenResponse("access-2", "refresh-2")))

            val first = async { refresher.refresh(currentAccessToken = "access-1") }
            val second = async { refresher.refresh(currentAccessToken = "access-1") }

            assertEquals("access-2", first.await())
            assertEquals("access-2", second.await())
            coVerify(exactly = 1) { authApi.refresh(any()) }
        }
}
