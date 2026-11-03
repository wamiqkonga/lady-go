package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Step 1 of customer registration: both the phone number and the email are required; the code goes to the email. */
public record RegisterOtpRequest(
        @NotNull @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotBlank @Email @Size(max = 254) String email) {
}
