package com.ladygo.auth.sms;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test-only stand-in for a real SMS provider: logs the code instead of contacting a carrier.
 * Profile-gated so it is structurally impossible for this to be the active bean under staging/prod
 * (docs/security/auth-design.md section 9); {@link SmsStartupGuard} double-checks that at boot.
 *
 * <p>Keeps the last code sent per phone in memory so tests can complete an OTP flow without a real
 * carrier - this state is never used by production code, only by {@code test}-profile test classes.
 */
@Component
@Profile({"dev", "test"})
public class MockSmsService implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(MockSmsService.class);
    private static final ConcurrentHashMap<String, String> LAST_CODE_BY_PHONE = new ConcurrentHashMap<>();

    @Override
    public void sendOtp(String phoneE164, String code, Duration validFor) {
        log.info("operation=mock_sms_otp phone={} code={} validForSeconds={}",
                phoneE164, code, validFor.toSeconds());
        LAST_CODE_BY_PHONE.put(phoneE164, code);
    }

    public static String lastCodeFor(String phoneE164) {
        return LAST_CODE_BY_PHONE.get(phoneE164);
    }
}
