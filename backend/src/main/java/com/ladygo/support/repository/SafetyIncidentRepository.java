package com.ladygo.support.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import com.ladygo.support.domain.IncidentSeverity;
import com.ladygo.support.domain.IncidentStatus;
import com.ladygo.support.domain.SafetyIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SafetyIncidentRepository extends JpaRepository<SafetyIncident, UUID> {

    /** Kept for the callers that already use it. Note this sorts severity as text; the admin queue uses {@link #queue}. */
    List<SafetyIncident> findByStatusInOrderBySeverityDescCreatedAtAsc(Collection<IncidentStatus> statuses);

    /** The admin queue: most severe first (CRITICAL, HIGH, MEDIUM, LOW), then oldest first. The sort is in the query on
     *  purpose: ordering the enum column directly would sort the names alphabetically. */
    @Query("""
            select i from SafetyIncident i where i.status in :statuses
            order by case i.severity
                when com.ladygo.support.domain.IncidentSeverity.CRITICAL then 0
                when com.ladygo.support.domain.IncidentSeverity.HIGH then 1
                when com.ladygo.support.domain.IncidentSeverity.MEDIUM then 2
                else 3 end, i.createdAt asc
            """)
    Page<SafetyIncident> queue(@Param("statuses") Collection<IncidentStatus> statuses, Pageable pageable);

    Page<SafetyIncident> findByStatusOrderByCreatedAtDesc(IncidentStatus status, Pageable pageable);

    long countByStatusIn(Collection<IncidentStatus> statuses);

    long countByStatus(IncidentStatus status);

    long countBySeverityAndStatusIn(IncidentSeverity severity, Collection<IncidentStatus> statuses);

    /** Atomic: only one of two simultaneous acknowledgements can win. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SafetyIncident i set i.status = com.ladygo.support.domain.IncidentStatus.ACKNOWLEDGED,
                i.acknowledgedBy = :by, i.acknowledgedAt = :now
            where i.id = :id and i.status = com.ladygo.support.domain.IncidentStatus.OPEN
            """)
    int acknowledgeIfOpen(@Param("id") UUID id, @Param("by") UUID by, @Param("now") Instant now);

    /** Atomic: an incident is resolved exactly once. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SafetyIncident i set i.status = com.ladygo.support.domain.IncidentStatus.RESOLVED,
                i.resolvedBy = :by, i.resolvedAt = :now
            where i.id = :id and i.status <> com.ladygo.support.domain.IncidentStatus.RESOLVED
            """)
    int resolveIfUnresolved(@Param("id") UUID id, @Param("by") UUID by, @Param("now") Instant now);
}
