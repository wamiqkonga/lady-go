package com.ladygo.admin.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.PricingCityResponse;
import com.ladygo.admin.web.dto.PricingPreviewResponse;
import com.ladygo.admin.web.dto.PricingPreviewResponse.FareQuote;
import com.ladygo.admin.web.dto.PricingPreviewResponse.LargeChange;
import com.ladygo.admin.web.dto.PricingRuleCreateRequest;
import com.ladygo.admin.web.dto.PricingRuleLimitsResponse;
import com.ladygo.admin.web.dto.PricingRulePreviewRequest;
import com.ladygo.admin.web.dto.PricingRuleResponse;
import com.ladygo.admin.web.dto.PricingRulesViewResponse;
import com.ladygo.admin.web.dto.PricingSampleRequest;
import com.ladygo.bookings.repository.BookingRepository;
import com.ladygo.common.exception.ErrorCode;
import com.ladygo.common.exception.LargeChangeNotConfirmedException;
import com.ladygo.common.exception.PricingRuleConflictException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.locations.domain.City;
import com.ladygo.locations.repository.CityRepository;
import com.ladygo.pricing.domain.PricingRule;
import com.ladygo.pricing.repository.PricingRuleRepository;
import com.ladygo.pricing.service.FareCalculator;
import com.ladygo.pricing.service.FareResult;
import com.ladygo.vehicles.domain.VehicleType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Set and change fares from the portal (docs/development/plans/2026-11-03-admin-pricing-rules.md). MONEY feature.
 *
 * <p>Versioned, never edited: a change closes the version in force at the instant the new one opens and inserts the
 * new one, in one transaction that first locks the city row (serialises every writer for that city) and then the
 * latest version. The database exclusion constraint {@code ex_pricing_rules_no_overlap} is the backstop. The only
 * UPDATE is closing/re-opening {@code effective_to}; the only DELETE removes one not-yet-effective, unreferenced
 * version by its own id. A booking keeps {@code pricing_rule_id}, so a change never alters a booking already made.
 */
@Service
public class AdminPricingRuleService {

    private static final String AUDIT_TARGET = "pricing_rule";
    private static final List<PricingSampleRequest> DEFAULT_SAMPLES = List.of(
            new PricingSampleRequest(2_000, 8 * 60), new PricingSampleRequest(5_000, 15 * 60),
            new PricingSampleRequest(10_000, 30 * 60));

    // Safety nets only; the seeded configuration rows (V45) are the source of truth.
    private static final int DEFAULT_BASE_FARE_MAX = 50_000;
    private static final int DEFAULT_PER_KM_MAX = 10_000;
    private static final int DEFAULT_PER_MINUTE_MAX = 2_000;
    private static final int DEFAULT_MINIMUM_FARE_MAX = 100_000;
    private static final int DEFAULT_CANCELLATION_FEE_MAX = 50_000;
    private static final int DEFAULT_SERVICE_FEE_FLAT_MAX = 10_000;
    private static final BigDecimal DEFAULT_SERVICE_FEE_PCT_MAX = new BigDecimal("30");
    private static final int DEFAULT_LARGE_CHANGE_PCT = 50;

    private final PricingRuleRepository rules;
    private final CityRepository cities;
    private final BookingRepository bookings;
    private final AuditLogRepository auditLogs;
    private final FareCalculator fareCalculator;
    private final ConfigurationService config;
    private final ObjectMapper objectMapper;

