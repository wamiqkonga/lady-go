package com.ladygo.verification.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DeclarationAcceptRequest(@NotBlank String declarationVersion) {
}
