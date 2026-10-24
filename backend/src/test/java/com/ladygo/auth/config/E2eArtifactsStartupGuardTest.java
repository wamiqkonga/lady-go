package com.ladygo.auth.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ladygo.config.ConfigurationService;
import com.ladygo.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class E2eArtifactsStartupGuardTest {

    @Mock
    private Environment environment;
    @Mock
    private ConfigurationService config;
    @Mock
    private UserRepository users;

    @Test
    void failsFastWhenTheLoginRateLimitIsElevatedUnderProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(config.getInt(eq("admin.login.max.attempts"), anyInt())).thenReturn(200);

        E2eArtifactsStartupGuard guard = new E2eArtifactsStartupGuard(environment, config, users);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenAnE2eSeedEmailExistsUnderStaging() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        when(config.getInt(anyString(), anyInt())).thenReturn(5);
        when(users.existsByEmailEndingWithIgnoreCase("@ladygo.test")).thenReturn(true);

        E2eArtifactsStartupGuard guard = new E2eArtifactsStartupGuard(environment, config, users);

        assertThatThrownBy(() -> guard.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesUnderProdWithTheNormalLimitAndNoSeedAccounts() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        when(config.getInt(anyString(), anyInt())).thenReturn(5);
        when(users.existsByEmailEndingWithIgnoreCase(anyString())).thenReturn(false);

        E2eArtifactsStartupGuard guard = new E2eArtifactsStartupGuard(environment, config, users);

        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }

    @Test
    void passesUnderDevEvenWithAnElevatedLimitAndSeedAccounts() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});

        E2eArtifactsStartupGuard guard = new E2eArtifactsStartupGuard(environment, config, users);

        // No stubbing for config/users: guarded=false must short-circuit before either is touched.
        assertThatCode(() -> guard.run(null)).doesNotThrowAnyException();
    }
}
