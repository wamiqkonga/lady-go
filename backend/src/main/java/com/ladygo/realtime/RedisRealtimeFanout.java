package com.ladygo.realtime;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Cross-node fan-out, additive only (docs/api/matching.md section 4/7): every push still goes to this
 * node's own connected sessions immediately via {@link LocalRealtimePushService}; Redis publish is
 * purely so OTHER nodes' listeners can also deliver to sessions connected to them. Never the source of
 * truth - a publish failure (Redis down/unreachable) is swallowed; local delivery has already happened
 * either way, and nothing upstream (offer/booking state, all in Postgres) is affected. Disabled by
 * default ({@code ladygo.realtime.redis.enabled=false}) - dev/test never touch Redis unless an
 * operator turns this on for a real multi-node deployment.
 */
@Configuration
@ConditionalOnProperty(name = "ladygo.realtime.redis.enabled", havingValue = "true")
public class RedisRealtimeFanout {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeFanout.class);
    private static final String CHANNEL_PREFIX = "rt:push:";

    @Bean
    @Primary
    RealtimePushService fanoutRealtimePushService(
            StringRedisTemplate redisTemplate, LocalRealtimePushService local, ObjectMapper objectMapper) {
        return (userId, destination, payload) -> {
            local.pushToUser(userId, destination, payload); // this node's own sessions, unconditionally
            try {
                String envelope = objectMapper.writeValueAsString(
                        Map.of("userId", userId.toString(), "destination", destination, "payload", payload));
                redisTemplate.convertAndSend(CHANNEL_PREFIX + userId, envelope);
            } catch (Exception ex) {
                log.warn("operation=realtime_fanout_publish_failed userId={} error={}", userId, ex.getMessage());
            }
        };
    }

    @Bean
    RedisMessageListenerContainer fanoutListenerContainer(
            RedisConnectionFactory connectionFactory, LocalRealtimePushService local, ObjectMapper objectMapper) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = objectMapper.readValue(body, Map.class);
                UUID userId = UUID.fromString((String) envelope.get("userId"));
                local.pushToUser(userId, (String) envelope.get("destination"), envelope.get("payload"));
            } catch (Exception ex) {
                log.warn("operation=realtime_fanout_receive_failed error={}", ex.getMessage());
            }
        }, new PatternTopic(CHANNEL_PREFIX + "*"));
        return container;
    }
}
