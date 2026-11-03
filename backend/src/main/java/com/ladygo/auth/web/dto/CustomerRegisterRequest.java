package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Step 2: the emailed code plus the password she chooses. Creates the account and signs her in. */
public record CustomerRegisterRequest(
        @NotNull @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
        @NotBlank @Size(min = 8, max = 72) String password,
        @Size(max = 100) String deviceId) {
}
