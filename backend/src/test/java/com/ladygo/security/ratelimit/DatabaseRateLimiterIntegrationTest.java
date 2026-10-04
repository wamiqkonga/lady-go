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
        String bucket = "test-bucket-expiry-" + System.nanoTime();
        assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMillis(50))).isTrue();
        assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMillis(50))).isFalse();
        await50ms();
        assertThat(rateLimiter.tryConsume(bucket, 1, Duration.ofMillis(50))).isTrue();
    }

    private static void await50ms() {
        try {
            Thread.sleep(80);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
