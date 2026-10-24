package com.ladygo.realtime;

import java.security.Principal;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/**
 * Makes {@code SimpMessagingTemplate.convertAndSendToUser(userId, ...)} work: the STOMP user-destination
 * mechanism resolves "user" from this {@link Principal}, built from the already-validated JWT
 * ({@link AuthHandshakeInterceptor} put the user id in the handshake attributes; this never
 * re-validates anything itself).
 */
@Component
public class PrincipalHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(
            ServerHttpRequest request, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Object userId = attributes.get(AuthHandshakeInterceptor.ATTR_USER_ID);
        if (userId == null) {
            return null;
        }
        return new UserIdPrincipal(userId.toString());
    }

    private record UserIdPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
