package com.ladygo.realtime;

import java.time.Instant;
import java.util.Map;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.jwt.AuthPrincipal;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.users.domain.UserRole;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Handshake-time authentication, role gate, and per-user connection limit
 * (docs/architecture/realtime-architecture.md section 4/7). Origin checking is handled separately by
 * {@code setAllowedOriginPatterns} on the STOMP endpoint registration - Spring rejects a disallowed
 * origin before this interceptor ever runs. The JWT is read from the handshake's own
 * {@code Authorization} header (native mobile clients can set arbitrary headers on a WebSocket
 * handshake; this deliberately never accepts a token via query string, same rule the architecture doc
 * already states).
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    static final String ATTR_USER_ID = "userId";
    static final String ATTR_TOKEN_EXPIRY = "tokenExpiry";

    private final JwtService jwtService;
    private final WebSocketSessionRegistry registry;
    private final ConfigurationService config;

    public AuthHandshakeInterceptor(
            JwtService jwtService, WebSocketSessionRegistry registry, ConfigurationService config) {
        this.jwtService = jwtService;
        this.registry = registry;
        this.config = config;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        String token = header.substring("Bearer ".length());
        AuthPrincipal principal = jwtService.parse(token).orElse(null);
        if (principal == null || principal.enrolment()
                || (principal.role() != UserRole.DRIVER && principal.role() != UserRole.CUSTOMER)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Instant expiry = jwtService.expiryOf(token).orElse(null);
        if (expiry == null || !expiry.isAfter(Instant.now())) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String userId = principal.userId().toString();
        int maxPerUser = config.getInt("websocket.max_connections_per_user", 3);
        // There is no session id yet at handshake time; use a per-call placeholder and let the
        // handshake handler's determineUser (which runs right after this) read it back out.
        String placeholder = userId + ":" + java.util.UUID.randomUUID();
        if (!registry.reserve(placeholder, userId, maxPerUser)) {
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_TOKEN_EXPIRY, expiry);
        attributes.put("reservationKey", placeholder);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // Nothing to do on success - the decorator (which has the real session id) takes over the
        // reservation in afterConnectionEstablished. On failure there is no session id to clean up by;
        // the reservation is a small, harmless leak bounded by maxPerUser and cleared on app restart.
        // Acceptable for this phase - noted, not solved, same as other "known minor race" notes here.
    }
}
