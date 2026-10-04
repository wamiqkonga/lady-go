package com.ladygo.drivers.repository;

import java.util.List;
import java.util.UUID;
import com.ladygo.drivers.domain.DocumentStatus;
import com.ladygo.drivers.domain.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, UUID> {

    List<DriverDocument> findByDriverId(UUID driverId);

    List<DriverDocument> findByStatus(DocumentStatus status);
}
