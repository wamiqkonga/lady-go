package com.ladygo.security.ratelimit;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed fixed-window counter: one atomic {@code INSERT ... ON CONFLICT ... RETURNING} per
 * call, no read-then-write race (docs/security/auth-design.md section 8).
 */
@Component
public class DatabaseRateLimiter implements RateLimiter {

    private static final String UPSERT = """
            INSERT INTO auth_rate_limits (bucket_key, window_start, attempt_count, updated_at)
            VALUES (?, ?, 1, now())
            ON CONFLICT (bucket_key) DO UPDATE SET
                attempt_count = CASE WHEN auth_rate_limits.window_start < ? THEN 1
                                      ELSE auth_rate_limits.attempt_count + 1 END,
                window_start  = CASE WHEN auth_rate_limits.window_start < ? THEN ?
                                      ELSE auth_rate_limits.window_start END,
                updated_at = now()
            RETURNING attempt_count
            """;

    private final JdbcTemplate jdbc;

    public DatabaseRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryConsume(String bucketKey, int maxAttempts, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Integer attemptCount = jdbc.queryForObject(
                UPSERT,
                Integer.class,
                bucketKey, Timestamp.from(now), Timestamp.from(cutoff), Timestamp.from(cutoff), Timestamp.from(now));
        return attemptCount != null && attemptCount <= maxAttempts;
    }
}
