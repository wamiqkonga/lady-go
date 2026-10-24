package com.ladygo.driver.account

import com.ladygo.driver.api.generated.models.DriverDocumentResponse
import com.ladygo.driver.api.generated.models.DriverRatingSummaryResponse
import com.ladygo.driver.api.generated.models.VehicleResponse
import java.math.BigDecimal
import java.math.RoundingMode

/** Below this many ratings the server withholds the distribution and tag counts (privacy), and so does the app. */
const val RATING_DETAIL_MIN_COUNT = 5

/** The rating tags a rider can give a driver; the server ignores any others it may have stored. */
enum class DriverTag { POLITE, SAFE_DRIVING, CLEAN_VEHICLE, ON_TIME }

data class RatingSummary(
    /** One decimal place, e.g. 4.8; null when the driver has no ratings yet. */
    val average: BigDecimal?,
    val count: Int,
    /** Stars 5 down to 1 mapped to how many ratings; null when the server did not send it (fewer than 5 ratings). */
    val distribution: Map<Int, Long>?,
    val tags: Map<DriverTag, Long>,
) {
    val hasDetail: Boolean get() = distribution != null
}

fun DriverRatingSummaryResponse.toSummary(): RatingSummary {
    val total = count ?: 0
    val detailed = total >= RATING_DETAIL_MIN_COUNT && distribution != null
    return RatingSummary(
        average = avg?.takeIf { total > 0 }?.setScale(1, RoundingMode.HALF_UP),
        count = total,
        distribution = if (detailed) (5 downTo 1).associateWith { distribution?.get(it.toString()) ?: 0L } else null,
        tags =
            if (detailed) {
                DriverTag.entries
                    .mapNotNull { tag -> recentTagCounts?.get(tag.name)?.takeIf { it > 0 }?.let { tag to it } }
                    .toMap()
            } else {
                emptyMap()
            },
    )
}

enum class ReviewStatus { APPROVED, PENDING, REJECTED, SUSPENDED, UNKNOWN }

data class DocumentItem(
    val type: DriverDocumentResponse.Type?,
    val status: ReviewStatus,
    /** The admin's note, shown for a rejected document. */
    val note: String?,
)

fun DriverDocumentResponse.toItem(): DocumentItem {
    val review =
        when (status) {
            DriverDocumentResponse.Status.APPROVED -> ReviewStatus.APPROVED
            DriverDocumentResponse.Status.PENDING -> ReviewStatus.PENDING
            DriverDocumentResponse.Status.REJECTED -> ReviewStatus.REJECTED
            null -> ReviewStatus.UNKNOWN
        }
    return DocumentItem(type = type, status = review, note = reviewNote?.takeIf { it.isNotBlank() && review == ReviewStatus.REJECTED })
}

data class VehicleInfo(
    val makeAndModel: String?,
    val plate: String?,
    val status: ReviewStatus,
)

fun VehicleResponse.toInfo(): VehicleInfo =
    VehicleInfo(
        makeAndModel =
            listOfNotNull(make, model)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .ifEmpty { null },
        plate = registrationNumber?.takeIf { it.isNotBlank() },
        status =
            when (verificationStatus) {
                VehicleResponse.VerificationStatus.APPROVED -> ReviewStatus.APPROVED
                VehicleResponse.VerificationStatus.PENDING -> ReviewStatus.PENDING
                VehicleResponse.VerificationStatus.REJECTED -> ReviewStatus.REJECTED
                VehicleResponse.VerificationStatus.SUSPENDED -> ReviewStatus.SUSPENDED
                null -> ReviewStatus.UNKNOWN
            },
    )

/** One section of the Me tab: loaded, or the reason it could not be. */
sealed interface Part<out T> {
    data class Loaded<T>(
        val value: T,
    ) : Part<T>

    data class Failed(
        val message: String,
    ) : Part<Nothing>
}

/** What the Me tab shows. [vehicle] loaded as null means the driver has not added a vehicle yet. */
data class AccountOverview(
    val rating: Part<RatingSummary>,
    val documents: Part<List<DocumentItem>>,
    val vehicle: Part<VehicleInfo?>,
) {
    val allFailed: Boolean get() = rating is Part.Failed && documents is Part.Failed && vehicle is Part.Failed
    val firstError: String? get() = listOf(rating, documents, vehicle).filterIsInstance<Part.Failed>().firstOrNull()?.message
}
