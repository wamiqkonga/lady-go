package com.ladygo.bookings.web.dto;

import java.time.Instant;

/**
 * What cancelling right now would cost, so the rider is told before she confirms. {@code fee} is in the smallest
 * currency unit, like the fare. {@code freeUntil} is set while the free-cancellation window after the driver
 * accepted is still open.
 */
public record CancellationPreviewResponse(boolean cancellable, long fee, String currency, Instant freeUntil) {
}
