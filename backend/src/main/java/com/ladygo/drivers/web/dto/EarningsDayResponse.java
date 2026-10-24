package com.ladygo.drivers.web.dto;

import java.time.LocalDate;

/** One calendar day in the city's time zone; same figures as the totals. */
public record EarningsDayResponse(
        LocalDate date, long rides, long gross, long commission, long net, String currency, long cashCollected,
        long onlineCollected, boolean commissionConfigured) {
}
