package com.ladygo.notifications.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "device_tokens")
@Getter
@Setter
public class DeviceToken extends CreatedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 10, nullable = false)
    private DevicePlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(name = "app", length = 10, nullable = false)
    private AppType app;

    @Column(name = "token", length = 500, nullable = false)
    private String token;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();
}
