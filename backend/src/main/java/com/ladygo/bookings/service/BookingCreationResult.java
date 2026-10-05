package com.ladygo.bookings.service;

import com.ladygo.bookings.web.dto.BookingDetailResponse;

/** docs/api/booking.md section 2 (B3): whether this request created the booking or replayed an existing one. */
public record BookingCreationResult(BookingDetailResponse booking, boolean created) {
}
