package com.ladygo.auth.web;

import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.security.CorsProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Defense-in-depth for the two admin-web endpoints that rely on an ambient cookie
 * ({@link AdminAuthCookies#REFRESH_TOKEN}) rather than an explicit Bearer header - everything else in
 * this API is immune to CSRF by construction (a cross-site request cannot forge an Authorization
 * header), but a cookie-authenticated endpoint can be hit by a cross-site form/fetch that merely
 * knows the URL, so these checks close that gap:
 *
 * <ol>
 *   <li>Double-submit CSRF token: the header must match the cookie, compared in constant time
 *   ({@link MessageDigest#isEqual}, not {@code String.equals}, which short-circuits on the first
 *   differing byte and so leaks a token's length/prefix through response timing). A cross-site
 *   attacker's page can trigger a request that carries the cookie (browsers do that automatically),
 *   but it cannot read the cookie's value (that's the whole point of same-origin policy) to put into
 *   the header.</li>
 *   <li>{@code Origin} allow-list: required on every request, not just checked when present - belt-
 *   and-suspenders against any client that omits the CSRF header entirely or any proxy that strips it
 *   (which would otherwise fail open, not closed).</li>
 *   <li>{@code Referer} allow-list: validated only when the header is present (some privacy-preserving
 *   clients omit it even same-origin), but if present it must name an allowed origin - closes the gap
 *   of a hypothetical future client/proxy that forwards a spoofed {@code Origin} but can't also spoof
 *   {@code Referer} without the browser's cooperation.</li>
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
        if (csrfCookieValue == null || header == null || !constantTimeEquals(header, csrfCookieValue)) {
            throw new ForbiddenException("Missing or invalid CSRF token.");
        }
        String origin = request.getHeader("Origin");
        if (origin == null || !corsProperties.allowedOrigins().contains(origin)) {
            throw new ForbiddenException("Request origin is not allowed.");
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !corsProperties.allowedOrigins().contains(originOf(referer))) {
            throw new ForbiddenException("Request referer is not allowed.");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** {scheme}://{host}[:{port}] from a Referer header's full URL, matching the bare-origin strings
     *  {@code ladygo.cors.allowed-origins} is configured with. An unparsable Referer can never match
     *  any configured origin, so it falls through to the rejection path rather than throwing here. */
    private static String originOf(String referer) {
        try {
            URI uri = URI.create(referer);
            int port = uri.getPort();
            String portSuffix = port == -1 ? "" : ":" + port;
            return uri.getScheme() + "://" + uri.getHost() + portSuffix;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
