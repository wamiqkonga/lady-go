package com.ladygo.driver.earnings

import com.ladygo.driver.api.generated.apis.DriverEarningsControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseDriverEarningsResponse
import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

class EarningsRepositoryTest {
    private val api = mockk<DriverEarningsControllerApi>()
    private val repository = EarningsRepository(api)
    private val range = DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25))

    @Test
    fun sendsTheDatesAsIsoLocalDatesAndReturnsTheServersNumbers() =
        runTest {
            val earnings = DriverEarningsResponse(from = "2026-10-19", to = "2026-10-25")
            coEvery { api.getMyEarnings("2026-10-19", "2026-10-25") } returns
                Response.success(ApiResponseDriverEarningsResponse(data = earnings))

            assertEquals(EarningsOutcome.Success(earnings), repository.fetch(range))
        }

    @Test
    fun aRejectionCarriesTheServersRealMessage() =
        runTest {
            val body = """{"success":false,"error":{"code":"VALIDATION_ERROR","message":"Range is longer than 31 days."}}"""
            coEvery { api.getMyEarnings(any(), any()) } returns
                Response.error(400, body.toResponseBody("application/json".toMediaType()))

            assertEquals(EarningsOutcome.Failed("Range is longer than 31 days."), repository.fetch(range))
        }

    @Test
    fun anUnparseableErrorBodyFallsBackToAPlainMessage() =
        runTest {
            coEvery { api.getMyEarnings(any(), any()) } returns Response.error(502, "<html>".toResponseBody("text/html".toMediaType()))

            assertEquals(EarningsOutcome.Failed("Could not load your earnings."), repository.fetch(range))
        }

    @Test
    fun noConnectionIsAFriendlyFailure() =
        runTest {
            coEvery { api.getMyEarnings(any(), any()) } throws IOException("offline")

            assertEquals(
                EarningsOutcome.Failed("Could not reach the server. Check your connection and try again."),
                repository.fetch(range),
            )
        }

    @Test
    fun anEmptySuccessBodyIsAFailureNotACrash() =
        runTest {
            coEvery { api.getMyEarnings(any(), any()) } returns Response.success(ApiResponseDriverEarningsResponse(data = null))

            assertEquals(EarningsOutcome.Failed("Unexpected response from the server."), repository.fetch(range))
        }
}
