package com.ladygo.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The day-old boundary, to the second: a captain is overdue only once the wait is OVER the limit. */
class AdminVerificationQueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Duration LIMIT = Duration.ofHours(24);

    @Test
    void exactlyTwentyFourHoursIsNotYetOverdue() {
        assertThat(AdminVerificationQueueService.isOverdue(NOW.minus(LIMIT), NOW, LIMIT)).isFalse();
    }

    @Test
    void oneSecondOverTheLimitIsOverdue() {
        assertThat(AdminVerificationQueueService.isOverdue(NOW.minus(LIMIT).minusSeconds(1), NOW, LIMIT)).isTrue();
    }

    @Test
    void justUnderTheLimitIsNotOverdue() {
        assertThat(AdminVerificationQueueService.isOverdue(NOW.minus(LIMIT).plusSeconds(1), NOW, LIMIT)).isFalse();
    }
}
