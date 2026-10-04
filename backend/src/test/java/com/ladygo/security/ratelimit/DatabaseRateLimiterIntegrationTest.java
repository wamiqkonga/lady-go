package com.ladygo.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import com.ladygo.testsupport.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class DatabaseRateLimiterIntegrationTest {

    @Autowired
    private DatabaseRateLimiter rateLimiter;

    @Test
    void allowsUpToTheConfiguredMaximumThenBlocks() {
        String bucket = "test-bucket-" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryConsume(bucket, 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(rateLimiter.tryConsume(bucket, 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void differentBucketsAreIndependent() {
        String bucketA = "test-bucket-a-" + System.nanoTime();
        String bucketB = "test-bucket-b-" + System.nanoTime();
        assertThat(rateLimiter.tryConsume(bucketA, 1, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.tryConsume(bucketA, 1, Duration.ofMinutes(1))).isFalse();
        assertThat(rateLimiter.tryConsume(bucketB, 1, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void windowExpiryResetsTheCount() {
        // Wide margins on purpose: a tight window here was observed to flake on a cold Testcontainers
        // start, where the first JDBC round-trip alone can take tens of milliseconds.
        String bucket = "test-bucket-expiry-" + System.nanoTime();
        assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMillis(500))).isTrue();
        assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMillis(500))).isFalse();
        awaitWindowExpiry();
        assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMillis(500))).isTrue();
    }

    @Test
    void consumeThenReleaseStaysWithinBudgetAcrossManyCalls() {
        // Mirrors the real usage (admin login: consume, then release on success): a repeating
        // consume+release pair must never accumulate, however many times it repeats.
        String bucket = "test-bucket-release-" + System.nanoTime();
        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMinutes(1))).isTrue();
            rateLimiter.release(bucket);
        }
    }

    @Test
    void releaseOnAnUnknownBucketIsANoOp() {
        rateLimiter.release("never-consumed-" + System.nanoTime());
    }

    @Test
    void releaseDoesNotGoBelowZero() {
        String bucket = "test-bucket-floor-" + System.nanoTime();
        assertThat(rateLimiter.tryConsume(bucket, 5, Duration.ofMinutes(1))).isTrue();

        rateLimiter.release(bucket);
        rateLimiter.release(bucket); // one more release than consumes - must floor at 0, not go negative

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryConsume(bucket, 5, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(rateLimiter.tryConsume(bucket, 5, Duration.ofMinutes(1))).isFalse();
    }

    private static void awaitWindowExpiry() {
        try {
            Thread.sleep(800);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
