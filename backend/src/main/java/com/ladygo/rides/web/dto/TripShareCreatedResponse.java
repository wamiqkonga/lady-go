package com.ladygo.rides.web.dto;

import java.time.Instant;
import java.util.UUID;

/** docs/api/safety.md section 4. {@code token} is shown exactly once, here - only its hash is ever
 *  persisted (same "show once" principle as a refresh token). */
public record TripShareCreatedResponse(UUID id, String token, Instant expiresAt) {
}
