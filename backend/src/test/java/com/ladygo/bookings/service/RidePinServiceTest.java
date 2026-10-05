package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import com.ladygo.config.ConfigurationService;
import com.ladygo.security.ratelimit.RateLimitExceededException;
import com.ladygo.security.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

class RidePinServiceTest {

    private static final String SECRET = "a".repeat(32);

    private RidePinService service(RateLimiter rateLimiter, ConfigurationService config) {
        return new RidePinService(SECRET, rateLimiter, config);
    }

    @Test
    void derivedPinIsFourDigitsAndDeterministic() {
        RidePinService service = service(mock(RateLimiter.class), mock(ConfigurationService.class));
        UUID bookingId = UUID.randomUUID();

        String first = service.derive(bookingId);
        String second = service.derive(bookingId);

        assertThat(first).hasSize(4).matches("^[0-9]{4}$");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentBookingsTypicallyGetDifferentPins() {
        RidePinService service = service(mock(RateLimiter.class), mock(ConfigurationService.class));
        Set<String> pins = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            pins.add(service.derive(UUID.randomUUID()));
        }
        // Not a strict guarantee (4 digits, 20 samples could collide by chance), but overwhelmingly
        // likely to produce more than a couple of distinct values if the derivation isn't broken.
        assertThat(pins.size()).isGreaterThan(10);
    }

    @Test
    void differentSecretsProduceDifferentPins() {
        RidePinService first = service(mock(RateLimiter.class), mock(ConfigurationService.class));
        RidePinService second = new RidePinService("b".repeat(32), mock(RateLimiter.class), mock(ConfigurationService.class));
        UUID bookingId = UUID.randomUUID();
        // Astronomically unlikely to collide for an unrelated secret unless derivation ignores the key.
        assertThat(first.derive(bookingId)).isNotEqualTo(second.derive(bookingId));
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new RidePinService("too-short", mock(RateLimiter.class), mock(ConfigurationService.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void verifyReturnsTrueForTheCorrectPinWhenWithinLimit() {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        ConfigurationService config = mock(ConfigurationService.class);
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(config.getInt(anyString(), anyInt())).thenReturn(5);
        when(config.getMinutes(any(), any(Duration.class))).thenReturn(Duration.ofMinutes(15));
        RidePinService service = service(rateLimiter, config);

        UUID bookingId = UUID.randomUUID();
        String correctPin = service.derive(bookingId);

        assertThat(service.verify(bookingId, correctPin)).isTrue();
        assertThat(service.verify(bookingId, "0000".equals(correctPin) ? "1111" : "0000")).isFalse();
    }

    @Test
    void verifyThrowsWhenTheRateLimiterSaysNo() {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        ConfigurationService config = mock(ConfigurationService.class);
        when(rateLimiter.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(false);
        when(config.getInt(anyString(), anyInt())).thenReturn(5);
        when(config.getMinutes(any(), any(Duration.class))).thenReturn(Duration.ofMinutes(15));
        RidePinService service = service(rateLimiter, config);

        assertThatThrownBy(() -> service.verify(UUID.randomUUID(), "0000"))
                .isInstanceOf(RateLimitExceededException.class);
    }
}
