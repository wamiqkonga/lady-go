package com.ladygo.admin.web.dto;

import com.ladygo.users.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The role is checked against the staff roles in the service; the reason goes into the audit entry. */
public record AdminUserInviteRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 120) String name,
        @NotNull UserRole role,
        @NotBlank @Size(max = 500) String reason) {
}
