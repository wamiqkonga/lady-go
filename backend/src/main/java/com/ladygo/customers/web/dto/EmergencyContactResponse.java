package com.ladygo.customers.web.dto;

import java.time.Instant;
import java.util.UUID;

public record EmergencyContactResponse(
        UUID id, String name, String phone, String relationship, Instant createdAt) {
}
