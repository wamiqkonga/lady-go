package com.ladygo.realtime;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** Delivers to this node's own connected sessions only - see {@link RealtimePushService}. Never
 *  throws: a push is best-effort, and a failure here must never fail the caller's transaction. */
@Service
public class LocalRealtimePushService implements RealtimePushService {

    private static final Logger log = LoggerFactory.getLogger(LocalRealtimePushService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public LocalRealtimePushService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void pushToUser(UUID userId, String destination, Object payload) {
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), destination, payload);
        } catch (Exception ex) {
            log.warn("operation=realtime_push_failed userId={} destination={} error={}",
                    userId, destination, ex.getMessage());
        }
    }
}
