package com.ladygo.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private final TotpService totp = new TotpService();

    @Test
    void generatedSecretIsValidBase32() {
        String secret = totp.generateSecret();
        assertThat(secret).matches("^[A-Z2-7]+$");
    }

    @Test
    void codeForCurrentStepVerifies() {
        String secret = totp.generateSecret();
        Instant now = Instant.now();
        long step = totp.currentStep(now);
        String code = totp.generateCode(secret, step);
        assertThat(totp.verify(secret, code, now)).isEqualTo(OptionalLong.of(step));
    }

    @Test
    void codeOneStepInsideTheWindowVerifies() {
        String secret = totp.generateSecret();
        Instant now = Instant.now();
        long step = totp.currentStep(now) - 1;
        String code = totp.generateCode(secret, step);
        assertThat(totp.verify(secret, code, now)).isEqualTo(OptionalLong.of(step));
    }

    @Test
    void codeOutsideTheWindowIsRejected() {
        String secret = totp.generateSecret();
        Instant now = Instant.now();
        long farFutureStep = totp.currentStep(now) + 10;
        String code = totp.generateCode(secret, farFutureStep);
        assertThat(totp.verify(secret, code, now)).isEmpty();
    }

    @Test
    void wrongCodeIsRejected() {
        String secret = totp.generateSecret();
        assertThat(totp.verify(secret, "000000", Instant.now())).isEmpty();
    }

    @Test
    void provisioningUriContainsIssuerAndSecret() {
        String secret = totp.generateSecret();
        String uri = totp.provisioningUri("admin@ladygo.test", secret);
        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=" + secret);
        assertThat(uri).contains("issuer=LadyGo");
    }
}
