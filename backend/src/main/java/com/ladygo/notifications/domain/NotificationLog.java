package com.ladygo.notifications.domain;

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
@Table(name = "notification_logs")
@Getter
@Setter
public class NotificationLog extends CreatedEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "event_type", length = 60, nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 10, nullable = false)
    private NotificationChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 15, nullable = false)
    private NotificationStatus status;

    @Column(name = "provider_ref", length = 150)
    private String providerRef;

    @Column(name = "error_code", length = 100)
    private String errorCode;
}
