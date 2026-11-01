package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SafetyIncidentNoteRequest(@NotBlank @Size(max = 2000) String note) {
}
