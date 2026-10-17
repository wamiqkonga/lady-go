package com.ladygo.driver.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StompBackoffTest {
    @Test
    fun startsAtTheBaseDelayWithNoJitter() {
        val backoff = StompBackoff(baseMillis = 1_000, maxMillis = 30_000, randomJitter = { 0.0 })

        assertEquals(1_000, backoff.nextDelayMillis())
    }

    @Test
    fun doublesEachAttemptWithNoJitter() {
        val backoff = StompBackoff(baseMillis = 1_000, maxMillis = 30_000, randomJitter = { 0.0 })

        assertEquals(1_000, backoff.nextDelayMillis())
        assertEquals(2_000, backoff.nextDelayMillis())
        assertEquals(4_000, backoff.nextDelayMillis())
        assertEquals(8_000, backoff.nextDelayMillis())
    }

    @Test
    fun capsAtMaxMillisEvenAfterManyAttempts() {
        val backoff = StompBackoff(baseMillis = 1_000, maxMillis = 30_000, randomJitter = { 0.0 })

        repeat(10) { backoff.nextDelayMillis() }

        assertEquals(30_000, backoff.nextDelayMillis())
    }

    @Test
    fun addsJitterOnTopOfTheExponentialDelay() {
        val backoff = StompBackoff(baseMillis = 1_000, maxMillis = 30_000, jitterRatio = 0.5, randomJitter = { 1.0 })

        // base=1000, jitterRatio=0.5, randomJitter=1.0 -> +500 on top of the 1000 base.
        assertEquals(1_500, backoff.nextDelayMillis())
    }

    @Test
    fun resetGoesBackToTheBaseDelay() {
        val backoff = StompBackoff(baseMillis = 1_000, maxMillis = 30_000, randomJitter = { 0.0 })
        backoff.nextDelayMillis()
        backoff.nextDelayMillis()

        backoff.reset()

        assertEquals(1_000, backoff.nextDelayMillis())
    }

    @Test
    fun jitterNeverPushesTheDelayBelowTheExponentialValue() {
        val backoff = StompBackoff(baseMillis = 1_000, maxMillis = 30_000, randomJitter = { 0.0 })

        val delay = backoff.nextDelayMillis()

        assertTrue(delay >= 1_000)
    }
}
