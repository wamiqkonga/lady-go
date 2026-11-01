package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SafetyIncidentResolveRequest(@NotNull SafetyOutcome outcome, @Size(max = 2000) String note) {
}
