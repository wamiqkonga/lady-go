package com.ladygo.customers.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmergencyContactRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @Size(max = 50) String relationship) {
}
