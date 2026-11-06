package com.ladygo.common.exception;

/** A fare change cannot be made in the current state (a change is already scheduled, the rule is in force, ...). */
public class PricingRuleConflictException extends ApiException {

    public PricingRuleConflictException(String safeMessage) {
        super(ErrorCode.PRICING_RULE_CONFLICT, safeMessage);
    }
}
