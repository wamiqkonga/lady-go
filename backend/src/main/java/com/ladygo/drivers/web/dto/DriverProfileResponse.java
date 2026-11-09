package com.ladygo.drivers.web.dto;

import java.util.UUID;
import com.ladygo.drivers.domain.DriverVerificationStatus;

/** {@code fullName} is null until the captain has given one (the website then asks for it first). */
public record DriverProfileResponse(UUID id, UUID cityId, DriverVerificationStatus verificationStatus, String fullName) {
}
