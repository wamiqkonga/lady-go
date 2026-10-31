package com.ladygo.notifications.repository;

import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.domain.DeviceToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUserId(UUID userId);

    List<DeviceToken> findByUserIdAndApp(UUID userId, AppType app);

    List<DeviceToken> findByUserIdAndAppOrderByLastSeenAtDesc(UUID userId, AppType app);

    Optional<DeviceToken> findByToken(String token);
}
