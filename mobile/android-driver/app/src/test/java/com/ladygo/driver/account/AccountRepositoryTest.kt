package com.ladygo.driver.account

import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.apis.RatingControllerApi
import com.ladygo.driver.api.generated.models.ApiResponseDriverProfileResponse
import com.ladygo.driver.api.generated.models.ApiResponseDriverRatingSummaryResponse
import com.ladygo.driver.api.generated.models.ApiResponseListDriverDocumentResponse
import com.ladygo.driver.api.generated.models.ApiResponseVehicleResponse
import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import com.ladygo.driver.api.generated.models.DriverProfileResponse
import com.ladygo.driver.api.generated.models.DriverRatingSummaryResponse
import com.ladygo.driver.api.generated.models.VehicleResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

class AccountRepositoryTest {
    private val ratingApi = mockk<RatingControllerApi>()
    private val driverApi = mockk<DriverControllerApi>()
    private val repository = AccountRepository(ratingApi, driverApi)

    private fun json(
        code: Int,
        body: String,
    ) = body.toResponseBody("application/json".toMediaType()).let { code to it }

    private fun stubAllOk() {
        coEvery { ratingApi.getMyRatingSummary() } returns
            Response.success(ApiResponseDriverRatingSummaryResponse(data = DriverRatingSummaryResponse(avg = BigDecimal("4.0"), count = 1)))
        coEvery { driverApi.listDocuments() } returns
            Response.success(
                ApiResponseListDriverDocumentResponse(
                    data =
                        listOf(
                            DriverDocumentResponse(
                                type = DriverDocumentResponse.Type.LICENSE,
                                status = DriverDocumentResponse.Status.REJECTED,
                                reviewNote = "Expired",
                            ),
                        ),
                ),
            )
        coEvery { driverApi.getVehicle() } returns
            Response.success(
                ApiResponseVehicleResponse(
                    data =
                        VehicleResponse(
                            make = "Tata",
                            model = "Tiago",
                            registrationNumber = "JK02",
                            verificationStatus = VehicleResponse.VerificationStatus.APPROVED,
                        ),
                ),
            )
    }

    @Test
    fun combinesRatingDocumentsAndVehicle() =
        runTest {
            stubAllOk()

            val overview = repository.fetchOverview()

            assertEquals(1, (overview.rating as Part.Loaded).value.count)
            assertEquals(
                listOf(DocumentItem(DriverDocumentResponse.Type.LICENSE, ReviewStatus.REJECTED, "Expired")),
                (overview.documents as Part.Loaded).value,
            )
            assertEquals(VehicleInfo("Tata Tiago", "JK02", ReviewStatus.APPROVED), (overview.vehicle as Part.Loaded).value)
        }

    @Test
    fun noVehicleOnFileIsANormalEmptyStateNotAnError() =
        runTest {
            stubAllOk()
            val (code, body) = json(404, """{"success":false,"error":{"code":"NOT_FOUND","message":"No vehicle"}}""")
            coEvery { driverApi.getVehicle() } returns Response.error(code, body)

            val overview = repository.fetchOverview()

            assertNull((overview.vehicle as Part.Loaded).value)
        }

    @Test
    fun oneFailingSectionDoesNotHideTheOthers() =
        runTest {
            stubAllOk()
            val (code, body) = json(500, """{"success":false,"error":{"code":"X","message":"Documents are down"}}""")
            coEvery { driverApi.listDocuments() } returns Response.error(code, body)

            val overview = repository.fetchOverview()

            assertEquals(Part.Failed("Documents are down"), overview.documents)
            assertTrue(overview.rating is Part.Loaded)
            assertTrue(overview.vehicle is Part.Loaded)
            assertEquals(false, overview.allFailed)
            assertEquals("Documents are down", overview.firstError)
        }

    @Test
    fun noConnectionFailsEverySectionWithAFriendlyMessage() =
        runTest {
            coEvery { ratingApi.getMyRatingSummary() } throws IOException("offline")
            coEvery { driverApi.listDocuments() } throws IOException("offline")
            coEvery { driverApi.getVehicle() } throws IOException("offline")

            val overview = repository.fetchOverview()

            assertTrue(overview.allFailed)
            assertEquals("Could not reach the server. Check your connection and try again.", overview.firstError)
        }

    @Test
    fun theDriverIdComesFromTheProfileAndIsNullWhenItCannotBeRead() =
        runTest {
            val id = UUID.randomUUID()
            coEvery { driverApi.getProfile1() } returns
                Response.success(ApiResponseDriverProfileResponse(data = DriverProfileResponse(id = id)))
            assertEquals(id.toString(), repository.fetchDriverId())

            coEvery { driverApi.getProfile1() } throws IOException("offline")
            assertNull(repository.fetchDriverId())
        }
}
