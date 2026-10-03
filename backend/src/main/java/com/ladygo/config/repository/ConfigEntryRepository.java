package com.ladygo.config.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.config.domain.ConfigEntry;
import com.ladygo.config.domain.ConfigScope;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigEntryRepository extends JpaRepository<ConfigEntry, UUID> {

    /** Global value for a key (city_id is null for GLOBAL scope). */
    Optional<ConfigEntry> findByConfigKeyAndScopeAndCityIdIsNull(String configKey, ConfigScope scope);

    Optional<ConfigEntry> findByConfigKeyAndScopeAndCityId(String configKey, ConfigScope scope, UUID cityId);
}
