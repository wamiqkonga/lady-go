package com.ladygo.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import com.ladygo.users.domain.UserRole;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("s".repeat(32));

    @Test
    void issuedAccessTokenParsesBackToTheSamePrincipal() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.issueAccessToken(userId, UserRole.CUSTOMER, Duration.ofMinutes(15));
        AuthPrincipal principal = jwtService.parse(token).orElseThrow();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(principal.enrolment()).isFalse();
    }

    @Test
    void issuedEnrolmentTokenCarriesItsClaims() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.issueEnrolmentToken(userId, false, true, Duration.ofMinutes(10));
        AuthPrincipal principal = jwtService.parse(token).orElseThrow();
        assertThat(principal.enrolment()).isTrue();
        assertThat(principal.role()).isNull();
        assertThat(principal.passwordChanged()).isFalse();
        assertThat(principal.mfaEnrolled()).isTrue();
    }

    @Test
    void expiredTokenFailsToParse() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.issueAccessToken(userId, UserRole.DRIVER, Duration.ofSeconds(-1));
        assertThat(jwtService.parse(token)).isEmpty();
    }

    @Test
    void garbageTokenFailsToParse() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() {
        JwtService other = new JwtService("x".repeat(32));
        String token = other.issueAccessToken(UUID.randomUUID(), UserRole.ADMIN, Duration.ofMinutes(10));
        assertThat(jwtService.parse(token)).isEmpty();
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("too-short")).isInstanceOf(IllegalStateException.class);
    }
}
