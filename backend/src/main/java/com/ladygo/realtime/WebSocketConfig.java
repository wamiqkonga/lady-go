package com.ladygo.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket at {@code /ws} (docs/architecture/realtime-architecture.md section 4):
 * handshake auth + per-user connection limit ({@link AuthHandshakeInterceptor}), origin allow-list
 * (below), user-destination routing ({@link PrincipalHandshakeHandler}), and token-expiry disconnect
 * ({@link SessionTrackingDecoratorFactory} + {@link SessionExpirySweepJob}). A single in-process
 * broker for now - cross-node fan-out (Redis pub/sub) is a separate, additive piece
 * ({@link RedisRealtimeFanout}), not required for this to work on one node.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthHandshakeInterceptor authHandshakeInterceptor;
    private final PrincipalHandshakeHandler principalHandshakeHandler;
    private final SessionTrackingDecoratorFactory sessionTrackingDecoratorFactory;
    private final WebSocketProperties properties;

    public WebSocketConfig(
            AuthHandshakeInterceptor authHandshakeInterceptor, PrincipalHandshakeHandler principalHandshakeHandler,
            SessionTrackingDecoratorFactory sessionTrackingDecoratorFactory, WebSocketProperties properties) {
        this.authHandshakeInterceptor = authHandshakeInterceptor;
        this.principalHandshakeHandler = principalHandshakeHandler;
        this.sessionTrackingDecoratorFactory = sessionTrackingDecoratorFactory;
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(new String[0]))
                .addInterceptors(authHandshakeInterceptor)
                .setHandshakeHandler(principalHandshakeHandler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.addDecoratorFactory(sessionTrackingDecoratorFactory);
    }
}
