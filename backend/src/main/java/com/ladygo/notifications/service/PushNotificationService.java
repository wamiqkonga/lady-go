package com.ladygo.notifications.service;

import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.domain.DeviceToken;
import com.ladygo.notifications.domain.NotificationChannelType;
import com.ladygo.notifications.domain.NotificationLog;
import com.ladygo.notifications.domain.NotificationStatus;
import com.ladygo.notifications.push.PushDeliveryResult;
import com.ladygo.notifications.push.PushMessage;
import com.ladygo.notifications.push.PushSender;
import com.ladygo.notifications.repository.DeviceTokenRepository;
import com.ladygo.notifications.repository.NotificationLogRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends a push to every device a user has registered for one app. Best effort: a failure is recorded in the delivery
 * log (event type and outcome only, never the message) and never thrown at the caller, so a ride transition cannot
 * fail because Firebase is down. Tokens the provider reports dead are removed.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final DeviceTokenRepository tokens;
    private final NotificationLogRepository logs;
    private final PushSender sender;

    public PushNotificationService(DeviceTokenRepository tokens, NotificationLogRepository logs, PushSender sender) {
        this.tokens = tokens;
        this.logs = logs;
        this.sender = sender;
    }

    public void notifyUser(UUID userId, AppType app, PushMessage message) {
        List<DeviceToken> devices = tokens.findByUserIdAndApp(userId, app);
        for (DeviceToken device : devices) {
            PushDeliveryResult result;
            try {
                result = sender.send(device.getToken(), message);
            } catch (RuntimeException e) {
                log.warn("operation=push_send outcome=error eventType={} type={}", message.eventType(), e.getClass().getSimpleName());
                result = PushDeliveryResult.failed("EXCEPTION");
            }
            record(userId, message.eventType(), result);
            if (result.status() == PushDeliveryResult.Status.INVALID_TOKEN) {
                tokens.delete(device);
            }
        }
    }

    private void record(UUID userId, String eventType, PushDeliveryResult result) {
        NotificationLog entry = new NotificationLog();
        entry.setUserId(userId);
        entry.setEventType(eventType);
        entry.setChannel(NotificationChannelType.PUSH);
        entry.setStatus(result.status() == PushDeliveryResult.Status.SENT ? NotificationStatus.SENT : NotificationStatus.FAILED);
        entry.setProviderRef(result.providerRef());
        entry.setErrorCode(result.errorCode());
        logs.save(entry);
    }
}
