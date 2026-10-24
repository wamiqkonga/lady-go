package com.ladygo.driver.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferCountdownTest {
    @Test
    fun remainingMillisCountsDownToZeroWithNoSkew() {
        val countdown = OfferCountdown(expiresAtEpochMillis = 10_000, skew = ServerClockSkew.NONE, nowMillis = { 3_000 })

        assertEquals(7_000, countdown.remainingMillis())
        assertFalse(countdown.isExpired())
    }

    @Test
    fun isExpiredOnceRemainingHitsZero() {
        val countdown = OfferCountdown(expiresAtEpochMillis = 10_000, skew = ServerClockSkew.NONE, nowMillis = { 10_000 })

        assertEquals(0, countdown.remainingMillis())
        assertTrue(countdown.isExpired())
    }

    @Test
    fun neverGoesNegativePastExpiry() {
        val countdown = OfferCountdown(expiresAtEpochMillis = 10_000, skew = ServerClockSkew.NONE, nowMillis = { 50_000 })

        assertEquals(0, countdown.remainingMillis())
        assertTrue(countdown.isExpired())
    }

    @Test
    fun aDeviceClockAheadOfTheServerWouldWronglyShowLessTimeWithoutSkewCorrection() {
        // Device thinks it's 9_000 (ahead of the server by 4_000ms), offer expires at server-time 10_000.
        // Without skew correction this would show only 1_000ms left; with it, the real 5_000ms server-side.
        val skew = ServerClockSkew.sample(serverEpochMillisAtResponse = 5_000, localNowMillisAtReceipt = 9_000)
        val countdown = OfferCountdown(expiresAtEpochMillis = 10_000, skew = skew, nowMillis = { 9_000 })

        assertEquals(5_000, countdown.remainingMillis())
    }

    @Test
    fun aDeviceClockBehindTheServerIsAlsoCorrected() {
        // Device thinks it's 1_000 (behind the server by 4_000ms, server is actually at 5_000).
        val skew = ServerClockSkew.sample(serverEpochMillisAtResponse = 5_000, localNowMillisAtReceipt = 1_000)
        val countdown = OfferCountdown(expiresAtEpochMillis = 10_000, skew = skew, nowMillis = { 1_000 })

        assertEquals(5_000, countdown.remainingMillis())
    }
}
