package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.VerificationReason;

public record DriverDocumentAdminResponse(
        UUID id, UUID driverId, String driverName, String driverPhoneMasked, DocumentType type,
        DocumentStatus status, VerificationReason reason, String documentNumber, String reviewNote,
        LocalDate expiresAt, Instant createdAt) {
}
