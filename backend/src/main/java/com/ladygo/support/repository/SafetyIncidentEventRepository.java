package com.ladygo.support.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.support.domain.SafetyIncidentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyIncidentEventRepository extends JpaRepository<SafetyIncidentEvent, UUID> {

    List<SafetyIncidentEvent> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);
}