    public AdminPricingRuleService(
            PricingRuleRepository rules, CityRepository cities, BookingRepository bookings,
            AuditLogRepository auditLogs, FareCalculator fareCalculator, ConfigurationService config,
            ObjectMapper objectMapper) {
        this.rules = rules;
        this.cities = cities;
        this.bookings = bookings;
        this.auditLogs = auditLogs;
        this.fareCalculator = fareCalculator;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** The proposed money of a version, validated in one place for preview and create. */
    private record Values(long baseFare, long perKm, long perMinute, long minimumFare, long cancellationFee,
                          BigDecimal serviceFeePct, long serviceFeeFlat) {

        static Values of(PricingRule rule) {
            return new Values(rule.getBaseFare(), rule.getPerKm(), rule.getPerMinute(), rule.getMinimumFare(),
                    rule.getCancellationFee(), rule.getServiceFeePct(), rule.getServiceFeeFlat());
        }

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("baseFare", baseFare);
            map.put("perKm", perKm);
            map.put("perMinute", perMinute);
            map.put("minimumFare", minimumFare);
            map.put("cancellationFee", cancellationFee);
            map.put("serviceFeePct", serviceFeePct.setScale(2).toPlainString());
            map.put("serviceFeeFlat", serviceFeeFlat);
            return map;
        }
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PricingRulesViewResponse view(UUID cityId, VehicleType vehicleType) {
        if (!cities.existsById(cityId)) {
            throw new ResourceNotFoundException();
        }
        Instant now = Instant.now();
        List<PricingRule> history = rules.findByCityIdAndVehicleTypeOrderByEffectiveFromDesc(cityId, vehicleType);
        PricingRuleResponse current = null;
        PricingRuleResponse scheduled = null;
        List<PricingRuleResponse> rows = new ArrayList<>();
        for (PricingRule rule : history) {
            PricingRuleResponse row = toResponse(rule, now);
            rows.add(row);
            if (row.status() == PricingRuleResponse.Status.IN_FORCE && current == null) {
                current = row;
            } else if (row.status() == PricingRuleResponse.Status.SCHEDULED) {
                scheduled = row;
            }
        }
        UUID latest = history.isEmpty() ? null : history.get(0).getId();
        return new PricingRulesViewResponse(cityId, vehicleType, current, scheduled, latest, rows, limits(),
                largeChangePct());
    }

    @Transactional(readOnly = true)
    public List<PricingCityResponse> cities() {
        Map<UUID, List<VehicleType>> withFare = new LinkedHashMap<>();
        for (Object[] pair : rules.findCityVehicleTypePairs()) {
            withFare.computeIfAbsent((UUID) pair[0], k -> new ArrayList<>()).add((VehicleType) pair[1]);
        }
        return cities.findAll().stream()
                .sorted(Comparator.comparing(City::getName))
                .map(city -> new PricingCityResponse(city.getId(), city.getName(), city.isActive(),
                        city.getServiceArea() != null,
                        withFare.getOrDefault(city.getId(), List.of()).stream().sorted().toList()))
                .toList();
    }

    /** Sample fares for the proposed values, priced by the same {@link FareCalculator} riders' estimates use. */
    @Transactional(readOnly = true)
    public PricingPreviewResponse preview(PricingRulePreviewRequest request) {
        if (!cities.existsById(request.cityId())) {
            throw new ResourceNotFoundException();
        }
        Values proposed = validated(request.baseFare(), request.perKm(), request.perMinute(), request.minimumFare(),
                request.cancellationFee(), request.serviceFeePct(), request.serviceFeeFlat());
        PricingRule current = rules.findActive(request.cityId(), request.vehicleType(), Instant.now()).orElse(null);
        PricingRule candidate = transientRule(request.cityId(), request.vehicleType(), "INR", proposed);

        List<PricingSampleRequest> samples =
                request.samples() == null || request.samples().isEmpty() ? DEFAULT_SAMPLES : request.samples();
        List<PricingPreviewResponse.Sample> priced = samples.stream()
                .map(s -> new PricingPreviewResponse.Sample(s.distanceMeters(), s.durationSeconds(),
                        current == null ? null : quote(current, s), quote(candidate, s)))
                .toList();
        List<LargeChange> large = current == null ? List.of() : largeChanges(Values.of(current), proposed);
        return new PricingPreviewResponse(priced, large);
    }

    // ---- writes ----------------------------------------------------------------------------------------------------

    @Transactional
    public PricingRuleResponse create(
            PricingRuleCreateRequest request, UUID actorId, String actorRole, String ip) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ValidationException("A reason is required to change a fare.");
        }
        Values proposed = validated(request.baseFare(), request.perKm(), request.perMinute(), request.minimumFare(),
                request.cancellationFee(), request.serviceFeePct(), request.serviceFeeFlat());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant from = request.effectiveFrom() == null ? now : request.effectiveFrom().truncatedTo(ChronoUnit.MICROS);
        if (from.isBefore(now)) {
            throw new ValidationException("A fare cannot start in the past.");
        }

