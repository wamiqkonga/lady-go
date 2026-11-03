package com.ladygo.admin.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.security.jwt.JwtService;
import com.ladygo.testsupport.PersistenceTestSupport;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Shared HTTP helpers for the admin pricing-rule tests. Request bodies are plain maps on purpose: the tests
 *  assert the wire contract (field names, whole-paise numbers), not the Java DTOs. */
abstract class PricingRuleHttpSupport extends PersistenceTestSupport {

    protected static final String BASE = "/api/v1/admin/pricing-rules";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JwtService jwtService;
    @Autowired protected ObjectMapper objectMapper;

    /** Token of a SUPER_ADMIN who has an authenticator enrolled and has just stepped up. */
    protected String steppedUpSuperAdminToken(UUID userId) {
        User user = users.findById(userId).orElseThrow();
        user.setMfaEnabled(true);
        users.saveAndFlush(user);
        return jwtService.issueAccessToken(userId, UserRole.SUPER_ADMIN, Duration.ofMinutes(15), Instant.now());
    }

    protected String tokenFor(UUID userId, UserRole role) {
        return jwtService.issueAccessToken(userId, role, Duration.ofMinutes(15));
    }

    /** Whole-paise body; the figures are test-only (not any real fare) and sit within 50 percent of the fixture
     *  rule from {@code newPricingRule}, so they are an ordinary change, not a large one. */
    protected Map<String, Object> body(UUID cityId, String vehicleType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cityId", cityId.toString());
        body.put("vehicleType", vehicleType);
        body.put("baseFare", 5500);
        body.put("perKm", 1200);
        body.put("perMinute", 200);
        body.put("minimumFare", 6000);
        body.put("cancellationFee", 5000);
        body.put("serviceFeePct", 5);
        body.put("serviceFeeFlat", 0);
        return body;
    }

    protected Map<String, Object> createBody(UUID cityId, String vehicleType, UUID expectedLatestRuleId, String reason) {
        Map<String, Object> body = body(cityId, vehicleType);
        body.put("expectedLatestRuleId", expectedLatestRuleId == null ? null : expectedLatestRuleId.toString());
        body.put("reason", reason);
        return body;
    }

    protected ResultActions postJson(String path, String bearer, Object body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : objectMapper.writeValueAsString(body)));
    }

    protected ResultActions create(String bearer, Object body) throws Exception {
        return postJson(BASE, bearer, body);
    }

    protected ResultActions list(String bearer, UUID cityId, String vehicleType) throws Exception {
        return mockMvc.perform(get(BASE).param("cityId", cityId.toString()).param("vehicleType", vehicleType)
                .header("Authorization", "Bearer " + bearer));
    }
}
