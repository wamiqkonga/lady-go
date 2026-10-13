package com.ladygo.notifications.sms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class SmsSenderStartupGuardTest {

    private static final SmsSender LOGGING_LIKE = new LoggingSmsSender();
    private static final SmsSender HTTP_LIKE = (to, templateKey, variables) -> SmsDeliveryResult.accepted("id");

    private static final SmsSenderProperties FULLY_CONFIGURED = new SmsSenderProperties(
            "https://provider.example/send", "Authorization", "secret-token", "LADYGO",
            "tmpl-1", "entity-1", "{\"to\":\"{{to}}\"}", "messageId", "errorCode",
            3000, 5000, 2, 500, 5, 30000);

    private static final SmsSenderProperties BLANK = new SmsSenderProperties(
            null, null, null, null, null, null, null, null, null, 3000, 5000, 2, 500, 5, 30000);

    @Mock
    private Environment environment;

    @Test
    void failsFastWhenLoggingSenderIsActiveUnderProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        var guard = new SmsSenderStartupGuard(List.of(LOGGING_LIKE), FULLY_CONFIGURED, environment);
        assertThatThrownBy(guard::verifyRealSenderConfiguredInGuardedProfiles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LoggingSmsSender");
    }

    @Test
    void failsFastWhenHttpSenderIsMissingRequiredConfigUnderStaging() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        var guard = new SmsSenderStartupGuard(List.of(HTTP_LIKE), BLANK, environment);
        assertThatThrownBy(guard::verifyRealSenderConfiguredInGuardedProfiles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing required configuration");
    }

    @Test
    void passesUnderProdWithAFullyConfiguredHttpSender() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        var guard = new SmsSenderStartupGuard(List.of(HTTP_LIKE), FULLY_CONFIGURED, environment);
        assertThatCode(guard::verifyRealSenderConfiguredInGuardedProfiles).doesNotThrowAnyException();
    }

    @Test
    void passesUnderDevEvenWithTheLoggingSenderAndNoConfig() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        var guard = new SmsSenderStartupGuard(List.of(LOGGING_LIKE), BLANK, environment);
        assertThatCode(guard::verifyRealSenderConfiguredInGuardedProfiles).doesNotThrowAnyException();
    }
}
