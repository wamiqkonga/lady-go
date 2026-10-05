package com.ladygo.verification.repository;

import java.util.Optional;
import com.ladygo.verification.domain.DeclarationVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeclarationVersionRepository extends JpaRepository<DeclarationVersion, String> {

    Optional<DeclarationVersion> findTopByOrderByPublishedAtDesc();
}
