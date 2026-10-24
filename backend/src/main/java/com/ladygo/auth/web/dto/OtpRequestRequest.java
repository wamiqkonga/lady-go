package com.ladygo.auth.web.dto;

import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.users.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OtpRequestRequest(
        @NotNull @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @NotNull OtpPurpose purpose,
        @NotNull UserRole intendedRole,
        /** Only checked when {@code otp.challenge.enabled} is on (default off) - see
         *  {@code com.ladygo.auth.challenge.ChallengeVerifier}. */
        String challengeToken,
        /** Required when codes are delivered by email ({@code ladygo.otp.channel=email}, MVP 1); ignored
         *  when they are sent by SMS. */
        @Email @Size(max = 254) String email) {
}
