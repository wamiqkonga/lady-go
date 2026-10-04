package com.ladygo.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OtpHasherTest {

    private static final String SECRET = "a".repeat(32);

    @Test
    void sameCodeHashesToTheSameValueAndMatches() {
        OtpHasher hasher = new OtpHasher(SECRET);
        String hash = hasher.hash("123456");
        assertThat(hasher.matches("123456", hash)).isTrue();
        assertThat(hasher.hash("123456")).isEqualTo(hash);
    }

    @Test
    void differentCodeDoesNotMatch() {
        OtpHasher hasher = new OtpHasher(SECRET);
        String hash = hasher.hash("123456");
        assertThat(hasher.matches("654321", hash)).isFalse();
    }

    @Test
    void differentSecretProducesDifferentHash() {
        OtpHasher first = new OtpHasher(SECRET);
        OtpHasher second = new OtpHasher("b".repeat(32));
        assertThat(first.hash("123456")).isNotEqualTo(second.hash("123456"));
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new OtpHasher("too-short")).isInstanceOf(IllegalStateException.class);
    }
}
