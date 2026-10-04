package com.ladygo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void doesNothingWhenASuperAdminAlreadyExists() {
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(true);
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                users, passwordEncoder, "admin@ladygo.test", "bootstrap-pass", "+911234567890");

        runner.run(null);

        verify(users, never()).save(any());
    }

    @Test
    void doesNothingWhenBootstrapEnvVarsAreMissing() {
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(false);
        AdminBootstrapRunner runner = new AdminBootstrapRunner(users, passwordEncoder, "", "", "");

        runner.run(null);

        verify(users, never()).save(any());
    }

    @Test
    void createsAForcedChangeSuperAdminWhenNoneExistsAndEnvVarsArePresent() {
        when(users.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("bootstrap-pass")).thenReturn("hashed");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                users, passwordEncoder, "admin@ladygo.test", "bootstrap-pass", "+911234567890");

        runner.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User created = captor.getValue();
        assertThat(created.getEmail()).isEqualTo("admin@ladygo.test");
        assertThat(created.getPhone()).isEqualTo("+911234567890");
        assertThat(created.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(created.getPasswordHash()).isEqualTo("hashed");
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(created.isMfaEnabled()).isFalse();
    }
}
