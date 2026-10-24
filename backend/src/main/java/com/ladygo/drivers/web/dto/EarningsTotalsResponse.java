package com.ladygo.drivers.web.dto;

/** Money in minor units. {@code commissionConfigured=false} means no rate is set, so commission is 0 and net equals gross. */
public record EarningsTotalsResponse(
        long rides, long gross, long commission, long net, String currency, long cashCollected,
        long onlineCollected, boolean commissionConfigured) {
}
