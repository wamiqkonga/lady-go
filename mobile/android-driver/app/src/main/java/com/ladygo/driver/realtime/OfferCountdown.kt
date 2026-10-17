package com.ladygo.driver.realtime

/**
 * Remaining time on an offer, driven off the server's own `expiresAt` corrected for clock skew
 * (design doc §2.2) - never a client-started stopwatch that assumes the push arrived instantly.
 * `remainingMillis()` never goes negative; [isExpired] is the UX signal only (design doc §3 rule 7)
 * - it disables the accept/decline buttons' *new* taps, but a tap already in flight when this flips
 * to true is still allowed to complete, and the server's response to it is authoritative either way.
 */
class OfferCountdown(
    private val expiresAtEpochMillis: Long,
    private val skew: ServerClockSkew,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun remainingMillis(): Long = (expiresAtEpochMillis - skew.toEstimatedServerNowMillis(nowMillis())).coerceAtLeast(0)

    fun isExpired(): Boolean = remainingMillis() <= 0
}
