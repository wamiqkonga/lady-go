package com.ladygo.notifications.email;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Dev/test-only: logs the message instead of sending, and remembers the last 6-digit code per address
 *  so tests can finish an OTP flow. Profile-gated so it can never be the active bean in staging/prod. */
@Component
@Profile({"dev", "test"})
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final ConcurrentHashMap<String, String> LAST_CODE_BY_ADDRESS = new ConcurrentHashMap<>();

    @Override
    public boolean send(String toAddress, String subject, String textBody) {
        log.info("operation=logging_email_send to={} subject={} body={}", toAddress, subject, textBody);
        Matcher m = CODE.matcher(textBody);
        if (m.find()) {
            LAST_CODE_BY_ADDRESS.put(toAddress.toLowerCase(), m.group(1));
        }
        return true;
    }

    public static String lastCodeFor(String address) {
        return LAST_CODE_BY_ADDRESS.get(address.toLowerCase());
    }
}
