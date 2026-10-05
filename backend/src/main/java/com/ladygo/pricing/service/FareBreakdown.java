package com.ladygo.pricing.service;

/** Minor units. baseFare + distanceFare + timeFare + serviceFee always sums to the total fare. */
public record FareBreakdown(long baseFare, long distanceFare, long timeFare, long serviceFee) {
}
