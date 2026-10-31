package com.ladygo.notifications.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev/test: records that a push would have gone out. Never logs the token, title or body. */
@Component
@ConditionalOnProperty(name = "ladygo.push.provider", havingValue = "logging")
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public PushDeliveryResult send(String deviceToken, PushMessage message) {
        log.info("operation=logging_push_send eventType={}", message.eventType());
        return PushDeliveryResult.sent("logged");
    }
}
