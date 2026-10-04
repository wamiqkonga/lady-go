package com.ladygo.auth.web.dto;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.users.domain.UserRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OtpRequestRequest(
        @NotNull @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotNull OtpPurpose purpose,
        @NotNull UserRole intendedRole) {
}
