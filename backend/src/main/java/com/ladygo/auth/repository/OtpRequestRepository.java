package com.ladygo.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.auth.domain.OtpPurpose;
import com.ladygo.auth.domain.OtpRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpRequestRepository extends JpaRepository<OtpRequest, UUID> {

    Optional<OtpRequest> findFirstByPhoneAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(String phone, OtpPurpose purpose);

    long countByPhoneAndCreatedAtAfter(String phone, Instant since);
}
