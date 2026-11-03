package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;

public record SafetyIncidentSummaryResponse(
        UUID id,
        UUID rideId,
        String type,
        String severity,
        String status,
        SafetyReporterResponse reporter,
        boolean hasLocation,
        Instant createdAt,
        Instant acknowledgedAt) {
}
