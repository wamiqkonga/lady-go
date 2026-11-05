package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.drivers.domain.DriverVerificationStatus;

/**
 * One captain in the verification queue (docs/product/admin-flows.md section 4). The phone is always masked.
 * {@code submittedAt} is the captain's first upload (a document, the vehicle or the declaration), falling back to
 * the account's creation time; {@code waitingSeconds} and {@code overdue} are measured from it.
 */
public record DriverVerificationQueueItemResponse(
        UUID driverId,
        String driverName,
        String phoneMasked,
        String city,
        String vehicleNumber,
        DriverVerificationStatus verificationStatus,
        RequiredItemCounts requiredItems,
        boolean optionalDocumentsProvided,
        boolean nameMissing,
        boolean readyForReview,
        Instant submittedAt,
        long waitingSeconds,
        boolean overdue) {

    /** Required items are the full name (counted approved once given, missing otherwise), the vehicle with its RC photo, the selfie, the self-declaration (and police verification when switched on). */
    public record RequiredItemCounts(int approved, int pending, int rejected, int missing) {
    }
}
