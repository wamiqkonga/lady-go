package com.ladygo.support.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.SafetyIncident;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SafetyIncidentRepository extends JpaRepository<SafetyIncident, UUID> {

    List<SafetyIncident> findByStatusInOrderBySeverityDescCreatedAtAsc(Collection<IncidentStatus> statuses);
}
