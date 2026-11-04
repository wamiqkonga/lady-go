package com.ladygo.support.web.dto;

import java.time.Instant;
import java.util.UUID;

/** Admin queue row. Coordinates are the reporter's own SOS snapshot (docs/api/safety.md section 6). */
public record SafetyIncidentResponse(
        UUID id,
        UUID reporterId,
        UUID bookingId,
        UUID rideId,
        String type,
        String severity,
        String status,
        Double latitude,
        Double longitude,
        String notes,
        Instant createdAt) {
}
