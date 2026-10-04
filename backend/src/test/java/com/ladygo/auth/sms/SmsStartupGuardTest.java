package com.ladygo.auth.sms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class SmsStartupGuardTest {

    @Mock
    private Environment environment;

    private static final SmsService MOCK_LIKE = new MockSmsService();
    private static final SmsService REAL_LIKE = (phone, code, validFor) -> { };

    @Test
    void failsFastWhenMockIsActiveUnderProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        SmsStartupGuard guard = new SmsStartupGuard(List.of(MOCK_LIKE), environment);
        assertThatThrownBy(guard::verifyNoMockInGuardedProfiles).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenMockIsActiveUnderStaging() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        SmsStartupGuard guard = new SmsStartupGuard(List.of(MOCK_LIKE), environment);
        assertThatThrownBy(guard::verifyNoMockInGuardedProfiles).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesUnderProdWhenOnlyARealProviderIsPresent() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        SmsStartupGuard guard = new SmsStartupGuard(List.of(REAL_LIKE), environment);
        assertThatCode(guard::verifyNoMockInGuardedProfiles).doesNotThrowAnyException();
    }

    @Test
    void passesUnderDevEvenWithMockPresent() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        SmsStartupGuard guard = new SmsStartupGuard(List.of(MOCK_LIKE), environment);
        assertThatCode(guard::verifyNoMockInGuardedProfiles).doesNotThrowAnyException();
    }
}
