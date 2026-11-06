package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ladygo.admin.service.AdminUserManagementService;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Committed (no test transaction) because both properties are about real concurrent transactions and real
 *  committed state: two SUPER_ADMINs demoting each other must not leave zero, and a deactivated account's still-valid
 *  token must be refused on the very next request. Rows created here are deleted afterwards. */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminUserManagementCommittedIntegrationTest extends PersistenceTestSupport {

    @Autowired private AdminUserManagementService management;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;

    private final List<UUID> created = new ArrayList<>();

    private User superAdmin() {
        User user = new User();
        user.setPhone(nextPhone());
        user.setRole(UserRole.SUPER_ADMIN);
        user.setEmail("committed-" + UUID.randomUUID() + "@example.test");
        user.setName("Committed SA");
        User saved = users.saveAndFlush(user);
        created.add(saved.getId());
        return saved;
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM audit_logs WHERE actor_id = ? OR target_id = ?", id, id.toString());
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void twoSuperAdminsDemotingEachOtherAtTheSameTimeNeverLeaveZero() throws Exception {
        User a = superAdmin();
        User b = superAdmin();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE' AND id NOT IN (?, ?)",
                Integer.class, a.getId(), b.getId())).as("no other SUPER_ADMIN may exist in the test database").isZero();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> aDemotesB = pool.submit(demote(go, a.getId(), b.getId()));
            Future<Object> bDemotesA = pool.submit(demote(go, b.getId(), a.getId()));
            go.countDown();
            List<Object> outcomes = List.of(aDemotesB.get(30, TimeUnit.SECONDS), bDemotesA.get(30, TimeUnit.SECONDS));

            long wins = outcomes.stream().filter(o -> !(o instanceof Throwable)).count();
            List<Object> losses = outcomes.stream().filter(o -> o instanceof Throwable).toList();
            assertThat(wins).as("exactly one demotion wins").isEqualTo(1);
            assertThat(losses).hasSize(1);
            // ForbiddenException is what the API answers with 403.
            assertThat(losses.get(0)).isInstanceOf(ForbiddenException.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE' AND id IN (?, ?)",
                Integer.class, a.getId(), b.getId())).as("at least one active SUPER_ADMIN remains").isEqualTo(1);
    }

    private Callable<Object> demote(CountDownLatch go, UUID actor, UUID target) {
        return () -> {
            go.await();
            try {
                return management.changeRole(actor, target, UserRole.SUPPORT, "race", "203.0.113.9");
            } catch (RuntimeException ex) {
                return ex;
            }
        };
    }

    @Test
    void aDeactivatedAccountsUnexpiredAccessTokenIsRefusedOnTheNextRequest() throws Exception {
        User actor = superAdmin();
        User admin = superAdmin();
        // The target is an ADMIN so deactivating it is allowed while the actor remains.
        admin.setRole(UserRole.ADMIN);
        users.saveAndFlush(admin);
        String token = jwtService.issueAccessToken(admin.getId(), UserRole.ADMIN, Duration.ofMinutes(10), Instant.now());
        mockMvc.perform(get("/api/v1/admin/config").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        management.deactivate(actor.getId(), admin.getId(), "left the company", "203.0.113.9");

        assertThat(users.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.SUSPENDED);
        mockMvc.perform(get("/api/v1/admin/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/users/" + actor.getId() + "/deactivate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
