package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.notifications.email.EmailSender;
import com.ladygo.security.crypto.MfaSecretCipher;
import com.ladygo.security.crypto.TotpService;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Not wrapped in a test transaction, because the point is that the invite's own transaction rolls back: if the email
 *  is not accepted, no account (and no link) is left behind. */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminUserInviteRollbackIntegrationTest extends PersistenceTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private TotpService totpService;
    @Autowired private MfaSecretCipher mfaSecretCipher;

    @MockitoBean private EmailSender emailSender;

    private UUID actorId;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", actorId);
        jdbc.update("DELETE FROM users WHERE id = ?", actorId);
    }

    @Test
    void inviteIsRolledBackWhenTheEmailCannotBeSent() throws Exception {
        User actor = new User();
        actor.setPhone(nextPhone());
        actor.setRole(UserRole.SUPER_ADMIN);
        actor.setEmail("rollback-actor-" + UUID.randomUUID() + "@example.test");
        actor.setMfaSecretEnc(mfaSecretCipher.encrypt(totpService.generateSecret()));
        actor.setMfaEnabled(true);
        actorId = users.saveAndFlush(actor).getId();
        when(emailSender.send(anyString(), anyString(), anyString())).thenReturn(false);
        String token = jwtService.issueAccessToken(actorId, UserRole.SUPER_ADMIN, Duration.ofMinutes(10), Instant.now());

        mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nosend@example.test\",\"name\":\"No Send\",\"role\":\"SUPPORT\",\"reason\":\"r\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EMAIL_DELIVERY_FAILED"));

        assertThat(users.findByEmailIgnoreCase("nosend@example.test")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ADMIN_USER_INVITED' AND actor_id = ?",
                Integer.class, actorId)).isZero();
    }
}
