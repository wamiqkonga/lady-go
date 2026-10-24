package com.ladygo.customers.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * phone is deliberately absent - never editable here (docs/api/customer-profile.md C1/C2). email is
 * optional, unverified, contact-info-only (C2) - never an auth/recovery factor.
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 120) String name,
        @Email @Size(max = 254) String email,
        UUID defaultCityId) {
}
