package com.ladygo.notifications.sms;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test-only stand-in for a real SMS provider: logs the rendered template instead of contacting a
 * carrier. Profile-gated so it is structurally impossible for this to be the active bean under
 * staging/prod (docs/security/auth-design.md section 9); {@link SmsSenderStartupGuard}
 * double-checks that at boot.
 *
 * <p>Keeps the last variables sent per phone in memory so tests can complete an OTP flow without a
 * real carrier - this state is never used by production code, only by {@code test}-profile test
 * classes (via {@link #lastCodeFor}).
 */
@Component
@Profile({"dev", "test"})
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);
    private static final ConcurrentHashMap<String, Map<String, String>> LAST_VARIABLES_BY_PHONE =
            new ConcurrentHashMap<>();

    @Override
    public SmsDeliveryResult send(String toE164, String templateKey, Map<String, String> variables) {
        // Dev-only logging of the full rendered content is deliberate here - the whole point is a
        // developer being able to read the OTP off the console without a real carrier. HttpSmsSender
        // (the path that actually runs in staging/prod) never does this - see its own javadoc.
        log.info("operation=logging_sms_send phone={} templateKey={} variables={}", toE164, templateKey, variables);
        LAST_VARIABLES_BY_PHONE.put(toE164, variables);
        return SmsDeliveryResult.accepted("mock-" + UUID.randomUUID());
    }

    /** The current OTP code for the given phone, as last sent via the {@code "code"} variable. */
    public static String lastCodeFor(String phoneE164) {
        Map<String, String> variables = LAST_VARIABLES_BY_PHONE.get(phoneE164);
        return variables == null ? null : variables.get("code");
    }
}
