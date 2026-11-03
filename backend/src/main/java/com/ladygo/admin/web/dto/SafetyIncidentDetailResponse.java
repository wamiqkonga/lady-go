package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything the safety responder needs. Only a masked phone is shown; the booking id links to the existing ride screen. */
public record SafetyIncidentDetailResponse(
        UUID id,
        UUID rideId,
        UUID bookingId,
        String type,
        String severity,
        String status,
        SafetyReporterResponse reporter,
        SafetyLocationResponse location,
        String notes,
        boolean legalHold,
        Instant createdAt,
        Instant acknowledgedAt,
        Instant resolvedAt,
        List<SafetyEventResponse> timeline) {
}
