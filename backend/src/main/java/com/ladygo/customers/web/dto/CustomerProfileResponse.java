package com.ladygo.customers.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID id,
        String phone,
        Instant phoneVerifiedAt,
        String name,
        String email,
        String profilePhotoKey,
        UUID defaultCityId,
        Map<String, Object> preferences) {
}
