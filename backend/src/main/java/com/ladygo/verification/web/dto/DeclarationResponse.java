package com.ladygo.verification.web.dto;

import java.time.Instant;

public record DeclarationResponse(String declarationVersion, Instant acceptedAt) {
}
