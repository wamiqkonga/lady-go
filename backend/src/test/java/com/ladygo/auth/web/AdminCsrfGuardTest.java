package com.ladygo.auth.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.security.CorsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Unit-level coverage of {@link AdminCsrfGuard} - the integration tests in
 *  {@code AdminAuthControllerIntegrationTest} exercise it end to end, but this isolates the guard's
 *  own branches (constant-time token compare, Referer allow-list) from the rest of the refresh/logout
 *  request stack. */
class AdminCsrfGuardTest {

    private final AdminCsrfGuard guard = new AdminCsrfGuard(new CorsProperties(List.of("http://localhost:5173")));

    private MockHttpServletRequest request(String origin, String referer, String csrfHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        if (csrfHeader != null) {
            request.addHeader(AdminAuthCookies.CSRF_HEADER, csrfHeader);
        }
        return request;
    }

    @Test
    void validOriginAndMatchingTokenPasses() {
        assertThatCode(() -> guard.verify(request("http://localhost:5173", null, "token-1"), "token-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void aTokenThatDiffersOnlyInItsLastCharacterIsStillRejected() {
        assertThatThrownBy(() -> guard.verify(request("http://localhost:5173", null, "token-1"), "token-2"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aTokenOfADifferentLengthIsRejected() {
        assertThatThrownBy(() -> guard.verify(request("http://localhost:5173", null, "short"), "a-much-longer-token"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void missingOriginIsRejectedEvenWithAMatchingToken() {
        assertThatThrownBy(() -> guard.verify(request(null, null, "token-1"), "token-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anOriginNotOnTheAllowListIsRejected() {
        assertThatThrownBy(() -> guard.verify(request("https://attacker.example", null, "token-1"), "token-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aMatchingRefererAlongsideAValidOriginPasses() {
        assertThatCode(() -> guard.verify(
                request("http://localhost:5173", "http://localhost:5173/dashboard", "token-1"), "token-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void aRefererOnADifferentOriginIsRejectedEvenWithAValidOrigin() {
        assertThatThrownBy(() -> guard.verify(
                request("http://localhost:5173", "https://attacker.example/evil", "token-1"), "token-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anAbsentRefererIsNotRequired() {
        assertThatCode(() -> guard.verify(request("http://localhost:5173", null, "token-1"), "token-1"))
                .doesNotThrowAnyException();
    }
}
