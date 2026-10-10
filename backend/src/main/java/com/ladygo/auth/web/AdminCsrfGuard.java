package com.ladygo.auth.web;

import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.security.CorsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Defense-in-depth for the two admin-web endpoints that rely on an ambient cookie
 * ({@link AdminAuthCookies#REFRESH_TOKEN}) rather than an explicit Bearer header - everything else in
 * this API is immune to CSRF by construction (a cross-site request cannot forge an Authorization
 * header), but a cookie-authenticated endpoint can be hit by a cross-site form/fetch that merely
 * knows the URL, so these two checks close that gap:
 *
 * <ol>
 *   <li>Double-submit CSRF token: the header must match the cookie. A cross-site attacker's page can
 *   trigger a request that carries the cookie (browsers do that automatically), but it cannot read
 *   the cookie's value (that's the whole point of same-origin policy) to put into the header.</li>
 *   <li>{@code Origin} allow-list: belt-and-suspenders against any client that skips sending the CSRF
 *   header rather than getting it wrong (which would otherwise fail open, not closed).</li>
 * </ol>
 */
@Component
class AdminCsrfGuard {

    private final CorsProperties corsProperties;

    AdminCsrfGuard(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    void verify(HttpServletRequest request, String csrfCookieValue) {
        String header = request.getHeader(AdminAuthCookies.CSRF_HEADER);
        if (csrfCookieValue == null || header == null || !header.equals(csrfCookieValue)) {
            throw new ForbiddenException("Missing or invalid CSRF token.");
        }
        String origin = request.getHeader("Origin");
        if (origin == null || !corsProperties.allowedOrigins().contains(origin)) {
            throw new ForbiddenException("Request origin is not allowed.");
        }
    }
}
