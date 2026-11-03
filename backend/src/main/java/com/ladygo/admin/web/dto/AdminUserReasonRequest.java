package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Deactivate and reactivate: the written reason is mandatory and goes into the audit entry. */
public record AdminUserReasonRequest(@NotBlank @Size(max = 500) String reason) {
}
