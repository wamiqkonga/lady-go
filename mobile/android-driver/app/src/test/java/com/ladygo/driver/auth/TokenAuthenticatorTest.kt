package com.ladygo.driver.auth

import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseTokenResponse
import com.ladygo.driver.api.generated.models.TokenResponse
import com.ladygo.driver.testing.FakeTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response as RetrofitResponse

class TokenAuthenticatorTest {
    private val authApi = mockk<AuthControllerApi>()
    private val tokenStore = FakeTokenStore()
    private val sessionManager = SessionManager(tokenStore)
    private val authenticator = TokenAuthenticator(tokenStore, sessionManager, authApi)

    private fun requestWithAuth(token: String): Request =
        Request
            .Builder()
            .url("http://localhost/api/v1/drivers/me")
            .header("Authorization", "Bearer $token")
            .build()

    private fun unauthorizedResponse(request: Request): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody(null))
            .build()

    @Test
    fun givesUpImmediatelyWithNoRefreshTokenStored() {
        val request = requestWithAuth("expired-access")
        val response = unauthorizedResponse(request)

        val result = authenticator.authenticate(null, response)

        assertNull(result)
        coVerify(exactly = 0) { authApi.refresh(any()) }
    }

    @Test
    fun givesUpAfterAnAlreadyRetriedRequestFailsAgain() {
        tokenStore.setTokens("access-1", "refresh-1")
        val request = requestWithAuth("access-1")
        // OkHttp's real Response.Builder requires a prior response's own body to already be null
        // (it models an already-consumed response in a redirect/retry chain) and there's no way to
        // build a standalone Response satisfying that from the public API, so this one case mocks
        // Response directly - [TokenAuthenticator.authenticate] only ever reads `.request` and
        // `.priorResponse` off it, never the body, so the mock only needs those two.
        val firstFailure = mockk<Response>()
        val secondFailure = mockk<Response>()
        every { secondFailure.request } returns request
        every { secondFailure.priorResponse } returns firstFailure
        every { firstFailure.priorResponse } returns null

        val result = authenticator.authenticate(null, secondFailure)

        assertNull(result)
        coVerify(exactly = 0) { authApi.refresh(any()) }
    }

    @Test
    fun refreshesAndRetriesWithTheNewAccessToken() {
        tokenStore.setTokens("access-1", "refresh-1")
        coEvery { authApi.refresh(any()) } returns
            RetrofitResponse.success(ApiResponseTokenResponse(data = TokenResponse("access-2", "refresh-2")))
        val request = requestWithAuth("access-1")

        val result = authenticator.authenticate(null, unauthorizedResponse(request))

        assertEquals("Bearer access-2", result?.header("Authorization"))
        assertEquals("access-2", tokenStore.getAccessToken())
        assertEquals("refresh-2", tokenStore.getRefreshToken())
    }

    @Test
    fun forcesLogoutWhenTheRefreshTokenItselfIsRejected() {
        tokenStore.setTokens("access-1", "refresh-1")
        sessionManager.onLoggedIn()
        coEvery { authApi.refresh(any()) } returns
            RetrofitResponse.error(401, "{}".toResponseBody("application/json".toMediaType()))
        val request = requestWithAuth("access-1")

        val result = authenticator.authenticate(null, unauthorizedResponse(request))

        assertNull(result)
        assertNull(tokenStore.getAccessToken())
        assertFalse(sessionManager.isLoggedIn.value)
    }

    /** Single-flight: a second thread's failing request used the *old* token, but the store
     *  already holds a newer one (another thread's [authenticate] call won the race) - it must
     *  retry with that instead of calling refresh a second time. */
    @Test
    fun reusesAnAlreadyRotatedTokenWithoutRefreshingAgain() {
        tokenStore.setTokens("access-2", "refresh-2")
        val requestThatFailedWithTheOldToken = requestWithAuth("access-1")

        val result = authenticator.authenticate(null, unauthorizedResponse(requestThatFailedWithTheOldToken))

        assertEquals("Bearer access-2", result?.header("Authorization"))
        coVerify(exactly = 0) { authApi.refresh(any()) }
    }
}
