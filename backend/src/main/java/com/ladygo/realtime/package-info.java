/**
 * WebSocket/STOMP infrastructure (docs/architecture/realtime-architecture.md): handshake
 * authentication, per-user connection limits, token-expiry disconnection, origin checking, and the
 * {@link com.ladygo.realtime.RealtimePushService} other modules use to push to a connected user.
 * Deliberately independent of {@code matching}/{@code bookings} - those modules depend on this one's
 * public interface, never the other way round.
 */
package com.ladygo.realtime;
