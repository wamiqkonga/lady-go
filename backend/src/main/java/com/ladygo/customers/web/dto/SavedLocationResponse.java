package com.ladygo.customers.web.dto;

import java.time.Instant;
import java.util.UUID;

public record SavedLocationResponse(
        UUID id, String label, String address, double latitude, double longitude, Instant createdAt) {
}
