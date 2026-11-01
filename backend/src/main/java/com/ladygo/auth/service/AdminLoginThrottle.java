package com.ladygo.auth.service;

import com.ladygo.config.ConfigurationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Progressive admin login delay (docs/security/admin-stepup-lockout-recovery-design.md section 3): each failure for an
 * email doubles the wait before the next attempt (1s, 2s, 4s ... capped), a success or a SUPER_ADMIN unlock clears it.
 * Unknown emails are tracked exactly like real ones, so the reply and its timing never reveal who is an admin.
 *
 * <p>One atomic upsert per failure. Callers must let the write commit even though the request is rejected (see the
 * {@code noRollbackFor} on {@link AdminAuthService#login}); a rolled-back counter would never accumulate.
 */
@Component
public class AdminLoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginThrottle.class);

    private static final String RECORD_FAILURE = """
            INSERT INTO admin_login_throttle (email, failure_count, window_start, window_failures, last_failure_at, locked_until)
            VALUES (?, 1, now(), 1, now(), now() + make_interval(secs => LEAST(1, ?::double precision)))
            ON CONFLICT (email) DO UPDATE SET
                failure_count   = admin_login_throttle.failure_count + 1,
                window_failures = CASE WHEN admin_login_throttle.window_start < now() - make_interval(mins => ?)
                                       THEN 1 ELSE admin_login_throttle.window_failures + 1 END,
                window_start    = CASE WHEN admin_login_throttle.window_start < now() - make_interval(mins => ?)
                                       THEN now() ELSE admin_login_throttle.window_start END,
                last_failure_at = now(),
                locked_until    = now() + make_interval(secs => LEAST(power(2, LEAST(admin_login_throttle.failure_count, 30)),
                                                                     ?::double precision))
            RETURNING failure_count, window_failures
            """;

    private final JdbcTemplate jdbc;
    private final ConfigurationService config;

    public AdminLoginThrottle(JdbcTemplate jdbc, ConfigurationService config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    public static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** True while the email is still inside its wait. */
    public boolean isLocked(String email) {
        List<Boolean> rows = jdbc.query(
                "SELECT locked_until > now() FROM admin_login_throttle WHERE email = ?",
                (rs, i) -> rs.getBoolean(1), normalise(email));
        return !rows.isEmpty() && rows.get(0);
    }

    public void recordFailure(String email) {
        String key = normalise(email);
        int maxDelay = config.getInt("admin.login.max.delay.seconds", 900);
        int alertWindow = config.getInt("admin.login.alert.window.minutes", 10);
        int alertFailures = config.getInt("admin.login.alert.failures", 10);
        int[] counts = jdbc.queryForObject(RECORD_FAILURE,
                (rs, i) -> new int[] {rs.getInt("failure_count"), rs.getInt("window_failures")},
                key, maxDelay, alertWindow, alertWindow, maxDelay);
        if (counts != null && counts[1] == alertFailures) {
            // Hash, not the address: the value is attacker-supplied text. Ops can hash a known address to match it.
            log.warn("operation=admin_login_failures_high failures={} windowMinutes={} emailHash={}",
                    counts[1], alertWindow, sha256Prefix(key));
        }
        // Housekeeping so junk addresses from a scan do not accumulate forever.
        jdbc.update("DELETE FROM admin_login_throttle WHERE last_failure_at < now() - make_interval(hours => 24)");
    }

    /** A successful login or a SUPER_ADMIN unlock forgets the failures. */
    public void clear(String email) {
        jdbc.update("DELETE FROM admin_login_throttle WHERE email = ?", normalise(email));
    }

    private static String sha256Prefix(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
