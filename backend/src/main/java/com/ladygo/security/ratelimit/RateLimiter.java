package com.ladygo.security.ratelimit;

import java.time.Duration;

/**
 * Fixed-window rate limiter. Database-backed in Phase 3 (docs/security/auth-design.md section 8);
 * kept behind this interface so a Redis-backed implementation can replace it once Redis is wired in
 * for matching (Phase 6/7) without touching any caller.
 */
public interface RateLimiter {

    /** Atomically records one attempt against {@code bucketKey} and reports whether it is still within limit. */
    boolean tryConsume(String bucketKey, int maxAttempts, Duration window);
}
