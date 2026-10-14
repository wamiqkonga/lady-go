package com.ladygo.verification.web.dto;

import java.time.Instant;

/** The versioned consent text a client must show before the subject can accept it (docs/api/
 *  driver-onboarding.md section 2), plus whether this subject has already accepted the current
 *  version - closes the gap where nothing exposed {@code DeclarationVersion.text} at all. */
public record DeclarationStatusResponse(
        String currentVersion, String currentText, boolean accepted, String acceptedVersion, Instant acceptedAt) {
}
