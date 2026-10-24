package com.ladygo.driver.realtime

/**
 * How far the device clock is from the server's, sampled once per connection (design doc §2.2) via
 * `GET /api/v1/system/ping`'s response arrival, not re-measured per offer - an offer's countdown is
 * driven off this plus the server's own `expiresAt`, never off a client-started stopwatch.
 */
data class ServerClockSkew(
    private val skewMillis: Long,
) {
    /** Best estimate of what the server's clock reads right now, given a local timestamp. */
    fun toEstimatedServerNowMillis(localNowMillis: Long): Long = localNowMillis + skewMillis

    companion object {
        val NONE = ServerClockSkew(0L)

        fun sample(
            serverEpochMillisAtResponse: Long,
            localNowMillisAtReceipt: Long,
        ): ServerClockSkew = ServerClockSkew(serverEpochMillisAtResponse - localNowMillisAtReceipt)
    }
}
