package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotNull;
import com.ladygo.verification.domain.EligibilityReportStatus;

public record EligibilityReportResolveRequest(@NotNull EligibilityReportStatus status, String note) {
}
