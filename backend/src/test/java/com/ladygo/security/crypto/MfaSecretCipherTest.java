package com.ladygo.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MfaSecretCipherTest {

    private static final String KEY_32 = "k".repeat(32);

    @Test
    void roundTripsThePlaintext() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY_32);
        String secret = "JBSWY3DPEHPK3PXP";
        String encrypted = cipher.encrypt(secret);
        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void sameInputEncryptsDifferentlyEachTime() {
        MfaSecretCipher cipher = new MfaSecretCipher(KEY_32);
        String secret = "JBSWY3DPEHPK3PXP";
        assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
    }

    @Test
    void invalidKeyLengthFailsFast() {
        assertThatThrownBy(() -> new MfaSecretCipher("too-short")).isInstanceOf(IllegalStateException.class);
    }
}
