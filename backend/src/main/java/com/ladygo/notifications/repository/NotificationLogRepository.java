package com.ladygo.notifications.repository;

import java.util.UUID;
import com.ladygo.notifications.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
}
