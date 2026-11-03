package com.ladygo.admin.web.dto;

import com.ladygo.users.domain.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminUserRoleRequest(@NotNull UserRole role, @NotBlank @Size(max = 500) String reason) {
}
