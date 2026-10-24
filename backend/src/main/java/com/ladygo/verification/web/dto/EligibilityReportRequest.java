package com.ladygo.verification.web.dto;

import jakarta.validation.constraints.NotBlank;

public record EligibilityReportRequest(@NotBlank String reason) {
}
