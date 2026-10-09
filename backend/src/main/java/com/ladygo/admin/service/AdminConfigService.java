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
 */
@Service
public class AdminConfigService {

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
    public AdminConfigEntryResponse update(UUID id, String newValue, UUID adminUserId, String adminRole, String ip) {
        ConfigEntry entry = entries.findById(id).orElseThrow(ResourceNotFoundException::new);
        validateAgainstType(entry.getValueType(), newValue);

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
        log.setMetadata(Map.of("key", saved.getConfigKey(), "oldValue", oldValue, "newValue", newValue));
        auditLogs.save(log);

        return toResponse(saved);
    }

    /** No switch-on-enum - ArchUnit bans {@code ordinal()} as a status/security-check foundation, and
     *  javac desugars an enum switch into exactly that (see {@code ArchitectureTest}). Plain
     *  {@code ==} comparisons instead, same as every other enum-dispatch in this codebase. */
    private static void validateAgainstType(ConfigValueType type, String value) {
        try {
            if (type == ConfigValueType.INTEGER) {
                Long.parseLong(value);
            } else if (type == ConfigValueType.DECIMAL) {
                new BigDecimal(value);
            } else if (type == ConfigValueType.BOOLEAN
                    && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new ValidationException("Value must be 'true' or 'false' for a BOOLEAN config entry.");
            }
            // STRING and JSON: no further validation - JSON structure isn't enforced here.
        } catch (NumberFormatException ex) {
            throw new ValidationException("Value is not a valid " + type + " for this config entry.");
        }
    }

    private static AdminConfigEntryResponse toResponse(ConfigEntry entry) {
        return new AdminConfigEntryResponse(
                entry.getId(), entry.getConfigKey(), entry.getConfigValue(), entry.getValueType(), entry.getScope(),
                entry.getCityId(), entry.getDescription(), entry.getUpdatedBy(), entry.getUpdatedAt());
    }
}
