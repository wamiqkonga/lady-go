package com.ladygo.verification.repository;

import java.util.UUID;
import com.ladygo.verification.domain.EligibilityReport;
import com.ladygo.verification.domain.EligibilityReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EligibilityReportRepository extends JpaRepository<EligibilityReport, UUID> {

    Page<EligibilityReport> findByStatusOrderByCreatedAtAsc(EligibilityReportStatus status, Pageable pageable);
}
