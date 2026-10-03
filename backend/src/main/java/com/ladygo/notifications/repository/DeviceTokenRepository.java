package com.ladygo.notifications.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.notifications.domain.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUserId(UUID userId);

    Optional<DeviceToken> findByToken(String token);
}
