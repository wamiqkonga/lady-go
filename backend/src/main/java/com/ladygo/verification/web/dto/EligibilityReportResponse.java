package com.ladygo.verification.web.dto;

import java.time.Instant;
import java.util.UUID;

public record EligibilityReportResponse(UUID id, Instant createdAt) {
}
