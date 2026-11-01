package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The reason is mandatory and goes into the audit entry. */
public record AdminUnlockRequest(@NotBlank @Size(max = 500) String reason) {
}
