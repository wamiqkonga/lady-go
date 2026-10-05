package com.ladygo.drivers.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DocumentType;
import com.ladygo.drivers.domain.DriverDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, UUID> {

    List<DriverDocument> findByDriverId(UUID driverId);

    List<DriverDocument> findByStatus(DocumentStatus status);

    Page<DriverDocument> findByStatusOrderByCreatedAtAsc(DocumentStatus status, Pageable pageable);

    /** Most recent upload of a given type for a driver - a resubmission supersedes an earlier one. */
    Optional<DriverDocument> findFirstByDriverIdAndTypeOrderByCreatedAtDesc(UUID driverId, DocumentType type);
}
