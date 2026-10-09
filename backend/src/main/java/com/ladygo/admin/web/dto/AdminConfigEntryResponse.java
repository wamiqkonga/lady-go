package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.domain.ConfigValueType;

/** docs/product/admin-web-design.md section 6. */
public record AdminConfigEntryResponse(
        UUID id,
        String configKey,
        String configValue,
        ConfigValueType valueType,
        ConfigScope scope,
        UUID cityId,
        String description,
        UUID updatedBy,
        Instant updatedAt) {
}
