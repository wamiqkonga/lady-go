package com.ladygo.admin.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminConfigEntryResponse;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.domain.ConfigEntry;
import com.ladygo.config.domain.ConfigValueType;
import com.ladygo.config.repository.ConfigEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/product/admin-web-design.md section 6. Immediate overwrite, no versioning/effective-dating
 * (open question 3, decided: acceptable cut for a single-founder-admin pilot - full audit trail of
 * old-&gt;new value substitutes for a rollback-by-version UI). SUPER_ADMIN-only on the write, per
 * instruction - read is open to any admin (ADMIN/SUPER_ADMIN), enforced at the controller.
 *
 * <p>Security-review finding (validator/consumer differential): the INTEGER check used to accept
 * anything {@code Long.parseLong} would take, but {@link com.ladygo.config.ConfigurationService#getInt}
 * - the actual reader for most of these keys - parses with {@code Integer.parseInt}. A value between
 * {@code Integer.MAX_VALUE} and {@code Long.MAX_VALUE} would pass this validator and then throw at
 * read time, in production, the next time that key was read. Narrowed to {@code Integer.parseInt} -
 * every consumer (including the ones that go on to build a {@code Duration} from a wider {@code long})
 * can represent anything that fits in an {@code int}, so this is the exact intersection of every actual
 * caller's real parser, not a new, independently-chosen rule.
 */
@Service
public class AdminConfigService {

    /** Safety-relevant keys only (location speed/age/rate, PIN attempts, cancel caps, radius, TTLs) -
     *  not an attempt to bound every config key, which would just be arbitrary line-drawing for keys
     *  with no safety story. A value outside these bounds cannot be saved even by SUPER_ADMIN; there is
     *  no bypass, because the risk (disabling a protection, or breaking a consumer) is the same
     *  regardless of who sets it. */
    private static final Map<String, long[]> INTEGER_BOUNDS = Map.ofEntries(
            Map.entry("driver.location.max_speed.kmh", new long[] {20, 300}),
            Map.entry("driver.location.max_age.seconds", new long[] {5, 300}),
            Map.entry("driver.location.max_future_skew.seconds", new long[] {1, 60}),
            Map.entry("ride.gate.max_location_age.seconds", new long[] {5, 300}),
            Map.entry("driver.location.rate.max.attempts", new long[] {1, 1000}),
            Map.entry("driver.location.rate.window.seconds", new long[] {1, 3600}),
            Map.entry("ride.pin.max.attempts", new long[] {1, 20}),
            Map.entry("ride.pin.driver.max.attempts", new long[] {1, 20}),
            Map.entry("ride.pin.lockout.window.minutes", new long[] {1, 1440}),
            Map.entry("ride.pin.driver.window.minutes", new long[] {1, 1440}),
            Map.entry("driver.eligibility_cancel.pair_cap.max", new long[] {1, 100}),
            Map.entry("driver.eligibility_cancel.weekly_cap.max", new long[] {1, 100}),
            Map.entry("rider.safety_cancel.pair_cap.max", new long[] {1, 100}),
            Map.entry("rider.safety_cancel.weekly_cap.max", new long[] {1, 100}),
            Map.entry("driver.search.radius.m", new long[] {100, 50_000}),
            Map.entry("matching.driver.location.max.age.seconds", new long[] {10, 3_600}),
            Map.entry("ride.gate.pickup_radius.m", new long[] {10, 5_000}),
            Map.entry("ride.max.distance.m", new long[] {1_000, 200_000}),
            Map.entry("booking.pickup.approximate.radius.m", new long[] {10, 5_000}),
            Map.entry("ride.gate.max_accuracy_credit.m", new long[] {0, 1_000}),
            Map.entry("trip_share.default_ttl.hours", new long[] {1, 168}),
            Map.entry("trip_share.post_ride_grace.minutes", new long[] {0, 1440}),
            Map.entry("trip_share.max_active_per_ride", new long[] {1, 20}),
            Map.entry("verification.document.view_url.ttl.seconds", new long[] {10, 600}),
            Map.entry("verification.rider_selfie.view_url.ttl.seconds", new long[] {10, 600}),
            Map.entry("access.token.ttl.minutes", new long[] {1, 60}),
            Map.entry("admin.access.token.ttl.minutes", new long[] {1, 60}),
            Map.entry("refresh.token.ttl.days", new long[] {1, 90}),
            Map.entry("otp.expiry.seconds", new long[] {30, 900}),
            Map.entry("otp.max.attempts", new long[] {1, 20}),
            Map.entry("admin.login.max.attempts", new long[] {1, 20}),
            Map.entry("sms.send.max_per_number.daily", new long[] {1, 1_000}),
            Map.entry("sms.send.max_per_ip.daily", new long[] {1, 5_000}),
            Map.entry("sms.send.max_global.daily", new long[] {1, 1_000_000}),
            Map.entry("earnings.commission.bps", new long[] {0, 10_000}),
            Map.entry("ratings.window.days", new long[] {1, 90}),
            Map.entry("ratings.submit.max_per_hour", new long[] {1, 1_000}));

    private static final Map<String, BigDecimal[]> DECIMAL_BOUNDS = Map.of(
            "booking.fare.change.tolerance.pct", new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("100")});

    private final ConfigEntryRepository entries;
    private final AuditLogRepository auditLogs;

    public AdminConfigService(ConfigEntryRepository entries, AuditLogRepository auditLogs) {
        this.entries = entries;
        this.auditLogs = auditLogs;
    }

    public List<AdminConfigEntryResponse> list() {
        return entries.findAll().stream().map(AdminConfigService::toResponse).toList();
    }

    @Transactional
    public AdminConfigEntryResponse update(
            UUID id, String newValue, String reason, UUID adminUserId, String adminRole, String ip) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("A reason is required to change a config value.");
        }
        ConfigEntry entry = entries.findById(id).orElseThrow(ResourceNotFoundException::new);
        validate(entry.getConfigKey(), entry.getValueType(), newValue);

        String oldValue = entry.getConfigValue();
        entry.setConfigValue(newValue);
        entry.setUpdatedBy(adminUserId);
        ConfigEntry saved = entries.save(entry);

        AuditLog log = new AuditLog();
        log.setActorId(adminUserId);
        log.setActorRole(adminRole);
        log.setAction("CONFIGURATION_CHANGED");
        log.setTargetType("configuration");
        log.setTargetId(saved.getId().toString());
        log.setIp(ip);
        log.setMetadata(Map.of(
                "key", saved.getConfigKey(), "oldValue", oldValue, "newValue", newValue, "reason", reason));
        auditLogs.save(log);

        return toResponse(saved);
    }

    /** No switch-on-enum - ArchUnit bans {@code ordinal()} as a status/security-check foundation, and
     *  javac desugars an enum switch into exactly that (see {@code ArchitectureTest}). Plain
     *  {@code ==} comparisons instead, same as every other enum-dispatch in this codebase. */
    private static void validate(String key, ConfigValueType type, String value) {
        try {
            if (type == ConfigValueType.INTEGER) {
                long parsed = Integer.parseInt(value);
                checkIntegerBounds(key, parsed);
            } else if (type == ConfigValueType.DECIMAL) {
                checkDecimalBounds(key, new BigDecimal(value));
            } else if (type == ConfigValueType.BOOLEAN
                    && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new ValidationException("Value must be 'true' or 'false' for a BOOLEAN config entry.");
            }
            // STRING and JSON: no further validation - JSON structure isn't enforced here.
        } catch (NumberFormatException ex) {
            throw new ValidationException("Value is not a valid " + type + " for this config entry.");
        }
    }

    private static void checkIntegerBounds(String key, long value) {
        long[] bounds = INTEGER_BOUNDS.get(key);
        if (bounds != null && (value < bounds[0] || value > bounds[1])) {
            throw new ValidationException(
                    "Value for " + key + " must be between " + bounds[0] + " and " + bounds[1] + ".");
        }
    }

    private static void checkDecimalBounds(String key, BigDecimal value) {
        BigDecimal[] bounds = DECIMAL_BOUNDS.get(key);
        if (bounds != null && (value.compareTo(bounds[0]) < 0 || value.compareTo(bounds[1]) > 0)) {
            throw new ValidationException(
                    "Value for " + key + " must be between " + bounds[0] + " and " + bounds[1] + ".");
        }
    }

    private static AdminConfigEntryResponse toResponse(ConfigEntry entry) {
        long[] intBounds = INTEGER_BOUNDS.get(entry.getConfigKey());
        BigDecimal[] decimalBounds = DECIMAL_BOUNDS.get(entry.getConfigKey());
        String minValue = intBounds != null ? String.valueOf(intBounds[0])
                : decimalBounds != null ? decimalBounds[0].toPlainString() : null;
        String maxValue = intBounds != null ? String.valueOf(intBounds[1])
                : decimalBounds != null ? decimalBounds[1].toPlainString() : null;
        return new AdminConfigEntryResponse(
                entry.getId(), entry.getConfigKey(), entry.getConfigValue(), entry.getValueType(), entry.getScope(),
                entry.getCityId(), entry.getDescription(), minValue, maxValue, entry.getUpdatedBy(),
                entry.getUpdatedAt());
    }
}
