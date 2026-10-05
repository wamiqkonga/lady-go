package com.ladygo.drivers.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;

public record DriverDocumentResponse(
        UUID id, DocumentType type, DocumentStatus status, String reviewNote, Instant reviewedAt, Instant createdAt) {
}
