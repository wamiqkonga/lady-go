package com.ladygo.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SimpleCircuitBreakerTest {

    private static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void staysClosedUnderTheFailureThreshold() {
        var breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(30), Clock.systemUTC());
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void opensAfterReachingTheFailureThresholdAndFailsFast() {
        var breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(30), Clock.systemUTC());
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        var breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(30), Clock.systemUTC());
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isFalse(); // only 2 consecutive failures since the reset
    }

    @Test
    void allowsOneProbeAfterTheCooldownAndClosesOnSuccess() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(30), clock);
        breaker.recordFailure();
        assertThat(breaker.allowRequest()).isFalse();

        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.allowRequest()).isTrue(); // the probe
        breaker.recordSuccess();

        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void aFailedProbeReopensForAFreshCooldown() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(30), clock);
        breaker.recordFailure();
        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.allowRequest()).isTrue(); // the probe
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowRequest()).isFalse(); // still within the fresh cooldown
    }
}
