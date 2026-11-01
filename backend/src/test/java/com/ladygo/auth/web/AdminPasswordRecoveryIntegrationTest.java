package com.ladygo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.auth.repository.RefreshTokenRepository;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** docs/security/admin-stepup-lockout-recovery-design.md section 4: an emailed, single-use, short-lived reset link
 *  that never reveals whether an address belongs to an admin, still needs a TOTP code when MFA is enrolled, and
 *  signs every existing session out. */
/** Deliberately NOT wrapped in a test transaction: the attempt limits only count if they are committed even when a
 *  request is rejected, and a surrounding transaction would hide exactly that. Rows are removed in {@link #cleanUp}. */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminPasswordRecoveryIntegrationTest extends PersistenceTestSupport {

    private static final Pattern LINK_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{40,})");
    private static final String NEW_PASSWORD = "Brand-new-passphrase-42";

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TotpService totpService;
    @Autowired private MfaSecretCipher mfaSecretCipher;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmailSender emailSender;

    private String totpSecret;
    private final List<UUID> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : createdUsers) {
            jdbc.update("DELETE FROM admin_password_resets WHERE user_id = ?", id);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ? OR target_id = ?", id, id.toString());
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        createdUsers.clear();
        jdbc.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE 'admin_pwreset%'");
    }

    private User admin(String email, boolean withMfa) {
        User user = newUser(UserRole.SUPER_ADMIN);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("Old-passphrase-12345"));
        if (withMfa) {
            totpSecret = totpService.generateSecret();
            user.setMfaSecretEnc(mfaSecretCipher.encrypt(totpSecret));
            user.setMfaEnabled(true);
        }
        User saved = users.saveAndFlush(user);
        createdUsers.add(saved.getId());
        return saved;
    }

    /** Waits until the worker has dealt with every earlier request: it is a single FIFO thread, so once this known
     *  admin's email arrives, anything requested before it has already been decided. */
    private void awaitWorker() throws Exception {
        String control = "control-" + UUID.randomUUID() + "@example.test";
        admin(control, false);
        forgot(control);
        verify(emailSender, timeout(5000)).send(eq(control), anyString(), anyString());
    }

    private void forgot(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
    }

    /** Requests a link for a known admin and returns the raw token from the email the user would receive. */
    private String requestToken(String email) throws Exception {
        forgot(email);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(3000)).send(eq(email), anyString(), body.capture());
        Matcher matcher = LINK_TOKEN.matcher(body.getValue());
        assertThat(matcher.find()).as("reset link with a fragment token in the email").isTrue();
        return matcher.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions reset(String token, String password, String totp)
            throws Exception {
        String totpJson = totp == null ? "" : ",\"totpCode\":\"" + totp + "\"";
        return mockMvc.perform(post("/api/v1/auth/admin/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"" + totpJson + "}"));
    }

    private String freshCode() {
        return totpService.generateCode(totpSecret, totpService.currentStep(Instant.now()));
    }

    @Test
    void anUnknownEmailGetsTheSameReplyAndNoEmailIsSent() throws Exception {
        forgot("nobody@example.test");
        awaitWorker();

        verify(emailSender, never()).send(eq("nobody@example.test"), anyString(), anyString());
    }

    @Test
    void aCustomerOrSuspendedAdminNeverReceivesALink() throws Exception {
        User customer = newUser(UserRole.CUSTOMER);
        createdUsers.add(customer.getId());
        customer.setEmail("rider@example.test");
        users.saveAndFlush(customer);
        User suspended = admin("suspended@example.test", false);
        suspended.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(suspended);

        forgot("rider@example.test");
        forgot("suspended@example.test");
        awaitWorker();

        verify(emailSender, never()).send(eq("rider@example.test"), anyString(), anyString());
        verify(emailSender, never()).send(eq("suspended@example.test"), anyString(), anyString());
    }

    @Test
    void theTokenIsStoredOnlyAsAHashAndExpiresInThirtyMinutes() throws Exception {
        admin("owner@example.test", false);

        String token = requestToken("owner@example.test");

        String stored = jdbc.queryForObject("SELECT token_hash FROM admin_password_resets", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(token);
        Long minutes = jdbc.queryForObject(
                "SELECT round(extract(epoch FROM (expires_at - created_at)) / 60) FROM admin_password_resets",
                Long.class);
        assertThat(minutes).isEqualTo(30L);
    }

    @Test
    void aValidLinkSetsTheNewPasswordRevokesEverySessionAndIsAudited() throws Exception {
        User user = admin("owner@example.test", false);
        refreshTokenService.issue(user.getId(), "device-1");
        String token = requestToken("owner@example.test");

        reset(token, NEW_PASSWORD, null).andExpect(status().isOk());

        User reloaded = users.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash())).isTrue();
        assertThat(reloaded.isMustChangePassword()).isFalse();
        assertThat(refreshTokens.findAll())
                .filteredOn(t -> t.getUserId().equals(user.getId()))
                .allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        assertThat(auditLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("ADMIN_PASSWORD_RESET");
            assertThat(log.getTargetId()).isEqualTo(user.getId().toString());
        });
    }

    @Test
    void aLinkCanBeUsedOnlyOnce() throws Exception {
        admin("owner@example.test", false);
        String token = requestToken("owner@example.test");

        reset(token, NEW_PASSWORD, null).andExpect(status().isOk());
        reset(token, "Another-passphrase-77", null).andExpect(status().isUnauthorized());
    }

    /** Asking again must not cancel the link the owner already has (anyone could otherwise keep them locked out by
     *  submitting their address), but using either link closes every other open one. */
    @Test
    void aNewRequestKeepsTheEarlierLinkAndUsingOneClosesTheRest() throws Exception {
        admin("owner@example.test", false);
        String first = requestToken("owner@example.test");
        forgot("owner@example.test");
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(3000).times(2)).send(eq("owner@example.test"), anyString(), bodies.capture());
        Matcher matcher = LINK_TOKEN.matcher(bodies.getAllValues().get(1));
        assertThat(matcher.find()).isTrue();
        String second = matcher.group(1);

        reset(second, NEW_PASSWORD, null).andExpect(status().isOk());
        reset(first, "Another-passphrase-77", null).andExpect(status().isUnauthorized());
    }

    @Test
    void theEmailStatesTheConfiguredLifetime() throws Exception {
        admin("owner@example.test", false);
        jdbc.update("UPDATE configuration SET value = '10' WHERE key = 'admin.password_reset.ttl.minutes'");
        try {
            forgot("owner@example.test");
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(emailSender, timeout(3000)).send(eq("owner@example.test"), anyString(), body.capture());
            assertThat(body.getValue()).contains("within 10 minutes");
        } finally {
            jdbc.update("UPDATE configuration SET value = '30' WHERE key = 'admin.password_reset.ttl.minutes'");
        }
    }

    @Test
    void aResetClearsAnUnfinishedAuthenticatorEnrolment() throws Exception {
        User user = admin("owner@example.test", false);
        jdbc.update("UPDATE users SET mfa_pending_secret_enc = 'pending' WHERE id = ?", user.getId());
        String token = requestToken("owner@example.test");

        reset(token, NEW_PASSWORD, null).andExpect(status().isOk());

        String pending = jdbc.queryForObject("SELECT mfa_pending_secret_enc FROM users WHERE id = ?", String.class, user.getId());
        assertThat(pending).isNull();
    }

    @Test
    void anExpiredLinkIsRejected() throws Exception {
        admin("owner@example.test", false);
        String token = requestToken("owner@example.test");
        jdbc.update("UPDATE admin_password_resets SET expires_at = now() - interval '1 minute'");

        reset(token, NEW_PASSWORD, null).andExpect(status().isUnauthorized());
    }

    @Test
    void aGarbageTokenIsRejectedTheSameWay() throws Exception {
        reset("A".repeat(43), NEW_PASSWORD, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void aTooShortPasswordIsRejectedAndTheLinkStaysUsable() throws Exception {
        admin("owner@example.test", false);
        String token = requestToken("owner@example.test");

        reset(token, "short", null).andExpect(status().isBadRequest());
        reset(token, NEW_PASSWORD, null).andExpect(status().isOk());
    }

    @Test
    void anAdminWithMfaMustGiveAValidTotpCode() throws Exception {
        User user = admin("owner@example.test", true);
        String token = requestToken("owner@example.test");

        reset(token, NEW_PASSWORD, null).andExpect(status().isUnauthorized());
        reset(token, NEW_PASSWORD, "000000").andExpect(status().isUnauthorized());
        assertThat(passwordEncoder.matches(NEW_PASSWORD, users.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isFalse();

        reset(token, NEW_PASSWORD, freshCode()).andExpect(status().isOk());
        assertThat(passwordEncoder.matches(NEW_PASSWORD, users.findById(user.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @Test
    void repeatedWrongTotpCodesAreRateLimited() throws Exception {
        admin("owner@example.test", true);
        String token = requestToken("owner@example.test");

        for (int i = 0; i < 5; i++) {
            reset(token, NEW_PASSWORD, "000000").andExpect(status().isUnauthorized());
        }
        // The sixth attempt is refused even with the right code: guessing is capped per account.
        reset(token, NEW_PASSWORD, freshCode()).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgotPasswordIsRateLimitedPerEmailWithoutRevealingIt() throws Exception {
        admin("owner@example.test", false);

        for (int i = 0; i < 6; i++) {
            forgot("owner@example.test");
        }

        // Three links are allowed per window; the other three requests are answered the same but send nothing.
        verify(emailSender, timeout(3000).times(3)).send(eq("owner@example.test"), anyString(), anyString());
        awaitWorker();
        verify(emailSender, times(3)).send(eq("owner@example.test"), anyString(), anyString());
    }

    @Test
    void aSuccessfulResetSendsAChangeNotificationWithoutTheToken() throws Exception {
        admin("owner@example.test", false);
        String token = requestToken("owner@example.test");

        reset(token, NEW_PASSWORD, null).andExpect(status().isOk());

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(3000).times(2)).send(eq("owner@example.test"), anyString(), bodies.capture());
        assertThat(bodies.getAllValues().get(1)).contains("password was changed").doesNotContain(token);
    }

    @Test
    void theRecoveryEndpointsArePublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }
}
