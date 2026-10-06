package com.ladygo.admin.web.dto;

import java.util.List;
import java.util.UUID;
import com.ladygo.drivers.domain.DriverVerificationStatus;

/** Everything an admin needs to decide on a driver, in one view (docs/api/driver-onboarding.md section 3). */
public record DriverReviewSummaryResponse(
        UUID driverId,
        DriverVerificationStatus verificationStatus,
        boolean declarationAccepted,
        VehicleAdminResponse vehicle,
        List<DriverDocumentAdminResponse> documents,
        boolean readyForApproval,
        List<String> missingRequirements) {
}
