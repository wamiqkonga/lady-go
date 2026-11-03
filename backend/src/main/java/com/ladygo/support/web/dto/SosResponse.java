package com.ladygo.support.web.dto;

import java.time.Instant;
import java.util.UUID;

public record SosResponse(UUID incidentId, Instant createdAt) {
}
