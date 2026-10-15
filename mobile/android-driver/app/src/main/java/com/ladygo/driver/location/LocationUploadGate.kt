package com.ladygo.driver.location

/**
 * Pure throttle/backoff decision for the location-upload loop - no Android dependency, so it's
 * testable with a fake clock. Deliberately holds no queue: the service always overwrites "the
 * latest fix" and asks this gate "can I send it now", so an old fix is naturally dropped rather
 * than queued the moment a newer one arrives (matches `driver.location.rate.window.seconds`=2 on
 * the server - sending faster than this just wastes battery hitting a 429 the server already
 * rate-limits, per docs/product/driver-android-design.md §2.3).
 */
class LocationUploadGate(
    private val baseIntervalMillis: Long = 2_000,
    private val maxBackoffMillis: Long = 30_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var nextAllowedSendAtMillis = 0L
    private var currentBackoffMillis = baseIntervalMillis

    fun canSendNow(): Boolean = nowMillis() >= nextAllowedSendAtMillis

    /** Resets backoff to the base throttle interval - a successful send means the server and the
     *  connection are both healthy again. */
    fun onSendSucceeded() {
        currentBackoffMillis = baseIntervalMillis
        nextAllowedSendAtMillis = nowMillis() + baseIntervalMillis
    }

    /** Doubles the wait (capped) on every consecutive failure - a 429 or a network error both mean
     *  "don't hammer this right now", not "retry immediately". */
    fun onSendFailed() {
        nextAllowedSendAtMillis = nowMillis() + currentBackoffMillis
        currentBackoffMillis = (currentBackoffMillis * 2).coerceAtMost(maxBackoffMillis)
    }
}
