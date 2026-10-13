package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code reason} is required (docs/product/admin-web-design.md section 6 update) - a config change
 *  can silently alter safety/money-relevant behaviour app-wide, and "someone changed it" with no
 *  record of why is strictly worse than the same gap on a single driver's document review. */
public record AdminConfigUpdateRequest(@NotBlank String value, @NotBlank String reason) {
}
