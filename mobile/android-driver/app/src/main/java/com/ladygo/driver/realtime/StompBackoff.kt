package com.ladygo.driver.realtime

/**
 * Exponential reconnect backoff with jitter (design doc §2.2: 1s, 2s, 4s, 8s, capped ~30s, plus
 * jitter) - no Android dependency, so it's testable with a fake clock/random source, same style as
 * [com.ladygo.driver.location.LocationUploadGate]. [reset] is called once a connection is actually
 * established, so a long-lived healthy connection doesn't leave a stale high attempt count sitting
 * around for its *next* disconnect.
 */
class StompBackoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
    private val jitterRatio: Double = 0.5,
    private val randomJitter: () -> Double = ::defaultRandomJitter,
) {
    private var attempt = 0

    fun nextDelayMillis(): Long {
        val exponential = (baseMillis shl attempt.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMillis)
        attempt++
        val jitter = (exponential * jitterRatio * randomJitter()).toLong()
        return (exponential + jitter).coerceAtMost(maxMillis + (maxMillis * jitterRatio).toLong())
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_SHIFT = 10
    }
}

private fun defaultRandomJitter(): Double = Math.random()
