package com.ladygo.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Integration tests run with the dev/test profile, where {@link LoggingSmsSender} is wired instead, so
 * Spring never instantiates {@link HttpSmsSender} - and it has two constructors. This proves the real
 * bean can be constructed by the container exactly as it is under staging/prod.
 */
class HttpSmsSenderWiringTest {

    @Configuration
    @Import(HttpSmsSender.class)
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void springCanInstantiateTheHttpSenderUnderAProductionLikeProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(Config.class)
                // Blank/default values, as in a deployment that uses the email channel instead of SMS.
                .withPropertyValues("ladygo.sms.http.connect-timeout-ms=3000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HttpSmsSender.class);
                });
    }
}
