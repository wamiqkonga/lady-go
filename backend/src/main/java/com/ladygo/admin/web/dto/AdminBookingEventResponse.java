package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import com.ladygo.bookings.domain.BookingStatus;
import com.ladygo.common.domain.ActorType;

/** One row of a ride's timeline (docs/product/admin-web-design.md section 4 update) - a direct
 *  read-through of {@code booking_events}. {@code reason} and {@code metadata} are rider/driver/
 *  support-supplied free text and values in some cases (e.g. a cancellation reason) - render as plain
 *  text only on the frontend, never as HTML, same discipline as a document-review note. */
public record AdminBookingEventResponse(
        BookingStatus fromStatus,
        BookingStatus toStatus,
        ActorType actorType,
        UUID actorId,
        String reason,
        Map<String, Object> metadata,
        Instant createdAt) {
}
