package com.ladygo.driver.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationUploadGateTest {
    private var currentTimeMillis = 0L
    private val gate =
        LocationUploadGate(
            baseIntervalMillis = 2_000,
            maxBackoffMillis = 30_000,
            nowMillis = { currentTimeMillis },
        )

    @Test
    fun allowsTheVeryFirstSendImmediately() {
        assertTrue(gate.canSendNow())
    }

    @Test
    fun blocksASecondSendBeforeTheBaseIntervalElapses() {
        gate.onSendSucceeded()
        currentTimeMillis += 1_999

        assertFalse(gate.canSendNow())
    }

    @Test
    fun allowsTheNextSendOnceTheBaseIntervalElapses() {
        gate.onSendSucceeded()
        currentTimeMillis += 2_000

        assertTrue(gate.canSendNow())
    }

    @Test
    fun aSuccessfulSendResetsBackoffToTheBaseInterval() {
        gate.onSendFailed()
        gate.onSendFailed() // backoff now doubled past the base interval
        currentTimeMillis += 100_000 // clear any pending backoff
        gate.onSendSucceeded()
        currentTimeMillis += 2_000

        assertTrue(gate.canSendNow())
    }

    @Test
    fun backsOffExponentiallyOnConsecutiveFailures() {
        gate.onSendFailed() // next allowed at +2000 (base)
        currentTimeMillis += 2_000
        assertTrue(gate.canSendNow())

        gate.onSendFailed() // next allowed at +4000 (doubled)
        currentTimeMillis += 2_000
        assertFalse(gate.canSendNow())
        currentTimeMillis += 2_000
        assertTrue(gate.canSendNow())
    }

    @Test
    fun backoffIsCappedAtMaxBackoffMillis() {
        repeat(10) { gate.onSendFailed() }
        currentTimeMillis += 30_000

        assertTrue(gate.canSendNow())
    }

    @Test
    fun backoffNeverExceedsTheCapEvenAfterManyFailures() {
        repeat(10) { gate.onSendFailed() }
        currentTimeMillis += 29_999

        assertFalse(gate.canSendNow())
    }
}
