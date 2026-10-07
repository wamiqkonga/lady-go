package com.ladygo.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ladygo.config.ConfigurationService;
import com.ladygo.config.repository.ConfigEntryRepository;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * docs/architecture/realtime-architecture.md section 4/7: handshake JWT auth, role gate, and per-user
 * connection limit, all enforced BEFORE the WebSocket upgrade completes.
 */
class AuthHandshakeInterceptorTest {

    private final JwtService jwtService = new JwtService("test-only-handshake-secret-at-least-32-bytes-long");
    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

    private ConfigurationService configWithMaxConnections(int max) {
        ConfigEntryRepository repo = mock(ConfigEntryRepository.class);
        when(repo.findByConfigKeyAndScopeAndCityIdIsNull(any(), any())).thenReturn(Optional.empty());
        ConfigurationService config = new ConfigurationService(repo);
        return config; // default (3) applies unless a real row is seeded; tests below use the default.
    }

    private AuthHandshakeInterceptor interceptor() {
        return new AuthHandshakeInterceptor(jwtService, registry, configWithMaxConnections(3));
    }

    private ServerHttpRequest requestWithAuthHeader(String headerValue) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (headerValue != null) {
            headers.set("Authorization", headerValue);
        }
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private String driverToken() {
        return jwtService.issueAccessToken(UUID.randomUUID(), UserRole.DRIVER, Duration.ofMinutes(15));
    }

    @Test
    void missingAuthorizationHeaderIsRejected() {
        ServerHttpRequest request = requestWithAuthHeader(null);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor().beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
    }

    @Test
    void garbageTokenIsRejected() {
        ServerHttpRequest request = requestWithAuthHeader("Bearer not-a-real-jwt");
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor().beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
    }

    @Test
    void anAdminTokenIsRejected() {
        String adminToken = jwtService.issueAccessToken(UUID.randomUUID(), UserRole.ADMIN, Duration.ofMinutes(15));
        ServerHttpRequest request = requestWithAuthHeader("Bearer " + adminToken);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor().beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
    }

    @Test
    void anExpiredTokenIsRejected() {
        String expired = jwtService.issueAccessToken(UUID.randomUUID(), UserRole.DRIVER, Duration.ofMillis(1));
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        ServerHttpRequest request = requestWithAuthHeader("Bearer " + expired);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor().beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
    }

    @Test
    void aValidDriverTokenIsAcceptedAndPopulatesAttributes() {
        ServerHttpRequest request = requestWithAuthHeader("Bearer " + driverToken());
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor().beforeHandshake(request, response, mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsKeys(
                AuthHandshakeInterceptor.ATTR_USER_ID, AuthHandshakeInterceptor.ATTR_TOKEN_EXPIRY, "reservationKey");
    }

    @Test
    void aFourthConcurrentConnectionForTheSameUserIsRejectedAtTheLimit() {
        ConfigurationService config = configWithMaxConnections(3);
        AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(jwtService, registry, config);
        UUID userId = UUID.randomUUID();
        String token = jwtService.issueAccessToken(userId, UserRole.DRIVER, Duration.ofMinutes(15));

        for (int i = 0; i < 3; i++) {
            boolean accepted = interceptor.beforeHandshake(
                    requestWithAuthHeader("Bearer " + token), mock(ServerHttpResponse.class),
                    mock(WebSocketHandler.class), new HashMap<>());
            assertThat(accepted).isTrue();
        }

        ServerHttpResponse fourthResponse = mock(ServerHttpResponse.class);
        boolean fourthAccepted = interceptor.beforeHandshake(
                requestWithAuthHeader("Bearer " + token), fourthResponse, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(fourthAccepted).isFalse();
    }
}
