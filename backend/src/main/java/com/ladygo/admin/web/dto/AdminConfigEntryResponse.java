package com.ladygo.admin.web.dto;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.domain.ConfigValueType;

/** docs/product/admin-web-design.md section 6. {@code minValue}/{@code maxValue} are null for a key
 *  with no server-enforced bound (most keys - AdminConfigService.INTEGER_BOUNDS/DECIMAL_BOUNDS cover
 *  safety-relevant keys only), never a stand-in for "unbounded is fine to guess at" - the UI shows
 *  them as text exactly as returned, same as every other value here. */
public record AdminConfigEntryResponse(
        UUID id,
        String configKey,
        String configValue,
        ConfigValueType valueType,
        ConfigScope scope,
        UUID cityId,
        String description,
        String minValue,
        String maxValue,
        UUID updatedBy,
        Instant updatedAt) {
}
