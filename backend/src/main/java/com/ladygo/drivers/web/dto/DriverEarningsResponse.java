package com.ladygo.drivers.web.dto;

import java.time.LocalDate;
import java.util.List;

/** {@code from} and {@code to} are inclusive dates in {@code timeZone} (the driver's city time zone). */
public record DriverEarningsResponse(
        LocalDate from, LocalDate to, String timeZone, EarningsTotalsResponse totals, List<EarningsDayResponse> days) {
}
