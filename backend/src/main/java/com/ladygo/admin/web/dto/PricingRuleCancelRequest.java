package com.ladygo.admin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PricingRuleCancelRequest(@NotBlank @Size(max = 500) String reason) {
}
