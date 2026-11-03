package com.ladygo.notifications.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Staging/prod before Firebase is configured: a deliberate, visible "no push" instead of a startup failure. */
@Component
@ConditionalOnProperty(name = "ladygo.push.provider", havingValue = "disabled")
public class DisabledPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(DisabledPushSender.class);

    public DisabledPushSender() {
        log.warn("Push notifications are DISABLED (ladygo.push.provider=disabled). Set LADYGO_PUSH_PROVIDER=fcm and the "
                + "LADYGO_FCM_* variables to enable them.");
    }

    @Override
    public PushDeliveryResult send(String deviceToken, PushMessage message) {
        return PushDeliveryResult.failed("PUSH_DISABLED");
    }
}
