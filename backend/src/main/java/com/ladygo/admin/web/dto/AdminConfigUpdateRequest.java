package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminConfigUpdateRequest(@NotBlank String value) {
}
