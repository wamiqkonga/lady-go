package com.ladygo.driver.account

import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import com.ladygo.driver.api.generated.models.DriverRatingSummaryResponse
import com.ladygo.driver.api.generated.models.VehicleResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AccountModelsTest {
    @Test
    fun belowFiveRatingsKeepsAverageAndCountButNoDistributionOrTags() {
        // The server's low-count shape: keys 1..5 present but all zero, tags empty.
        val summary =
            DriverRatingSummaryResponse(
                avg = BigDecimal("4.67"),
                count = 3,
                distribution = mapOf("1" to 0L, "2" to 0L, "3" to 0L, "4" to 0L, "5" to 0L),
                recentTagCounts = emptyMap(),
            ).toSummary()

        assertEquals(BigDecimal("4.7"), summary.average)
        assertEquals(3, summary.count)
        assertNull(summary.distribution)
        assertFalse(summary.hasDetail)
        assertTrue(summary.tags.isEmpty())
    }

    @Test
    fun belowFiveIsWithheldEvenIfAServerBugSentNumbers() {
        val summary =
            DriverRatingSummaryResponse(
                avg = BigDecimal("1.0"),
                count = 4,
                distribution = mapOf("1" to 4L),
                recentTagCounts = mapOf("POLITE" to 4L),
            ).toSummary()

        assertNull(summary.distribution)
        assertTrue(summary.tags.isEmpty())
    }

    @Test
    fun fiveOrMoreShowsTheDistributionFiveDownToOneAndKnownTagsOnly() {
        val summary =
            DriverRatingSummaryResponse(
                avg = BigDecimal("4.50"),
                count = 6,
                distribution = mapOf("5" to 4L, "4" to 1L, "3" to 1L),
                recentTagCounts = mapOf("POLITE" to 3L, "ON_TIME" to 0L, "MYSTERY_TAG" to 9L),
            ).toSummary()

        assertEquals(BigDecimal("4.5"), summary.average)
        assertEquals(listOf(5, 4, 3, 2, 1), summary.distribution!!.keys.toList())
        assertEquals(mapOf(5 to 4L, 4 to 1L, 3 to 1L, 2 to 0L, 1 to 0L), summary.distribution)
        assertEquals(mapOf(DriverTag.POLITE to 3L), summary.tags)
    }

    @Test
    fun noRatingsHasNoAverage() {
        val summary = DriverRatingSummaryResponse(avg = BigDecimal.ZERO, count = 0).toSummary()

        assertNull(summary.average)
        assertEquals(0, summary.count)
    }

    @Test
    fun aRejectedDocumentKeepsTheAdminsNoteAndOthersDoNot() {
        val rejected =
            DriverDocumentResponse(
                type = DriverDocumentResponse.Type.LICENSE,
                status = DriverDocumentResponse.Status.REJECTED,
                reviewNote = "Photo is blurry",
            ).toItem()
        val approved =
            DriverDocumentResponse(
                type = DriverDocumentResponse.Type.INSURANCE,
                status = DriverDocumentResponse.Status.APPROVED,
                reviewNote = "ok",
            ).toItem()
        val pending =
            DriverDocumentResponse(
                type = DriverDocumentResponse.Type.PERMIT,
                status = DriverDocumentResponse.Status.PENDING,
            ).toItem()

        assertEquals(DocumentItem(DriverDocumentResponse.Type.LICENSE, ReviewStatus.REJECTED, "Photo is blurry"), rejected)
        assertEquals(ReviewStatus.APPROVED, approved.status)
        assertNull(approved.note)
        assertEquals(ReviewStatus.PENDING, pending.status)
    }

    @Test
    fun aBlankRejectionNoteIsDropped() {
        val item = DriverDocumentResponse(status = DriverDocumentResponse.Status.REJECTED, reviewNote = "  ").toItem()

        assertNull(item.note)
    }

    @Test
    fun theVehicleShowsMakeModelPlateAndStatus() {
        val vehicle =
            VehicleResponse(
                make = "Maruti",
                model = "Dzire",
                registrationNumber = "JK01AB1234",
                verificationStatus = VehicleResponse.VerificationStatus.SUSPENDED,
            ).toInfo()

        assertEquals(VehicleInfo("Maruti Dzire", "JK01AB1234", ReviewStatus.SUSPENDED), vehicle)
    }

    @Test
    fun aVehicleWithMissingFieldsHasNullsNotBlanks() {
        val vehicle = VehicleResponse(make = " ", model = null, registrationNumber = "").toInfo()

        assertNull(vehicle.makeAndModel)
        assertNull(vehicle.plate)
        assertEquals(ReviewStatus.UNKNOWN, vehicle.status)
    }
}
