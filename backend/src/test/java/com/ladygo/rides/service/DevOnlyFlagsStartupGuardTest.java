package com.ladygo.rides.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class DevOnlyFlagsStartupGuardTest {

    @Mock
    private Environment environment;

    @Test
    void failsFastWhenMissingAccuracyBypassIsOnUnderProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        DevOnlyFlagsStartupGuard guard = new DevOnlyFlagsStartupGuard(environment, true, false);
        assertThatThrownBy(guard::verifyNoDevOnlyFlagsInGuardedProfiles).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenPaymentsStubIsOnUnderStaging() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        DevOnlyFlagsStartupGuard guard = new DevOnlyFlagsStartupGuard(environment, false, true);
        assertThatThrownBy(guard::verifyNoDevOnlyFlagsInGuardedProfiles).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesUnderProdWhenBothFlagsAreOff() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        DevOnlyFlagsStartupGuard guard = new DevOnlyFlagsStartupGuard(environment, false, false);
        assertThatCode(guard::verifyNoDevOnlyFlagsInGuardedProfiles).doesNotThrowAnyException();
    }

    @Test
    void passesUnderDevEvenWithBothFlagsOn() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        DevOnlyFlagsStartupGuard guard = new DevOnlyFlagsStartupGuard(environment, true, true);
        assertThatCode(guard::verifyNoDevOnlyFlagsInGuardedProfiles).doesNotThrowAnyException();
    }
}
