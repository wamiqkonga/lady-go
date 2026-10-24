package com.ladygo.drivers.web.dto;

import java.util.UUID;
import com.ladygo.drivers.domain.DriverVerificationStatus;

public record DriverProfileResponse(UUID id, UUID cityId, DriverVerificationStatus verificationStatus) {
}
