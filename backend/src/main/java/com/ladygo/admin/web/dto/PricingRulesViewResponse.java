package com.ladygo.admin.web.dto;

import java.util.List;
import java.util.UUID;
import com.ladygo.vehicles.domain.VehicleType;

/**
 * Fares for one city and vehicle type. {@code latestRuleId} is the version a new change must echo back as
 * {@code expectedLatestRuleId} (null when no fare exists yet), so two admins cannot both overwrite the same fare.
 */
public record PricingRulesViewResponse(
        UUID cityId, VehicleType vehicleType, PricingRuleResponse current, PricingRuleResponse scheduled,
        UUID latestRuleId, List<PricingRuleResponse> history, PricingRuleLimitsResponse limits, int largeChangePct) {
}