        City city = cities.findByIdForUpdate(request.cityId()).orElseThrow(ResourceNotFoundException::new);
        PricingRule latest = rules.lockLatest(city.getId(), request.vehicleType(), Pageable.ofSize(1))
                .stream().findFirst().orElse(null);

        if (latest != null && latest.getEffectiveFrom().isAfter(now)) {
            throw new PricingRuleConflictException(
                    "A fare change is already scheduled. Cancel it first, then make this change.");
        }
        UUID latestId = latest == null ? null : latest.getId();
        if (!java.util.Objects.equals(latestId, request.expectedLatestRuleId())) {
            throw new StaleFareException();
        }

        Values previous = null;
        UUID previousId = null;
        if (latest != null) {
            previous = Values.of(latest);
            previousId = latest.getId();
            if (!from.isAfter(latest.getEffectiveFrom())) {
                throw new ValidationException("The new fare must start after the current fare started.");
            }
            if (latest.getEffectiveTo() != null && from.isBefore(latest.getEffectiveTo())) {
                throw new PricingRuleConflictException("The new fare would overlap the previous fare.");
            }
            List<LargeChange> large = largeChanges(previous, proposed);
            if (!large.isEmpty() && !request.confirmLargeChange()) {
                throw new LargeChangeNotConfirmedException(
                        "This changes " + describe(large) + " by more than " + largeChangePct()
                                + " percent. Confirm the large change to continue.");
            }
            if (latest.getEffectiveTo() == null && rules.closeOpenRule(latest.getId(), from) != 1) {
                throw new PricingRuleConflictException("The current fare changed while saving. Reload and try again.");
            }
        }

        PricingRule created = transientRule(city.getId(), request.vehicleType(), city.getCurrency(), proposed);
        created.setEffectiveFrom(from);
        created.setCreatedBy(actorId);
        try {
            created = rules.saveAndFlush(created);
        } catch (DataIntegrityViolationException ex) {
            throw new PricingRuleConflictException("This fare overlaps another fare for the same city and vehicle type.");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", request.reason());
        metadata.put("cityId", city.getId().toString());
        metadata.put("vehicleType", request.vehicleType().name());
        metadata.put("previousRuleId", previousId == null ? null : previousId.toString());
        metadata.put("oldValues", previous == null ? null : previous.asMap());
        metadata.put("newValues", proposed.asMap());
        metadata.put("effectiveFrom", from.toString());
        metadata.put("largeChangeConfirmed", request.confirmLargeChange());
        audit("PRICING_RULE_CREATED", created.getId(), metadata, actorId, actorRole, ip);
        return toResponse(created, now);
    }

