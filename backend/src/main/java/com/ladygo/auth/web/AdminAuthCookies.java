package com.ladygo.auth.web;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.ResponseCookie;

/**
 * Cookie shape for the admin web's refresh token (docs/product/admin-web-design.md section 7) - admin
 * web only; CUSTOMER/DRIVER keep the existing JSON-body refresh token (mobile apps have no CSRF
 * exposure from an ambient cookie, so there is nothing to gain from changing that path, and every
 * other client stays untouched).
 *
 * <p>{@code REFRESH_TOKEN} is {@code HttpOnly} (never readable by admin-web's own JS, so an XSS there
 * cannot exfiltrate it) - {@code CSRF_TOKEN} is deliberately NOT {@code HttpOnly} (the double-submit
 * pattern requires admin-web's JS to read it and echo it back as a header; its value carries no
 * authority on its own, only proves the caller can read same-site cookies, which a cross-site attacker
 * cannot).
 */
public final class AdminAuthCookies {

    static final String REFRESH_TOKEN = "ladygo_admin_rt";
    static final String CSRF_TOKEN = "ladygo_admin_csrf";
    /** Public - {@code SecurityConfig}'s CORS allow-list (a different package) needs the exact name. */
    public static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String COOKIE_PATH = "/api/v1/auth/admin";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminAuthCookies() {
    }

    static String newCsrfToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static ResponseCookie refreshTokenCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    static ResponseCookie csrfTokenCookie(String value, Duration maxAge) {
        return ResponseCookie.from(CSRF_TOKEN, value)
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    static ResponseCookie clearedRefreshTokenCookie() {
        return ResponseCookie.from(REFRESH_TOKEN, "").httpOnly(true).secure(true)
                .sameSite("Strict").path(COOKIE_PATH).maxAge(Duration.ZERO).build();
    }

    static ResponseCookie clearedCsrfTokenCookie() {
        return ResponseCookie.from(CSRF_TOKEN, "").httpOnly(false).secure(true)
                .sameSite("Strict").path(COOKIE_PATH).maxAge(Duration.ZERO).build();
    }
}
