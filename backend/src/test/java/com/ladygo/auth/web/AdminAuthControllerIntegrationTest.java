package com.ladygo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
@AutoConfigureMockMvc
class AdminAuthControllerIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(4000);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TotpService totpService;
    @Autowired
    private MfaSecretCipher mfaSecretCipher;

    /** The admin login rate limiter is IP-scoped and database-backed (never released on an IP bucket,
     *  by design - see {@code AdminAuthServiceIntegrationTest}), so every test in this class needs its
     *  own distinct source IP - otherwise they all share MockMvc's default "127.0.0.1" and collide. */
    private String freshIp() {
        int n = SEQ.incrementAndGet();
        return "203.0." + (n / 250) + "." + (n % 250 + 1);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** A fully-enrolled admin (MFA already confirmed), to actually reach the Authenticated branch -
     *  every existing test above only ever reaches EnrolmentRequired. */
    private String[] newEnrolledAdminAndLogin() throws Exception {
        String email = "enrolled" + SEQ.incrementAndGet() + "@ladygo.test";
        String password = "InitialPass123!";
        User admin = new User();
        admin.setPhone("+9197" + String.format("%08d", SEQ.incrementAndGet()));
        admin.setEmail(email);
        admin.setRole(UserRole.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setMustChangePassword(false);
        admin.setMfaEnabled(true);
        String secret = totpService.generateSecret();
        admin.setMfaSecretEnc(mfaSecretCipher.encrypt(secret));
        users.saveAndFlush(admin);
        String code = totpService.generateCode(secret, totpService.currentStep(Instant.now()));
        return new String[] {email, password, code, freshIp()};
    }

    private MvcResult login(String email, String password, String code, String ip) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/admin/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .with(remoteAddr(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password
                                + "\",\"totpCode\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void loginEndpointIsPublicButRejectsUnknownCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@ladygo.test\",\"password\":\"whatever12345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void enrolmentEndpointsRequireAnEnrolmentToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/password/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/admin/mfa/enroll"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidEmailFailsValidationBeforeHittingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"whatever12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * Adversarial review finding (HIGH): an enrolment-scoped token satisfied the blanket
     * anyRequest().authenticated() rule and could reach endpoints carrying no role-specific
     * @PreAuthorize, contradicting auth-design.md section 3.2's claim that it is "only accepted by
     * the three enrolment endpoints... nothing else." /auth/logout is exactly such an endpoint.
     */
    @Test
    void enrolmentTokenCannotBeUsedOnNonEnrolmentEndpoints() throws Exception {
        String email = "pending" + SEQ.incrementAndGet() + "@ladygo.test";
        User staff = new User();
        staff.setPhone("+9193" + String.format("%08d", SEQ.incrementAndGet()));
        staff.setEmail(email);
        staff.setRole(UserRole.ADMIN);
        staff.setPasswordHash(passwordEncoder.encode("InitialPass123!"));
        staff.setMustChangePassword(true);
        staff.setMfaEnabled(false);
        users.saveAndFlush(staff);

        String loginResponse = mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"InitialPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String enrolmentToken = com.jayway.jsonpath.JsonPath.read(loginResponse, "$.data.enrolmentToken");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + enrolmentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"anything\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** docs/product/admin-web-design.md section 7's core contract: the refresh token never appears
     *  in the body - only in an HttpOnly cookie - and a CSRF cookie is issued alongside it. */
    @Test
    void successfulLoginReturnsOnlyAnAccessTokenAndSetsSessionCookies() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();

        MvcResult result = login(creds[0], creds[1], creds[2], creds[3]);

        jsonPath("$.data.accessToken").exists().match(result);
        jsonPath("$.data.refreshToken").doesNotExist().match(result);
        Cookie refreshCookie = result.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = result.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        assertThat(refreshCookie.getSecure()).isTrue();
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        // A real bug this exact assertion would have caught: a cookie's Path attribute gates whether
        // document.cookie can see it at all from a given page, matched against the PAGE's path, not
        // just the request path - admin-web's own pages (/dashboard, /driver-verification/...) never
        // live under /api/v1/auth/admin, so scoping the JS-readable CSRF cookie that narrowly (the
        // refresh cookie's own correct scope) made it permanently invisible to admin-web's own JS
        // from any real page, breaking silent session restore on every reload. Only a real browser
        // (not MockMvc, which attaches cookies/headers directly) can exercise this.
        assertThat(csrfCookie.getPath()).isEqualTo("/");
        assertThat(refreshCookie.getPath()).isEqualTo("/api/v1/auth/admin");
    }

    @Test
    void refreshWithValidCookieAndCsrfHeaderIssuesANewAccessToken() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(cookie().exists(AdminAuthCookies.REFRESH_TOKEN));
    }

    /** The actual security control under test - a cross-site caller has the cookie (browsers attach
     *  it automatically) but cannot read its value to put in the header. */
    @Test
    void refreshWithoutTheCsrfHeaderIsRejected() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshWithAMismatchedCsrfHeaderIsRejected() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(AdminAuthCookies.CSRF_HEADER, "not-the-right-value")
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshFromADisallowedOriginIsRejectedEvenWithAValidCsrfHeader() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        // Belt: Spring's own CORS filter already rejects a disallowed-but-present Origin before this
        // even reaches AdminCsrfGuard - still asserted here since it's the actual end-to-end behavior.
        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", "https://attacker.example")
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isForbidden());
    }

    /** Suspenders: Spring's CORS filter only applies when the request actually carries an Origin
     *  header at all - a request with none (which a non-browser caller can trivially arrange) skips
     *  CORS entirely and would reach the controller unchecked if AdminCsrfGuard didn't separately
     *  require one. This is the test that actually isolates the guard's own origin check, since the
     *  test above passes even with that check deleted (caught upstream by Spring either way). */
    @Test
    void refreshWithAValidCsrfHeaderButNoOriginHeaderIsRejected() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isForbidden());
    }

    /** The {@code Referer} allow-list is the guard's third layer, validated only when the header is
     *  present - this isolates it from the Origin check above by sending a VALID Origin and CSRF
     *  header, differing only in Referer. */
    @Test
    void refreshWithAMismatchedRefererIsRejectedEvenWithAValidOriginAndCsrfHeader() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Referer", "https://attacker.example/evil")
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshWithAMatchingRefererAlongsideAValidOriginSucceeds() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Referer", ALLOWED_ORIGIN + "/dashboard")
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isOk());
    }

    @Test
    void logoutClearsBothCookiesAndRevokesTheRefreshToken() throws Exception {
        String[] creds = newEnrolledAdminAndLogin();
        MvcResult loginResult = login(creds[0], creds[1], creds[2], creds[3]);
        Cookie refreshCookie = loginResult.getResponse().getCookie(AdminAuthCookies.REFRESH_TOKEN);
        Cookie csrfCookie = loginResult.getResponse().getCookie(AdminAuthCookies.CSRF_TOKEN);

        mockMvc.perform(post("/api/v1/auth/admin/logout")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(AdminAuthCookies.REFRESH_TOKEN, 0));

        // The revoked refresh token must no longer rotate - same cookie, now dead.
        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header(AdminAuthCookies.CSRF_HEADER, csrfCookie.getValue())
                        .cookie(refreshCookie, csrfCookie))
                .andExpect(status().isUnauthorized());
    }
}
