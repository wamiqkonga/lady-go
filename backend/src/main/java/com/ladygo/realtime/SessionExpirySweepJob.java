package com.ladygo.realtime;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Closes a WebSocket session whose JWT has expired (docs/architecture/realtime-architecture.md
 *  section 7) - the client must reconnect with a fresh token; nothing silently keeps working past
 *  token expiry just because the socket is still open. */
@Component
public class SessionExpirySweepJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpirySweepJob.class);

    private final WebSocketSessionRegistry registry;

    public SessionExpirySweepJob(WebSocketSessionRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${ladygo.websocket.expiry-sweep.interval-ms:15000}")
    public void closeExpiredSessions() {
        for (WebSocketSession session : registry.sessionsExpiredAsOf(Instant.now())) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("token expired"));
            } catch (Exception ex) {
                log.warn("operation=websocket_expiry_close_failed sessionId={} error={}",
                        session.getId(), ex.getMessage());
            }
        }
    }
}
