package com.ladygo.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.locations.domain.City;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.pricing.service.FareCalculator;
import com.ladygo.pricing.service.FareResult;
import com.ladygo.users.domain.UserRole;
import com.ladygo.vehicles.domain.VehicleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/development/plans/2026-11-03-admin-pricing-rules.md sections 4 and 7 (transactional half: everything that
 *  does not depend on commit timing). The committed half is {@link AdminPricingRuleCommittedIntegrationTest}. */
@AutoConfigureMockMvc
class AdminPricingRuleControllerIntegrationTest extends PricingRuleHttpSupport {

    @Autowired private AuditLogRepository auditLogs;
    @Autowired private FareCalculator fareCalculator;

    private String superAdmin() {
        return steppedUpSuperAdminToken(newUser(UserRole.SUPER_ADMIN).getId());
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    // ---- access: SUPER_ADMIN only, for reads and writes -----------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"ADMIN", "SUPPORT", "CUSTOMER", "DRIVER"})
    void everyRoleExceptSuperAdminIsRefusedOnEveryEndpoint(UserRole role) throws Exception {
        City city = newCity();
        PricingRuleRowIds rule = new PricingRuleRowIds(newPricingRule(city, VehicleType.BIKE).getId());
        String token = tokenFor(newUser(role).getId(), role);

        list(token, city.getId(), "BIKE").andExpect(status().isForbidden());
        mockMvcGet(BASE + "/cities", token).andExpect(status().isForbidden());
        postJson(BASE + "/preview", token, body(city.getId(), "BIKE")).andExpect(status().isForbidden());
        create(token, createBody(city.getId(), "BIKE", rule.id(), "reason")).andExpect(status().isForbidden());
        postJson(BASE + "/" + rule.id() + "/cancel", token, Map.of("reason", "reason")).andExpect(status().isForbidden());

        assertThat(pricingRules.findAll().stream().filter(r -> r.getCityId().equals(city.getId()))).hasSize(1);
    }

    private record PricingRuleRowIds(UUID id) { }

    private ResultActions mockMvcGet(String path, String token) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void anonymousCallsAreRefused() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE)
                .param("cityId", UUID.randomUUID().toString()).param("vehicleType", "BIKE"))
                .andExpect(status().isUnauthorized());
    }

    // ---- step-up and reason ---------------------------------------------------------------------------------------

    @Test
    void createWithoutAFreshStepUpIsRefusedAndNothingIsWritten() throws Exception {
        City city = newCity();
        var admin = newUser(UserRole.SUPER_ADMIN);
        admin.setMfaEnabled(true);
        users.saveAndFlush(admin);
        String noStepUp = jwtService.issueAccessToken(admin.getId(), UserRole.SUPER_ADMIN, java.time.Duration.ofMinutes(10));

        create(noStepUp, createBody(city.getId(), "BIKE", null, "first fare"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        assertThat(pricingRules.findAll().stream().anyMatch(r -> r.getCityId().equals(city.getId()))).isFalse();
    }

    @Test
    void cancelWithoutAFreshStepUpIsRefused() throws Exception {
        City city = newCity();
        PricingRule future = scheduled(city, 3600);
        var admin = newUser(UserRole.SUPER_ADMIN);
        admin.setMfaEnabled(true);
        users.saveAndFlush(admin);
        String noStepUp = jwtService.issueAccessToken(admin.getId(), UserRole.SUPER_ADMIN, java.time.Duration.ofMinutes(10));

        postJson(BASE + "/" + future.getId() + "/cancel", noStepUp, Map.of("reason", "oops"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));

        assertThat(pricingRules.findById(future.getId())).isPresent();
    }

    @ParameterizedTest
    @CsvSource(value = {"null", "''", "'   '"}, nullValues = "null")
    void aMissingOrBlankReasonIsRefused(String reason) throws Exception {
        City city = newCity();

        create(superAdmin(), createBody(city.getId(), "BIKE", null, reason)).andExpect(status().isBadRequest());

        assertThat(ruleCount(city)).isZero();
    }

    @Test
    void cancelNeedsAReason() throws Exception {
        City city = newCity();
        PricingRule future = scheduled(city, 3600);

        postJson(BASE + "/" + future.getId() + "/cancel", superAdmin(), Map.of("reason", " ")).andExpect(status().isBadRequest());

        assertThat(pricingRules.findById(future.getId())).isPresent();
    }

    // ---- money format ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"baseFare,12.5", "perKm,100.01", "perMinute,\"5\"", "minimumFare,1e3", "cancellationFee,-1", "serviceFeeFlat,0.5",
            "baseFare,null"})
    void moneyThatIsNotAWholeNonNegativeNumberOfPaiseIsRefused(String field, String raw) throws Exception {
        City city = newCity();
        String json = objectMapper.writeValueAsString(createBody(city.getId(), "BIKE", null, "first fare"))
                .replaceFirst("\"" + field + "\":\\d+", "\"" + field + "\":" + raw);

        create(superAdmin(), json).andExpect(status().isBadRequest());

        assertThat(ruleCount(city)).isZero();
    }

    @Test
    void aServiceFeePercentWithMoreThanTwoDecimalsIsRefused() throws Exception {
        City city = newCity();
        String json = objectMapper.writeValueAsString(createBody(city.getId(), "BIKE", null, "first fare"))
                .replace("\"serviceFeePct\":5", "\"serviceFeePct\":5.005");

        create(superAdmin(), json).andExpect(status().isBadRequest());

        assertThat(ruleCount(city)).isZero();
    }

    // ---- absolute limits (seeded configuration rows) --------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "baseFare,50000,50001", "perKm,10000,10001", "perMinute,2000,2001", "minimumFare,100000,100001",
            "cancellationFee,50000,50001", "serviceFeeFlat,10000,10001", "serviceFeePct,30,30.01"})
    void everyFieldAcceptsItsLimitAndRefusesOneStepAbove(String field, String atLimit, String aboveLimit) throws Exception {
        City city = newCity();
        // first fare: no previous value, so only the absolute limit applies (the large-change guard has nothing to compare to)
        Map<String, Object> ok = createBody(city.getId(), "BIKE", null, "first fare");
        ok.put(field, new BigDecimal(atLimit));
        // keep minimum fare coherent so only the field under test can be at fault
        create(superAdmin(), ok).andExpect(status().isCreated());

        City other = newCity();
        Map<String, Object> bad = createBody(other.getId(), "BIKE", null, "first fare");
        bad.put(field, new BigDecimal(aboveLimit));
        create(superAdmin(), bad).andExpect(status().isBadRequest());
        assertThat(ruleCount(other)).isZero();
    }

    @Test
    void zeroIsAllowedForServiceFeeAndCancellationFeeAndForAnySingleChargeField() throws Exception {
        City city = newCity();
        Map<String, Object> zero = createBody(city.getId(), "BIKE", null, "first fare");
        for (String f : List.of("baseFare", "perKm", "perMinute", "cancellationFee", "serviceFeePct", "serviceFeeFlat")) {
            zero.put(f, 0);
        }
        // minimum fare stays above zero: the fare still charges something
        create(superAdmin(), zero).andExpect(status().isCreated());
    }

    @Test
    void anAllZeroFareIsRefusedOnCreateEvenAsAFirstFareWithNoGuardToCompareWith() throws Exception {
        City city = newCity();
        Map<String, Object> free = createBody(city.getId(), "BIKE", null, "free rides");
        for (String f : List.of("baseFare", "perKm", "perMinute", "minimumFare")) {
            free.put(f, 0);
        }

        create(superAdmin(), free).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("must charge something")));

        assertThat(ruleCount(city)).isZero();
    }

    @Test
    void anAllZeroFareIsRefusedOnAChangeToo() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> free = createBody(city.getId(), "BIKE", current.getId(), "free rides");
        for (String f : List.of("baseFare", "perKm", "perMinute", "minimumFare")) {
            free.put(f, 0);
        }
        free.put("confirmLargeChange", true);

        create(superAdmin(), free).andExpect(status().isBadRequest());

        assertThat(pricingRules.findById(current.getId()).orElseThrow().getEffectiveTo()).isNull();
    }

    @Test
    void anAllZeroFareIsRefusedOnPreview() throws Exception {
        City city = newCity();
        Map<String, Object> free = body(city.getId(), "BIKE");
        for (String f : List.of("baseFare", "perKm", "perMinute", "minimumFare")) {
            free.put(f, 0);
        }

        postJson(BASE + "/preview", superAdmin(), free).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("must charge something")));
    }

    @Test
    void aChangeScheduledWithinTheMaximumLeadIsAcceptedAndOneBeyondItIsRefused() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE); // default maximum lead: 90 days
        Map<String, Object> late = createBody(city.getId(), "BIKE", current.getId(), "too far");
        late.put("effectiveFrom", Instant.now().plus(90, ChronoUnit.DAYS).plus(5, ChronoUnit.MINUTES).toString());

        create(superAdmin(), late).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("90 days")));
        assertThat(ruleCount(city)).isEqualTo(1);

        Map<String, Object> inside = createBody(city.getId(), "BIKE", current.getId(), "in range");
        inside.put("effectiveFrom", Instant.now().plus(90, ChronoUnit.DAYS).minus(5, ChronoUnit.MINUTES).toString());
        create(superAdmin(), inside).andExpect(status().isCreated());
    }

    @Test
    void theMaximumLeadComesFromConfigurationAndIsShownToTheScreen() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        jdbc.update("UPDATE configuration SET value = '7' WHERE key = 'pricing.schedule.max.days' AND scope = 'GLOBAL'");
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "eight days");
        body.put("effectiveFrom", Instant.now().plus(8, ChronoUnit.DAYS).toString());

        create(superAdmin(), body).andExpect(status().isBadRequest());
        JsonNode view = json(list(superAdmin(), city.getId(), "BIKE").andExpect(status().isOk())).path("data");
        assertThat(view.path("limits").path("scheduleMaxDays").asInt()).isEqualTo(7);
    }

    @Test
    void theLimitsAreReadFromConfigurationSoTheyCanBeEditedLater() throws Exception {
        City city = newCity();
        jdbc.update("UPDATE configuration SET value = '20000' WHERE key = 'pricing.limit.base_fare.max.paise' AND scope = 'GLOBAL'");
        Map<String, Object> body = createBody(city.getId(), "BIKE", null, "first fare");
        body.put("baseFare", 20001);

        create(superAdmin(), body).andExpect(status().isBadRequest());
    }

    // ---- first fare, then change -----------------------------------------------------------------------------------

    @Test
    void theFirstFareIsCreatedNowAndIsInForce() throws Exception {
        City city = newCity();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        JsonNode created = json(create(superAdmin(), createBody(city.getId(), "BIKE", null, "first fare"))
                .andExpect(status().isCreated()))
                .path("data");

        assertThat(created.path("baseFare").asLong()).isEqualTo(5500);
        PricingRule row = pricingRules.findById(UUID.fromString(created.path("id").asText())).orElseThrow();
        assertThat(row.getEffectiveFrom()).isAfterOrEqualTo(before);
        assertThat(row.getEffectiveTo()).isNull();
        assertThat(row.getCurrency()).isEqualTo(city.getCurrency());
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, Instant.now().plusSeconds(1))).isPresent();
    }

    @Test
    void aFirstFareRequestThatClaimsAFareAlreadyExistsIsRefusedAsStale() throws Exception {
        City city = newCity();
        newPricingRule(city, VehicleType.BIKE);

        create(superAdmin(), createBody(city.getId(), "BIKE", null, "first fare")).andExpect(status().isConflict());
    }

    @Test
    void aChangeBasedOnAnOldVersionIsRefusedAsStale() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);

        create(superAdmin(), createBody(city.getId(), "BIKE", UUID.randomUUID(), "change")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE"));

        assertThat(pricingRules.findById(current.getId()).orElseThrow().getEffectiveTo()).isNull();
    }

    @Test
    void anOrdinaryChangeClosesTheOldRowExactlyWhereTheNewOneOpensWithNoGapAndNoOverlap() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);

        JsonNode created = json(create(superAdmin(), createBody(city.getId(), "BIKE", current.getId(), "petrol price rose"))
                .andExpect(status().isCreated())).path("data");

        flushAndClear();
        PricingRule old = pricingRules.findById(current.getId()).orElseThrow();
        PricingRule next = pricingRules.findById(UUID.fromString(created.path("id").asText())).orElseThrow();
        assertThat(old.getEffectiveTo()).isNotNull().isEqualTo(next.getEffectiveFrom());
        assertThat(next.getEffectiveTo()).isNull();
        assertThat(old.getBaseFare()).isEqualTo(5000); // the old row's money is untouched
        assertThat(next.getBaseFare()).isEqualTo(5500);
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, old.getEffectiveTo().minusNanos(1000)))
                .map(PricingRule::getId).contains(old.getId());
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, old.getEffectiveTo()))
                .map(PricingRule::getId).contains(next.getId());
    }

    @Test
    void aPastEffectiveFromIsRefused() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "backdated");
        body.put("effectiveFrom", Instant.now().minusSeconds(60).toString());

        create(superAdmin(), body).andExpect(status().isBadRequest());

        assertThat(pricingRules.findById(current.getId()).orElseThrow().getEffectiveTo()).isNull();
    }

    @Test
    void aFutureChangeIsScheduledAndTheCurrentRowStaysInForceUntilThen() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Instant when = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "weekend change");
        body.put("effectiveFrom", when.toString());

        JsonNode created = json(create(superAdmin(), body).andExpect(status().isCreated())).path("data");

        flushAndClear();
        assertThat(pricingRules.findById(current.getId()).orElseThrow().getEffectiveTo()).isEqualTo(when);
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, Instant.now())).map(PricingRule::getId)
                .contains(current.getId());
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, when)).map(PricingRule::getId)
                .contains(UUID.fromString(created.path("id").asText()));

        JsonNode view = json(list(superAdmin(), city.getId(), "BIKE").andExpect(status().isOk())).path("data");
        assertThat(view.path("current").path("id").asText()).isEqualTo(current.getId().toString());
        assertThat(view.path("scheduled").path("id").asText()).isEqualTo(created.path("id").asText());
    }

    @Test
    void aSecondPendingChangeIsRefused() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        PricingRule pending = scheduledAfter(city, current, 3600);
        Map<String, Object> second = createBody(city.getId(), "BIKE", pending.getId(), "another");
        second.put("effectiveFrom", Instant.now().plus(2, ChronoUnit.DAYS).toString());

        create(superAdmin(), second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRICING_RULE_CONFLICT"));

        assertThat(ruleCount(city)).isEqualTo(2);
    }

    @Test
    void anImmediateChangeWhileAChangeIsPendingIsAlsoRefused() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        PricingRule pending = scheduledAfter(city, current, 3600);

        create(superAdmin(), createBody(city.getId(), "BIKE", pending.getId(), "now")).andExpect(status().isConflict());
        create(superAdmin(), createBody(city.getId(), "BIKE", current.getId(), "now")).andExpect(status().isConflict());
    }

    @Test
    void anUnknownCityIsNotFoundAndAnUnknownVehicleTypeIsRefused() throws Exception {
        create(superAdmin(), createBody(UUID.randomUUID(), "BIKE", null, "x")).andExpect(status().isNotFound());
        City city = newCity();
        create(superAdmin(), createBody(city.getId(), "HOVERCRAFT", null, "x")).andExpect(status().isBadRequest());
    }

    // ---- large-change guard ----------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"baseFare,7501", "perKm,1801", "perMinute,301", "minimumFare,9001", "cancellationFee,7501", "serviceFeeFlat,1"})
    void moreThanFiftyPercentInAnyFieldNeedsTheConfirmFlag(String field, long newValue) throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE); // 5000/1200/200/6000/5000/5%/0 paise
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "big move");
        body.put(field, newValue);

        create(superAdmin(), body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("LARGE_CHANGE_NOT_CONFIRMED"));
        assertThat(ruleCount(city)).isEqualTo(1);

        body.put("confirmLargeChange", true);
        create(superAdmin(), body).andExpect(status().isCreated());
        assertThat(ruleCount(city)).isEqualTo(2);
    }

    @Test
    void exactlyFiftyPercentIsNotALargeChange() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "half");
        body.put("baseFare", 7500); // +50.00 percent exactly

        create(superAdmin(), body).andExpect(status().isCreated());
    }

    @Test
    void aServiceFeePercentMovingMoreThanFiftyPercentNeedsConfirmation() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE); // 5.00 percent
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "fee");
        body.put("serviceFeePct", new BigDecimal("7.51"));

        create(superAdmin(), body).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aDropToZeroIsALargeChange() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "free ride");
        body.put("perKm", 0);

        create(superAdmin(), body).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theFiftyPercentThresholdComesFromConfiguration() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        jdbc.update("UPDATE configuration SET value = '10' WHERE key = 'pricing.change.confirm.pct' AND scope = 'GLOBAL'");
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "twelve percent");
        body.put("baseFare", 5600);

        create(superAdmin(), body).andExpect(status().isUnprocessableEntity());
    }

    // ---- cancel ----------------------------------------------------------------------------------------------------

    @Test
    void cancellingAScheduledChangeRemovesItAndReopensThePreviousRow() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        PricingRule pending = scheduledAfter(city, current, 7200);

        postJson(BASE + "/" + pending.getId() + "/cancel", superAdmin(), Map.of("reason", "changed my mind"))
                .andExpect(status().isOk());

        flushAndClear();
        assertThat(pricingRules.findById(pending.getId())).isEmpty();
        assertThat(pricingRules.findById(current.getId()).orElseThrow().getEffectiveTo()).isNull();
        assertThat(pricingRules.findActive(city.getId(), VehicleType.BIKE, Instant.now().plusSeconds(86400)))
                .map(PricingRule::getId).contains(current.getId());
    }

    @Test
    void aScheduledFirstFareCanBeCancelledToo() throws Exception {
        City city = newCity();
        PricingRule pending = scheduled(city, 3600);

        postJson(BASE + "/" + pending.getId() + "/cancel", superAdmin(), Map.of("reason", "wrong city")).andExpect(status().isOk());

        flushAndClear();
        assertThat(ruleCount(city)).isZero();
    }

    @Test
    void aRuleAlreadyInForceCannotBeCancelled() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);

        postJson(BASE + "/" + current.getId() + "/cancel", superAdmin(), Map.of("reason", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PRICING_RULE_CONFLICT"));

        assertThat(pricingRules.findById(current.getId())).isPresent();
    }

    @Test
    void anEndedRuleCannotBeCancelled() throws Exception {
        City city = newCity();
        PricingRule old = newPricingRule(city, VehicleType.BIKE);
        old.setEffectiveTo(Instant.now().minusSeconds(60));
        pricingRules.saveAndFlush(old);

        postJson(BASE + "/" + old.getId() + "/cancel", superAdmin(), Map.of("reason", "x")).andExpect(status().isConflict());
    }

    @Test
    void aFutureRuleThatABookingReferencesCannotBeCancelled() throws Exception {
        City city = newCity();
        PricingRule pending = scheduled(city, 3600);
        var booking = newBooking(newCustomer(), city, com.ladygo.bookings.domain.BookingStatus.REQUESTED, null);
        booking.setPricingRuleId(pending.getId());
        bookings.saveAndFlush(booking);

        postJson(BASE + "/" + pending.getId() + "/cancel", superAdmin(), Map.of("reason", "x")).andExpect(status().isConflict());

        assertThat(pricingRules.findById(pending.getId())).isPresent();
    }

    @Test
    void cancellingAnUnknownRuleIsNotFound() throws Exception {
        postJson(BASE + "/" + UUID.randomUUID() + "/cancel", superAdmin(), Map.of("reason", "x")).andExpect(status().isNotFound());
    }

    // ---- audit -----------------------------------------------------------------------------------------------------

    @Test
    void createWritesAnAuditEntryWithActorIpOldAndNewValuesAndTheReason() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        var actor = newUser(UserRole.SUPER_ADMIN);
        String token = steppedUpSuperAdminToken(actor.getId());

        JsonNode created = json(create(token, createBody(city.getId(), "BIKE", current.getId(), "petrol price rose"))
                .andExpect(status().isCreated())).path("data");

        AuditLog log = auditLogs.findAll().stream().filter(a -> "PRICING_RULE_CREATED".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(log.getActorId()).isEqualTo(actor.getId());
        assertThat(log.getActorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(log.getTargetType()).isEqualTo("pricing_rule");
        assertThat(log.getTargetId()).isEqualTo(created.path("id").asText());
        assertThat(log.getIp()).isNotBlank();
        Map<String, Object> meta = log.getMetadata();
        assertThat(meta).containsEntry("reason", "petrol price rose").containsEntry("cityId", city.getId().toString())
                .containsEntry("vehicleType", "BIKE").containsEntry("previousRuleId", current.getId().toString());
        assertThat(asMap(meta.get("oldValues"))).containsEntry("baseFare", 5000);
        assertThat(asMap(meta.get("newValues"))).containsEntry("baseFare", 5500);
        assertThat(meta).containsKey("effectiveFrom");
    }

    @Test
    void aFirstFareAuditEntryHasNoOldValues() throws Exception {
        City city = newCity();

        create(superAdmin(), createBody(city.getId(), "BIKE", null, "first fare")).andExpect(status().isCreated());

        AuditLog log = auditLogs.findAll().stream().filter(a -> "PRICING_RULE_CREATED".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(log.getMetadata().get("oldValues")).isNull();
        assertThat(asMap(log.getMetadata().get("newValues"))).containsEntry("perKm", 1200);
    }

    @Test
    void aConfirmedLargeChangeIsFlaggedInTheAudit() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> body = createBody(city.getId(), "BIKE", current.getId(), "big");
        body.put("baseFare", 20000);
        body.put("confirmLargeChange", true);

        create(superAdmin(), body).andExpect(status().isCreated());

        AuditLog log = auditLogs.findAll().stream().filter(a -> "PRICING_RULE_CREATED".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(log.getMetadata()).containsEntry("largeChangeConfirmed", true);
    }

    @Test
    void cancelWritesAnAuditEntryWithTheCancelledValuesAndTheReason() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        PricingRule pending = scheduledAfter(city, current, 7200);

        postJson(BASE + "/" + pending.getId() + "/cancel", superAdmin(), Map.of("reason", "owner changed plan")).andExpect(status().isOk());

        AuditLog log = auditLogs.findAll().stream().filter(a -> "PRICING_RULE_CANCELLED".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(log.getTargetId()).isEqualTo(pending.getId().toString());
        assertThat(log.getMetadata()).containsEntry("reason", "owner changed plan");
        assertThat(asMap(log.getMetadata().get("oldValues"))).containsEntry("baseFare", 5500);
    }

    @Test
    void aRefusedWriteLeavesNoAuditEntry() throws Exception {
        City city = newCity();
        long before = auditLogs.findAll().stream().filter(a -> a.getAction().startsWith("PRICING_RULE")).count();

        create(superAdmin(), createBody(city.getId(), "BIKE", null, " ")).andExpect(status().isBadRequest());

        assertThat(auditLogs.findAll().stream().filter(a -> a.getAction().startsWith("PRICING_RULE")).count()).isEqualTo(before);
    }

    // ---- preview is the real calculator ----------------------------------------------------------------------------

    @Test
    void previewEqualsWhatTheRealCalculatorReturnsForBothCurrentAndProposed() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> body = body(city.getId(), "BIKE");
        body.put("baseFare", 6100);
        body.put("serviceFeePct", new BigDecimal("7.25"));
        body.put("serviceFeeFlat", 300);
        body.put("samples", List.of(Map.of("distanceMeters", 2000, "durationSeconds", 480),
                Map.of("distanceMeters", 5000, "durationSeconds", 900), Map.of("distanceMeters", 100, "durationSeconds", 10)));

        JsonNode data = json(postJson(BASE + "/preview", superAdmin(), body).andExpect(status().isOk())).path("data");

        PricingRule proposed = new PricingRule();
        proposed.setBaseFare(6100);
        proposed.setPerKm(1200);
        proposed.setPerMinute(200);
        proposed.setMinimumFare(6000);
        proposed.setCancellationFee(5000);
        proposed.setServiceFeePct(new BigDecimal("7.25"));
        proposed.setServiceFeeFlat(300);
        JsonNode samples = data.path("samples");
        assertThat(samples).hasSize(3);
        int[][] inputs = {{2000, 480}, {5000, 900}, {100, 10}};
        for (int i = 0; i < inputs.length; i++) {
            FareResult wantCurrent = fareCalculator.calculate(current, inputs[i][0], inputs[i][1]);
            FareResult wantProposed = fareCalculator.calculate(proposed, inputs[i][0], inputs[i][1]);
            assertThat(samples.get(i).path("current").path("totalFare").asLong()).isEqualTo(wantCurrent.totalFare());
            assertThat(samples.get(i).path("current").path("serviceFee").asLong()).isEqualTo(wantCurrent.breakdown().serviceFee());
            assertThat(samples.get(i).path("proposed").path("totalFare").asLong()).isEqualTo(wantProposed.totalFare());
            assertThat(samples.get(i).path("proposed").path("baseFare").asLong()).isEqualTo(wantProposed.breakdown().baseFare());
        }
    }

    @Test
    void previewUsesTheDefaultSamplesAndShowsNoCurrentSideBeforeAFirstFare() throws Exception {
        City city = newCity();

        JsonNode data = json(postJson(BASE + "/preview", superAdmin(), body(city.getId(), "BIKE")).andExpect(status().isOk())).path("data");

        assertThat(data.path("samples")).hasSize(3);
        assertThat(data.path("samples").get(0).path("distanceMeters").asInt()).isEqualTo(2000);
        assertThat(data.path("samples").get(0).path("current").isNull()).isTrue();
        assertThat(data.path("samples").get(0).path("proposed").path("totalFare").asLong()).isPositive();
        assertThat(data.path("largeChanges")).isEmpty();
    }

    @Test
    void previewReportsTheLargeChangedFieldsAndEnforcesTheSameLimitsAndChangesNothing() throws Exception {
        City city = newCity();
        PricingRule current = newPricingRule(city, VehicleType.BIKE);
        Map<String, Object> body = body(city.getId(), "BIKE");
        body.put("perKm", 3000);

        JsonNode data = json(postJson(BASE + "/preview", superAdmin(), body).andExpect(status().isOk())).path("data");

        assertThat(data.path("largeChanges")).hasSize(1);
        assertThat(data.path("largeChanges").get(0).path("field").asText()).isEqualTo("perKm");
        assertThat(data.path("largeChanges").get(0).path("oldValue").asText()).isEqualTo("1200");
        assertThat(data.path("largeChanges").get(0).path("newValue").asText()).isEqualTo("3000");

        body.put("perKm", 10001);
        postJson(BASE + "/preview", superAdmin(), body).andExpect(status().isBadRequest());
        assertThat(pricingRules.findById(current.getId()).orElseThrow().getEffectiveTo()).isNull();
        assertThat(ruleCount(city)).isEqualTo(1);
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Test
    void listShowsCurrentHistoryNewestFirstLimitsAndTheLatestRuleId() throws Exception {
        City city = newCity();
        PricingRule first = newPricingRule(city, VehicleType.BIKE);
        first.setEffectiveFrom(Instant.now().minus(10, ChronoUnit.DAYS));
        first.setEffectiveTo(Instant.now().minus(5, ChronoUnit.DAYS));
        pricingRules.saveAndFlush(first);
        PricingRule second = new PricingRule();
        second.setCityId(city.getId());
        second.setVehicleType(VehicleType.BIKE);
        second.setCurrency("INR");
        second.setBaseFare(5100);
        second.setPerKm(1200);
        second.setPerMinute(200);
        second.setMinimumFare(6000);
        second.setEffectiveFrom(first.getEffectiveTo());
        pricingRules.saveAndFlush(second);

        JsonNode data = json(list(superAdmin(), city.getId(), "BIKE").andExpect(status().isOk())).path("data");

        assertThat(data.path("current").path("id").asText()).isEqualTo(second.getId().toString());
        assertThat(data.path("scheduled").isNull()).isTrue();
        assertThat(data.path("latestRuleId").asText()).isEqualTo(second.getId().toString());
        assertThat(data.path("history")).hasSize(2);
        assertThat(data.path("history").get(0).path("id").asText()).isEqualTo(second.getId().toString());
        assertThat(data.path("history").get(0).path("status").asText()).isEqualTo("IN_FORCE");
        assertThat(data.path("history").get(1).path("status").asText()).isEqualTo("ENDED");
        assertThat(data.path("limits").path("baseFareMaxPaise").asLong()).isEqualTo(50000);
        assertThat(data.path("limits").path("serviceFeePctMax").asDouble()).isEqualTo(30.0);
        assertThat(data.path("largeChangePct").asInt()).isEqualTo(50);
    }

    @Test
    void listForACityWithNoFareHasNoCurrentAndNoLatestRule() throws Exception {
        City city = newCity();

        JsonNode data = json(list(superAdmin(), city.getId(), "BIKE").andExpect(status().isOk())).path("data");

        assertThat(data.path("current").isNull()).isTrue();
        assertThat(data.path("latestRuleId").isNull()).isTrue();
        assertThat(data.path("history")).isEmpty();
    }

    @Test
    void citiesShowTheServiceAreaFlagAndWhichVehicleTypesAlreadyHaveAFare() throws Exception {
        City withArea = newCityWithServiceArea(74.0, 34.0, 75.0, 34.5);
        newPricingRule(withArea, VehicleType.BIKE);
        newPricingRule(withArea, VehicleType.CAB);
        City bare = newCity();

        JsonNode data = json(mockMvcGet(BASE + "/cities", superAdmin()).andExpect(status().isOk())).path("data");

        JsonNode a = findById(data, withArea.getId());
        JsonNode b = findById(data, bare.getId());
        assertThat(a.path("hasServiceArea").asBoolean()).isTrue();
        assertThat(a.path("vehicleTypesWithFare")).extracting(JsonNode::asText).containsExactlyInAnyOrder("BIKE", "CAB");
        assertThat(b.path("hasServiceArea").asBoolean()).isFalse();
        assertThat(b.path("vehicleTypesWithFare")).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static JsonNode findById(JsonNode array, UUID id) {
        for (JsonNode n : array) {
            if (id.toString().equals(n.path("id").asText())) {
                return n;
            }
        }
        throw new AssertionError("city " + id + " not listed");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private long ruleCount(City city) {
        return pricingRules.findAll().stream().filter(r -> r.getCityId().equals(city.getId())).count();
    }

    /** A first fare that only starts in the future (no row before it). */
    private PricingRule scheduled(City city, long secondsAhead) {
        PricingRule rule = rule(city, 5500, Instant.now().plusSeconds(secondsAhead).truncatedTo(ChronoUnit.MICROS));
        return pricingRules.saveAndFlush(rule);
    }

    /** A pending change after {@code current}: closes current at the same instant, as the service does. */
    private PricingRule scheduledAfter(City city, PricingRule current, long secondsAhead) {
        Instant at = Instant.now().plusSeconds(secondsAhead).truncatedTo(ChronoUnit.MICROS);
        current.setEffectiveTo(at);
        pricingRules.saveAndFlush(current);
        return pricingRules.saveAndFlush(rule(city, 5500, at));
    }

    private PricingRule rule(City city, long baseFare, Instant from) {
        PricingRule rule = new PricingRule();
        rule.setCityId(city.getId());
        rule.setVehicleType(VehicleType.BIKE);
        rule.setCurrency("INR");
        rule.setBaseFare(baseFare);
        rule.setPerKm(1200);
        rule.setPerMinute(200);
        rule.setMinimumFare(6000);
        rule.setCancellationFee(5000);
        rule.setServiceFeePct(BigDecimal.valueOf(5));
        rule.setEffectiveFrom(from);
        return rule;
    }
}
