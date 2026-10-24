package com.ladygo.realtime;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Allowed WebSocket handshake origins per environment - same pattern as {@code CorsProperties}.
 *  Never use {@code *} in production. */
@ConfigurationProperties(prefix = "ladygo.websocket")
public record WebSocketProperties(List<String> allowedOrigins) {

    public WebSocketProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
