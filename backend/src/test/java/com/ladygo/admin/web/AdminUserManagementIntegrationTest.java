package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.admin.service.AdminUserManagementService;
import com.ladygo.auth.repository.RefreshTokenRepository;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.notifications.email.EmailSender;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Plan step 8: SUPER_ADMIN-only staff management. Every write needs a fresh step-up, a written reason and an audit
 *  row, and the server (not the UI) refuses self-service role changes and removing the last SUPER_ADMIN. */
@AutoConfigureMockMvc
class AdminUserManagementIntegrationTest extends PersistenceTestSupport {

    private static final Pattern LINK_TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{40,})");
    private static final String NEW_PASSWORD = "Brand-new-passphrase-42";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TotpService totpService;
    @Autowired private MfaSecretCipher mfaSecretCipher;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @MockitoBean private EmailSender emailSender;

    @BeforeEach
    void emailWorks() {
        when(emailSender.send(anyString(), anyString(), anyString())).thenReturn(true);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private User staff(UserRole role, boolean enrolled) {
        User user = newUser(role);
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test");
        user.setName("Staff " + role);
        user.setPasswordHash(passwordEncoder.encode("Old-passphrase-12345"));
        if (enrolled) {
            user.setMfaSecretEnc(mfaSecretCipher.encrypt(totpService.generateSecret()));
            user.setMfaEnabled(true);
        }
        return users.saveAndFlush(user);
    }

    private String fresh(User user) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), Instant.now());
    }

    private String noStepUp(User user) {
        return jwtService.issueAccessToken(user.getId(), user.getRole(), Duration.ofMinutes(10), null);
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/users" + path).header("Authorization", "Bearer " + token));
    }

    private ResultActions postAs(String token, String path, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/users" + path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String inviteJson(String email, String role, String reason) {
        String reasonPart = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return "{\"email\":\"" + email + "\",\"name\":\"New Person\",\"role\":\"" + role + "\"" + reasonPart + "}";
    }

    private int audit(String action, UUID targetId) {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ? AND target_id = ?",
                Integer.class, action, targetId.toString());
    }

    private UUID idOf(ResultActions result) throws Exception {
        JsonNode json = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return UUID.fromString(json.at("/data/id").asText());
    }

    private String invitedToken(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), anyString(), body.capture());
        Matcher matcher = LINK_TOKEN.matcher(body.getValue());
        assertThat(matcher.find()).as("set-password link with a fragment token in the invite email").isTrue();
        return matcher.group(1);
    }

    private ResultActions setPassword(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/admin/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"));
    }

    // ---- role matrix ---------------------------------------------------------------------------------------

    @Test
    void onlySuperAdminCanListOrReadStaff() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User support = staff(UserRole.SUPPORT, false);
        User admin = staff(UserRole.ADMIN, false);

        getAs(fresh(support), "").andExpect(status().isForbidden());
        getAs(fresh(admin), "").andExpect(status().isForbidden());
        getAs(fresh(support), "/" + admin.getId()).andExpect(status().isForbidden());
        getAs(fresh(admin), "/" + support.getId()).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());

        getAs(fresh(superAdmin), "").andExpect(status().isOk());
        getAs(fresh(superAdmin), "/" + support.getId()).andExpect(status().isOk());
    }

    @Test
    void supportAndAdminCannotUseAnyWrite() throws Exception {
        User support = staff(UserRole.SUPPORT, true);
        User admin = staff(UserRole.ADMIN, true);
        User target = staff(UserRole.SUPPORT, false);
        String reason = "{\"reason\":\"because\"}";
        for (User actor : List.of(support, admin)) {
            String token = fresh(actor);
            postAs(token, "", inviteJson("x@example.test", "SUPPORT", "r")).andExpect(status().isForbidden());
            postAs(token, "/" + target.getId() + "/role", "{\"role\":\"ADMIN\",\"reason\":\"r\"}")
                    .andExpect(status().isForbidden());
            postAs(token, "/" + target.getId() + "/deactivate", reason).andExpect(status().isForbidden());
            postAs(token, "/" + target.getId() + "/reactivate", reason).andExpect(status().isForbidden());
        }
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.SUPPORT);
        assertThat(users.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void listShowsOnlyStaffWithMaskedEmailAndNoSecrets() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User support = staff(UserRole.SUPPORT, false);
        support.setLastLoginAt(Instant.parse("2026-10-01T10:00:00Z"));
        users.saveAndFlush(support);
        User customer = newUser(UserRole.CUSTOMER);

        String body = getAs(fresh(superAdmin), "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertThat(body).doesNotContain(customer.getId().toString());
        JsonNode row = null;
        for (JsonNode candidate : data) {
            if (candidate.get("id").asText().equals(support.getId().toString())) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("role").asText()).isEqualTo("SUPPORT");
        assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.get("mfaEnrolled").asBoolean()).isFalse();
        assertThat(row.get("locked").asBoolean()).isFalse();
        assertThat(row.get("lastLoginAt").asText()).startsWith("2026-10-01T10:00");
        assertThat(row.get("maskedEmail").asText()).contains("***").doesNotContain(support.getEmail());
        assertNothingSecret(body, support);
    }

    @Test
    void detailOfAMissingOrNonStaffAccountIs404() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User customer = newUser(UserRole.CUSTOMER);

        getAs(fresh(superAdmin), "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        getAs(fresh(superAdmin), "/" + customer.getId()).andExpect(status().isNotFound());
    }

    // ---- step-up and reason --------------------------------------------------------------------------------

    @Test
    void everyWriteNeedsAFreshStepUp() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.SUPPORT, false);
        String token = noStepUp(superAdmin);

        postAs(token, "", inviteJson("a@example.test", "SUPPORT", "r"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        postAs(token, "/" + target.getId() + "/role", "{\"role\":\"ADMIN\",\"reason\":\"r\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        postAs(token, "/" + target.getId() + "/deactivate", "{\"reason\":\"r\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
        postAs(token, "/" + target.getId() + "/reactivate", "{\"reason\":\"r\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        assertThat(users.findByEmailIgnoreCase("a@example.test")).isEmpty();
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.SUPPORT);
        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void everyWriteNeedsAReason() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.SUPPORT, false);
        String token = fresh(superAdmin);

        postAs(token, "", inviteJson("b@example.test", "SUPPORT", null)).andExpect(status().isBadRequest());
        postAs(token, "", inviteJson("b@example.test", "SUPPORT", "   ")).andExpect(status().isBadRequest());
        postAs(token, "/" + target.getId() + "/role", "{\"role\":\"ADMIN\"}").andExpect(status().isBadRequest());
        postAs(token, "/" + target.getId() + "/deactivate", "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        postAs(token, "/" + target.getId() + "/reactivate", "{}").andExpect(status().isBadRequest());

        assertThat(users.findByEmailIgnoreCase("b@example.test")).isEmpty();
    }

    // ---- invite --------------------------------------------------------------------------------------------

    @Test
    void inviteCreatesAPasswordlessAccountAndEmailsASingleUseLink() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        String email = "New.Hire-" + UUID.randomUUID() + "@Example.test";

        ResultActions result = postAs(fresh(superAdmin), "", inviteJson(email, "SUPPORT", "New agent for Kashmir shift"))
                .andExpect(status().isOk());
        String response = result.andReturn().getResponse().getContentAsString();
        UUID id = idOf(result);

        User created = users.findById(id).orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.SUPPORT);
        assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(created.getPasswordHash()).as("no usable password until the link is used").isNull();
        assertThat(created.getEmail()).isEqualToIgnoringCase(email);
        assertThat(audit("ADMIN_USER_INVITED", id)).isEqualTo(1);

        String token = invitedToken(created.getEmail());
        assertThat(response).doesNotContain(token).doesNotContain("passwordHash").doesNotContain("#token");
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_password_resets WHERE user_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_password_resets WHERE token_hash = ?",
                Integer.class, token)).as("only the hash is stored").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE metadata::text LIKE ?",
                Integer.class, "%" + token + "%")).isZero();

        setPassword(token).andExpect(status().isOk());
        assertThat(users.findById(id).orElseThrow().getPasswordHash()).isNotNull();
        setPassword(token).andExpect(status().isUnauthorized()); // single use
    }

    @Test
    void inviteLinkExpires() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        String email = "late-" + UUID.randomUUID() + "@example.test";
        postAs(fresh(superAdmin), "", inviteJson(email, "ADMIN", "Hire")).andExpect(status().isOk());
        String token = invitedToken(email);

        em.flush();
        jdbc.update("UPDATE admin_password_resets SET expires_at = now() - interval '1 minute' WHERE user_id = "
                + "(SELECT id FROM users WHERE lower(email) = lower(?))", email);

        setPassword(token).andExpect(status().isUnauthorized());
    }

    @Test
    void inviteRejectsBadEmailBadRoleAndDuplicates() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        String token = fresh(superAdmin);

        postAs(token, "", inviteJson("not-an-email", "SUPPORT", "r")).andExpect(status().isBadRequest());
        postAs(token, "", inviteJson("c@example.test", "CUSTOMER", "r")).andExpect(status().isBadRequest());
        postAs(token, "", inviteJson("c@example.test", "DRIVER", "r")).andExpect(status().isBadRequest());
        postAs(token, "", inviteJson("c@example.test", "WIZARD", "r")).andExpect(status().isBadRequest());
        postAs(token, "", inviteJson(superAdmin.getEmail(), "SUPPORT", "r")).andExpect(status().isBadRequest());
        postAs(token, "", inviteJson(superAdmin.getEmail().toUpperCase(), "SUPPORT", "r"))
                .andExpect(status().isBadRequest());

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        assertThat(users.findByEmailIgnoreCase("c@example.test")).isEmpty();
    }

    @Test
    void anInviteeCanSignInAsStaffButNotUseSuperAdminEndpoints() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        String email = "agent-" + UUID.randomUUID() + "@example.test";
        postAs(fresh(superAdmin), "", inviteJson(email, "SUPPORT", "Hire")).andExpect(status().isOk());
        setPassword(invitedToken(email)).andExpect(status().isOk());
        User invitee = users.findByEmailIgnoreCase(email).orElseThrow();

        getAs(fresh(invitee), "").andExpect(status().isForbidden());
        postAs(fresh(invitee), "/" + superAdmin.getId() + "/deactivate", "{\"reason\":\"r\"}")
                .andExpect(status().isForbidden());
    }

    // ---- role change ---------------------------------------------------------------------------------------

    @Test
    void roleChangeUpdatesTheRoleAuditsAndSignsTheTargetOut() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.SUPPORT, false);
        refreshTokenService.issue(target.getId(), "phone");

        postAs(fresh(superAdmin), "/" + target.getId() + "/role", "{\"role\":\"ADMIN\",\"reason\":\"Promoted\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));

        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(audit("ADMIN_USER_ROLE_CHANGED", target.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT metadata->>'reason' FROM audit_logs WHERE action = 'ADMIN_USER_ROLE_CHANGED' AND target_id = ?",
                String.class, target.getId().toString())).isEqualTo("Promoted");
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(target.getId())).isEmpty();
    }

    @Test
    void roleChangeRejectsNonStaffRolesSameRoleAndNonStaffTargets() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.SUPPORT, false);
        User customer = newUser(UserRole.CUSTOMER);
        String token = fresh(superAdmin);

        postAs(token, "/" + target.getId() + "/role", "{\"role\":\"CUSTOMER\",\"reason\":\"r\"}")
                .andExpect(status().isBadRequest());
        postAs(token, "/" + target.getId() + "/role", "{\"role\":\"SUPPORT\",\"reason\":\"r\"}")
                .andExpect(status().isBadRequest());
        postAs(token, "/" + customer.getId() + "/role", "{\"role\":\"ADMIN\",\"reason\":\"r\"}")
                .andExpect(status().isNotFound());
        assertThat(users.findById(customer.getId()).orElseThrow().getRole()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void nobodyCanChangeTheirOwnRole() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        staff(UserRole.SUPER_ADMIN, true); // a second one, so the "last super admin" rule is not what refuses this.

        postAs(fresh(superAdmin), "/" + superAdmin.getId() + "/role", "{\"role\":\"ADMIN\",\"reason\":\"r\"}")
                .andExpect(status().isForbidden());

        assertThat(users.findById(superAdmin.getId()).orElseThrow().getRole()).isEqualTo(UserRole.SUPER_ADMIN);
        assertThat(audit("ADMIN_USER_ROLE_CHANGED", superAdmin.getId())).isZero();
    }

    @Test
    void aSuperAdminCanDemoteAnotherWhileOneRemains() throws Exception {
        User actor = staff(UserRole.SUPER_ADMIN, true);
        User other = staff(UserRole.SUPER_ADMIN, false);

        postAs(fresh(actor), "/" + other.getId() + "/role", "{\"role\":\"ADMIN\",\"reason\":\"Step down\"}")
                .andExpect(status().isOk());

        assertThat(users.findById(other.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
    }

    /** A token minted before the actor was demoted must not keep working: the role is re-read from the database. */
    @Test
    void aStaleSuperAdminTokenIsRefusedOnWrites() throws Exception {
        User actor = staff(UserRole.SUPER_ADMIN, true);
        String staleToken = fresh(actor);
        actor.setRole(UserRole.ADMIN);
        users.saveAndFlush(actor);
        User target = staff(UserRole.SUPPORT, false);

        postAs(staleToken, "/" + target.getId() + "/deactivate", "{\"reason\":\"r\"}")
                .andExpect(status().isUnauthorized()); // the filter drops a token whose role no longer matches.

        assertThat(users.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void theLastActiveSuperAdminCanNeverBeRemoved() {
        UUID only = UUID.randomUUID();
        User sole = new User();
        sole.setRole(UserRole.SUPER_ADMIN);
        sole.setStatus(UserStatus.ACTIVE);
        org.springframework.test.util.ReflectionTestUtils.setField(sole, "id", only);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> AdminUserManagementService.requireAnotherActiveSuperAdmin(List.of(sole), only))
                .isInstanceOf(com.ladygo.common.exception.ForbiddenException.class);

        User second = new User();
        second.setRole(UserRole.SUPER_ADMIN);
        second.setStatus(UserStatus.ACTIVE);
        org.springframework.test.util.ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
        AdminUserManagementService.requireAnotherActiveSuperAdmin(List.of(sole, second), only); // no throw
    }

    // ---- deactivate / reactivate ---------------------------------------------------------------------------

    @Test
    void deactivateBlocksTheAccountRevokesSessionsAndAudits() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.ADMIN, true);
        refreshTokenService.issue(target.getId(), "laptop");
        refreshTokenService.issue(target.getId(), "phone");

        postAs(fresh(superAdmin), "/" + target.getId() + "/deactivate", "{\"reason\":\"Left the company\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        User after = users.findById(target.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(after.getStatusChangedAt()).isNotNull();
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(target.getId())).isEmpty();
        assertThat(audit("ADMIN_USER_DEACTIVATED", target.getId())).isEqualTo(1);

        // A step-up token the target already holds stops working too.
        postAs(fresh(target), "/" + superAdmin.getId() + "/deactivate", "{\"reason\":\"r\"}")
                .andExpect(status().is4xxClientError());
        assertThat(users.findById(superAdmin.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void nobodyCanDeactivateThemselves() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        staff(UserRole.SUPER_ADMIN, true);

        postAs(fresh(superAdmin), "/" + superAdmin.getId() + "/deactivate", "{\"reason\":\"r\"}")
                .andExpect(status().isForbidden());

        assertThat(users.findById(superAdmin.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(audit("ADMIN_USER_DEACTIVATED", superAdmin.getId())).isZero();
    }

    @Test
    void reactivateRestoresASuspendedAccountOnly() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.SUPPORT, false);
        String token = fresh(superAdmin);

        postAs(token, "/" + target.getId() + "/reactivate", "{\"reason\":\"r\"}").andExpect(status().isBadRequest());
        postAs(token, "/" + target.getId() + "/deactivate", "{\"reason\":\"r\"}").andExpect(status().isOk());
        postAs(token, "/" + target.getId() + "/deactivate", "{\"reason\":\"r\"}").andExpect(status().isBadRequest());
        postAs(token, "/" + target.getId() + "/reactivate", "{\"reason\":\"Back from leave\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));

        assertThat(users.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(audit("ADMIN_USER_REACTIVATED", target.getId())).isEqualTo(1);
    }

    @Test
    void aDeactivatedAccountCannotUseItsInviteOrResetLink() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        String email = "gone-" + UUID.randomUUID() + "@example.test";
        postAs(fresh(superAdmin), "", inviteJson(email, "SUPPORT", "Hire")).andExpect(status().isOk());
        String token = invitedToken(email);
        UUID id = users.findByEmailIgnoreCase(email).orElseThrow().getId();

        postAs(fresh(superAdmin), "/" + id + "/deactivate", "{\"reason\":\"Changed mind\"}").andExpect(status().isOk());

        setPassword(token).andExpect(status().isUnauthorized());
        assertThat(users.findById(id).orElseThrow().getPasswordHash()).isNull();
    }

    /** An access token minted before a deactivation or demotion must stop working at once, not at its expiry. */
    @Test
    void deactivatedOrDemotedStaffTokensStopWorkingImmediately() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User admin = staff(UserRole.ADMIN, true);
        User demoted = staff(UserRole.SUPER_ADMIN, true);
        String adminToken = fresh(admin);
        String demotedToken = fresh(demoted);
        mockMvc.perform(get("/api/v1/admin/config").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        getAs(demotedToken, "").andExpect(status().isOk());

        postAs(fresh(superAdmin), "/" + admin.getId() + "/deactivate", "{\"reason\":\"r\"}").andExpect(status().isOk());
        postAs(fresh(superAdmin), "/" + demoted.getId() + "/role", "{\"role\":\"SUPPORT\",\"reason\":\"r\"}")
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        mockMvc.perform(get("/api/v1/admin/config").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
        getAs(demotedToken, "").andExpect(status().isUnauthorized());
    }

    // ---- secrets -------------------------------------------------------------------------------------------

    @Test
    void detailAndWriteResponsesNeverContainSecrets() throws Exception {
        User superAdmin = staff(UserRole.SUPER_ADMIN, true);
        User target = staff(UserRole.ADMIN, true);

        assertNothingSecret(getAs(fresh(superAdmin), "/" + target.getId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), target);
        assertNothingSecret(postAs(fresh(superAdmin), "/" + target.getId() + "/role",
                "{\"role\":\"SUPPORT\",\"reason\":\"r\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), target);
    }

    private void assertNothingSecret(String body, User user) {
        assertThat(body).doesNotContain(user.getPasswordHash()).doesNotContain(user.getEmail())
                .doesNotContain("passwordHash").doesNotContain("password_hash").doesNotContain("mfaSecret")
                .doesNotContain("token").doesNotContain(user.getMfaSecretEnc() == null ? "zz-none" : user.getMfaSecretEnc());
    }
}
