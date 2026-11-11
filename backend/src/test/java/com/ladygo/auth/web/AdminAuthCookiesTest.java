package com.ladygo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdminAuthCookiesTest {

    @Test
    void csrfCookieIsHostOnlyByDefault() {
        assertThat(AdminAuthCookies.csrfTokenCookie("v", Duration.ofDays(1), "").getDomain()).isNull();
        assertThat(AdminAuthCookies.csrfTokenCookie("v", Duration.ofDays(1), null).getDomain()).isNull();
    }

    @Test
    void csrfCookieCarriesTheConfiguredParentDomainButStaysReadableBySiblingsOnly() {
        var cookie = AdminAuthCookies.csrfTokenCookie("v", Duration.ofDays(1), " ladygoride.com ");
        assertThat(cookie.getDomain()).isEqualTo("ladygoride.com");
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo("/");
    }

    @Test
    void clearingUsesTheSameDomainOrTheBrowserKeepsTheCookie() {
        var cleared = AdminAuthCookies.clearedCsrfTokenCookie("ladygoride.com");
        assertThat(cleared.getDomain()).isEqualTo("ladygoride.com");
        assertThat(cleared.getMaxAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void refreshCookieIsNeverGivenADomain() {
        var cookie = AdminAuthCookies.refreshTokenCookie("t", Duration.ofDays(1));
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.isHttpOnly()).isTrue();
    }
}
