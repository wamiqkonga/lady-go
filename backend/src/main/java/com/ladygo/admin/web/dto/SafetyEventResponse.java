package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;

/** One line of an incident's append-only timeline. {@code outcome} is set only on a RESOLVED event. */
public record SafetyEventResponse(
        UUID id, String eventType, String actorName, String actorRole, String notes, String outcome, Instant createdAt) {
}
