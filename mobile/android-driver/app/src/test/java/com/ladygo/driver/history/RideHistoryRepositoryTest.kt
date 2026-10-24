package com.ladygo.driver.history

import com.ladygo.driver.api.generated.apis.DriverEarningsControllerApi
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseDriverRideHistoryItemResponse
import com.ladygo.driver.api.generated.models.DriverRideHistoryItemResponse
import com.ladygo.driver.api.generated.models.PageResponseDriverRideHistoryItemResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.UUID

class RideHistoryRepositoryTest {
    private val api = mockk<DriverEarningsControllerApi>()
    private val repository = RideHistoryRepository(api)

    private fun page(
        rows: Int,
        totalPages: Int?,
    ) = Response.success(
        ApiResponsePageResponseDriverRideHistoryItemResponse(
            data =
                PageResponseDriverRideHistoryItemResponse(
                    content = List(rows) { DriverRideHistoryItemResponse(bookingId = UUID.randomUUID(), status = "COMPLETED") },
                    totalPages = totalPages,
                ),
        ),
    )

    @Test
    fun requestsThePageWithSize20AndSaysWhetherMoreRemain() =
        runTest {
            coEvery { api.listMyRides(0, 20, null) } returns page(20, totalPages = 2)
            coEvery { api.listMyRides(1, 20, null) } returns page(3, totalPages = 2)

            val first = repository.fetchPage(0) as RideHistoryOutcome.Success
            val last = repository.fetchPage(1) as RideHistoryOutcome.Success

            assertEquals(20, first.items.size)
            assertTrue(first.hasMore)
            assertFalse(last.hasMore)
        }

    @Test
    fun withoutTotalPagesAFullPageMeansMaybeMore() =
        runTest {
            coEvery { api.listMyRides(0, 20, null) } returns page(20, totalPages = null)
            assertTrue((repository.fetchPage(0) as RideHistoryOutcome.Success).hasMore)
        }

    @Test
    fun failuresCarryTheServerMessageOrAFriendlyOne() =
        runTest {
            val body = """{"success":false,"error":{"code":"X","message":"Nope"}}"""
            coEvery { api.listMyRides(0, 20, null) } returns Response.error(500, body.toResponseBody("application/json".toMediaType()))
            assertEquals(RideHistoryOutcome.Failed("Nope"), repository.fetchPage(0))

            coEvery { api.listMyRides(1, 20, null) } throws IOException()
            assertEquals(
                RideHistoryOutcome.Failed("Could not reach the server. Check your connection and try again."),
                repository.fetchPage(1),
            )
        }
}
