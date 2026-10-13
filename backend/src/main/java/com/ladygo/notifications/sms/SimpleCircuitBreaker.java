package com.ladygo.notifications.sms;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal consecutive-failure circuit breaker, purpose-built for {@link HttpSmsSender}: so a dead or
 * saturated provider fails fast (no HTTP attempt, no thread blocked waiting on a connect/read
 * timeout) once it has clearly stopped working, instead of every request thread independently
 * discovering that the slow way. Not a general-purpose library replacement - no half-open probe
 * concurrency limit, no sliding window, just "N consecutive failures opens it for a cooldown, then one
 * attempt is allowed through to test recovery."
 *
 * <p>Plain object, not Spring-managed - {@link HttpSmsSender} owns one instance for its own lifetime,
 * which is also what makes this trivially unit-testable with no Spring context.
 */
public class SimpleCircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final java.time.Duration cooldown;
    private final Clock clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>(Instant.MIN);

    public SimpleCircuitBreaker(int failureThreshold, java.time.Duration cooldown, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Call before attempting the real call. False means fail fast - do not attempt it. */
    public boolean allowRequest() {
        if (state.get() == State.CLOSED) {
            return true;
        }
        if (state.get() == State.OPEN && clock.instant().isAfter(openedAt.get().plus(cooldown))) {
            // One probe attempt is allowed through; recordSuccess/recordFailure below decides whether
            // it closes again or re-opens for another full cooldown.
            state.set(State.HALF_OPEN);
            return true;
        }
        // Still OPEN within the cooldown, or a probe is already outstanding (HALF_OPEN) - fail fast
        // either way. HttpSmsSender has a single call site per request, so a concurrent probe would
        // only come from a genuinely concurrent request, not a bug in this class.
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (state.get() == State.HALF_OPEN || failures >= failureThreshold) {
            state.set(State.OPEN);
            openedAt.set(clock.instant());
        }
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }
}
