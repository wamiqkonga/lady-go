package com.ladygo.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import com.ladygo.config.domain.ConfigScope;
import com.ladygo.config.repository.ConfigEntryRepository;
import org.springframework.stereotype.Service;

/**
 * Typed access to the {@code configuration} table (GLOBAL scope only, for now - city-scoped overrides
 * are not needed by anything in Phase 3). Per docs/architecture/backend-architecture.md section 1,
 * business values are read through here rather than hard-coded; the literal passed as {@code fallback}
 * is only a safety net for an environment where the row hasn't been seeded (seeding happens in V12 for
 * every key Phase 3 uses), not a hidden second source of truth.
 */
@Service
public class ConfigurationService {

    private final ConfigEntryRepository repository;

    public ConfigurationService(ConfigEntryRepository repository) {
        this.repository = repository;
    }

    public int getInt(String key, int fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> Integer.parseInt(entry.getConfigValue()))
                .orElse(fallback);
    }

    /**
     * For keys that are deliberately UNSET until the owner provides a value (no safe default exists, e.g.
     * {@code earnings.commission.bps}): empty when the row is absent OR its value is blank, so "unset"
     * never turns into an invented number. A non-numeric value is also treated as unset rather than
     * throwing, because the caller sits inside a ride-completion transaction that must not fail on a
     * config typo (AdminConfigService already rejects non-integers on write).
     */
    public Optional<Integer> findInt(String key) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> entry.getConfigValue() == null ? "" : entry.getConfigValue().trim())
                .filter(value -> !value.isEmpty())
                .flatMap(value -> {
                    try {
                        return Optional.of(Integer.parseInt(value));
                    } catch (NumberFormatException ex) {
                        return Optional.empty();
                    }
                });
    }

    public boolean getBoolean(String key, boolean fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> Boolean.parseBoolean(entry.getConfigValue()))
                .orElse(fallback);
    }

    public Duration getSeconds(String key, Duration fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> Duration.ofSeconds(Long.parseLong(entry.getConfigValue())))
                .orElse(fallback);
    }

    public Duration getHours(String key, Duration fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> Duration.ofHours(Long.parseLong(entry.getConfigValue())))
                .orElse(fallback);
    }

    public Duration getMinutes(String key, Duration fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> Duration.ofMinutes(Long.parseLong(entry.getConfigValue())))
                .orElse(fallback);
    }

    public BigDecimal getBigDecimal(String key, BigDecimal fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> new BigDecimal(entry.getConfigValue()))
                .orElse(fallback);
    }

    public Duration getDays(String key, Duration fallback) {
        return repository.findByConfigKeyAndScopeAndCityIdIsNull(key, ConfigScope.GLOBAL)
                .map(entry -> Duration.ofDays(Long.parseLong(entry.getConfigValue())))
                .orElse(fallback);
    }
}
