package com.ladygo.realtime;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Captures the real {@link org.springframework.web.socket.WebSocketSession} so
 * {@link SessionExpirySweepJob} can forcibly close it later, and promotes the handshake-time
 * reservation ({@link AuthHandshakeInterceptor}) to that real session id.
 */
@Component
public class SessionTrackingDecoratorFactory implements WebSocketHandlerDecoratorFactory {

    private final WebSocketSessionRegistry registry;

    public SessionTrackingDecoratorFactory(WebSocketSessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session)
                    throws Exception {
                Object userId = session.getAttributes().get(AuthHandshakeInterceptor.ATTR_USER_ID);
                Object expiry = session.getAttributes().get(AuthHandshakeInterceptor.ATTR_TOKEN_EXPIRY);
                Object reservationKey = session.getAttributes().get("reservationKey");
                if (userId != null && reservationKey != null) {
                    registry.promote(reservationKey.toString(), session.getId(), userId.toString(),
                            (Instant) expiry, session);
                }
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(
                    org.springframework.web.socket.WebSocketSession session, CloseStatus closeStatus)
                    throws Exception {
                registry.release(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }
}