    @Transactional
    public PricingRuleResponse cancel(UUID ruleId, String reason, UUID actorId, String actorRole, String ip) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("A reason is required to cancel a scheduled fare.");
        }
        PricingRule peek = rules.findById(ruleId).orElseThrow(ResourceNotFoundException::new);
        cities.findByIdForUpdate(peek.getCityId()).orElseThrow(ResourceNotFoundException::new);
        PricingRule rule = rules.findByIdForUpdate(ruleId).orElseThrow(ResourceNotFoundException::new);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!rule.getEffectiveFrom().isAfter(now)) {
            throw new PricingRuleConflictException("Only a fare that has not started yet can be cancelled.");
        }
        if (bookings.existsByPricingRuleId(ruleId)) {
            throw new PricingRuleConflictException("A booking already uses this fare, so it cannot be cancelled.");
        }
        PricingRuleResponse cancelled = toResponse(rule, now);
        Values old = Values.of(rule);
        Instant startedAt = rule.getEffectiveFrom();
        Optional<PricingRule> predecessor = rules.findByCityIdAndVehicleTypeAndEffectiveTo(
                rule.getCityId(), rule.getVehicleType(), startedAt);
        UUID predecessorId = predecessor.map(PricingRule::getId).orElse(null);
        UUID cityId = rule.getCityId();
        VehicleType vehicleType = rule.getVehicleType();

        if (rules.deleteFutureRule(ruleId, now) != 1) {
            throw new PricingRuleConflictException("The fare changed while cancelling. Reload and try again.");
        }
        if (predecessorId != null && rules.reopenRule(predecessorId, startedAt) != 1) {
            throw new PricingRuleConflictException("The previous fare changed while cancelling. Reload and try again.");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason);
        metadata.put("cityId", cityId.toString());
        metadata.put("vehicleType", vehicleType.name());
        metadata.put("previousRuleId", predecessorId == null ? null : predecessorId.toString());
        metadata.put("oldValues", old.asMap());
        metadata.put("newValues", null);
        metadata.put("effectiveFrom", startedAt.toString());
        audit("PRICING_RULE_CANCELLED", ruleId, metadata, actorId, actorRole, ip);
        return cancelled;
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    /** A stale expectation is the existing "someone else just updated this" error, not a new kind. */
    private static final class StaleFareException extends com.ladygo.common.exception.ApiException {
        StaleFareException() {
            super(ErrorCode.CONCURRENT_UPDATE, "The fare was changed by someone else. Reload and review it before changing it.");
        }
    }

    private void audit(String action, UUID targetId, Map<String, Object> metadata, UUID actorId, String role, String ip) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setActorRole(role);
        log.setAction(action);
        log.setTargetType(AUDIT_TARGET);
        log.setTargetId(targetId.toString());
        log.setIp(ip);
        // Null entries are left out and numbers round-tripped through JSON, so the in-memory map equals what Hibernate's
        // JSON dirty check re-reads. Otherwise the entry looks "changed" and a later flush issues an UPDATE, which the
        // append-only audit table refuses (500 on an otherwise successful fare change).
        Map<String, Object> present = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (value != null) {
                present.put(key, value);
            }
        });
        try {
            // a real text round trip: convertValue would keep a Long as a Long
            @SuppressWarnings("unchecked")
            Map<String, Object> stable = objectMapper.readValue(objectMapper.writeValueAsString(present), Map.class);
            log.setMetadata(stable);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Audit metadata is not serialisable", ex);
        }
        auditLogs.save(log);
    }

    private Values validated(long baseFare, long perKm, long perMinute, long minimumFare, long cancellationFee,
                             BigDecimal serviceFeePct, long serviceFeeFlat) {
        PricingRuleLimitsResponse limits = limits();
        checkMoney("Base fare", baseFare, limits.baseFareMaxPaise());
        checkMoney("Per-kilometre rate", perKm, limits.perKmMaxPaise());
        checkMoney("Per-minute rate", perMinute, limits.perMinuteMaxPaise());
        checkMoney("Minimum fare", minimumFare, limits.minimumFareMaxPaise());
        checkMoney("Cancellation fee", cancellationFee, limits.cancellationFeeMaxPaise());
        checkMoney("Flat service fee", serviceFeeFlat, limits.serviceFeeFlatMaxPaise());
        if (serviceFeePct.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("The service fee percentage can have at most two decimals.");
        }
        if (serviceFeePct.signum() < 0 || serviceFeePct.compareTo(limits.serviceFeePctMax()) > 0) {
            throw new ValidationException("The service fee percentage must be between 0 and " + limits.serviceFeePctMax().toPlainString() + ".");
        }
        return new Values(baseFare, perKm, perMinute, minimumFare, cancellationFee,
                serviceFeePct.setScale(2), serviceFeeFlat);
    }

    private static void checkMoney(String label, long paise, long maxPaise) {
        if (paise < 0 || paise > maxPaise) {
            throw new ValidationException(label + " must be between 0 and " + maxPaise + " paise.");
        }
    }

    private PricingRuleLimitsResponse limits() {
        return new PricingRuleLimitsResponse(
                config.getInt("pricing.limit.base_fare.max.paise", DEFAULT_BASE_FARE_MAX),
                config.getInt("pricing.limit.per_km.max.paise", DEFAULT_PER_KM_MAX),
                config.getInt("pricing.limit.per_minute.max.paise", DEFAULT_PER_MINUTE_MAX),
                config.getInt("pricing.limit.minimum_fare.max.paise", DEFAULT_MINIMUM_FARE_MAX),
                config.getInt("pricing.limit.cancellation_fee.max.paise", DEFAULT_CANCELLATION_FEE_MAX),
                config.getInt("pricing.limit.service_fee_flat.max.paise", DEFAULT_SERVICE_FEE_FLAT_MAX),
                config.getBigDecimal("pricing.limit.service_fee_pct.max", DEFAULT_SERVICE_FEE_PCT_MAX));
    }

    private int largeChangePct() {
        return config.getInt("pricing.change.confirm.pct", DEFAULT_LARGE_CHANGE_PCT);
    }

    private List<LargeChange> largeChanges(Values old, Values proposed) {
        BigDecimal threshold = BigDecimal.valueOf(largeChangePct());
        List<LargeChange> out = new ArrayList<>();
        check(out, threshold, "baseFare", BigDecimal.valueOf(old.baseFare()), BigDecimal.valueOf(proposed.baseFare()));
        check(out, threshold, "perKm", BigDecimal.valueOf(old.perKm()), BigDecimal.valueOf(proposed.perKm()));
        check(out, threshold, "perMinute", BigDecimal.valueOf(old.perMinute()), BigDecimal.valueOf(proposed.perMinute()));
        check(out, threshold, "minimumFare", BigDecimal.valueOf(old.minimumFare()), BigDecimal.valueOf(proposed.minimumFare()));
        check(out, threshold, "cancellationFee", BigDecimal.valueOf(old.cancellationFee()), BigDecimal.valueOf(proposed.cancellationFee()));
        check(out, threshold, "serviceFeePct", old.serviceFeePct(), proposed.serviceFeePct());
        check(out, threshold, "serviceFeeFlat", BigDecimal.valueOf(old.serviceFeeFlat()), BigDecimal.valueOf(proposed.serviceFeeFlat()));
        return out;
    }

    /** Large when the move is more than {@code threshold} percent of the old value; from zero, any non-zero value is large. */
    private static void check(List<LargeChange> out, BigDecimal threshold, String field, BigDecimal old, BigDecimal proposed) {
        boolean large;
        if (old.signum() == 0) {
            large = proposed.signum() != 0;
        } else {
            large = proposed.subtract(old).abs().multiply(BigDecimal.valueOf(100)).compareTo(old.multiply(threshold)) > 0;
        }
        if (large) {
            out.add(new LargeChange(field, old.stripTrailingZeros().toPlainString(), proposed.stripTrailingZeros().toPlainString()));
        }
    }

    private static String describe(List<LargeChange> changes) {
        return String.join(", ", changes.stream().map(LargeChange::field).toList());
    }

    private PricingRule transientRule(UUID cityId, VehicleType vehicleType, String currency, Values values) {
        PricingRule rule = new PricingRule();
        rule.setCityId(cityId);
        rule.setVehicleType(vehicleType);
        rule.setCurrency(currency);
        rule.setBaseFare(values.baseFare());
        rule.setPerKm(values.perKm());
        rule.setPerMinute(values.perMinute());
        rule.setMinimumFare(values.minimumFare());
        rule.setCancellationFee(values.cancellationFee());
        rule.setServiceFeePct(values.serviceFeePct());
        rule.setServiceFeeFlat(values.serviceFeeFlat());
        return rule;
    }

    private FareQuote quote(PricingRule rule, PricingSampleRequest sample) {
        FareResult fare = fareCalculator.calculate(rule, sample.distanceMeters(), sample.durationSeconds());
        return new FareQuote(fare.totalFare(), fare.breakdown().baseFare(), fare.breakdown().distanceFare(),
                fare.breakdown().timeFare(), fare.breakdown().serviceFee());
    }

    private PricingRuleResponse toResponse(PricingRule rule, Instant now) {
        PricingRuleResponse.Status status;
        if (rule.getEffectiveFrom().isAfter(now)) {
            status = PricingRuleResponse.Status.SCHEDULED;
        } else if (rule.getEffectiveTo() != null && !rule.getEffectiveTo().isAfter(now)) {
            status = PricingRuleResponse.Status.ENDED;
        } else {
            status = PricingRuleResponse.Status.IN_FORCE;
        }
        boolean cancellable = status == PricingRuleResponse.Status.SCHEDULED && !bookings.existsByPricingRuleId(rule.getId());
        return new PricingRuleResponse(
                rule.getId(), rule.getCityId(), rule.getVehicleType(), rule.getCurrency(),
                rule.getBaseFare(), rule.getPerKm(), rule.getPerMinute(), rule.getMinimumFare(),
                rule.getCancellationFee(), rule.getServiceFeePct(), rule.getServiceFeeFlat(),
                rule.getEffectiveFrom(), rule.getEffectiveTo(), rule.getCreatedBy(), rule.getCreatedAt(),
                status, cancellable);
    }
}
