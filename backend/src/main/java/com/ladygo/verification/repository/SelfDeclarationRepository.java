package com.ladygo.verification.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.verification.domain.SelfDeclaration;
import com.ladygo.verification.domain.SubjectType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfDeclarationRepository extends JpaRepository<SelfDeclaration, UUID> {

    Optional<SelfDeclaration> findBySubjectTypeAndSubjectIdAndDeclarationVersion(
            SubjectType subjectType, UUID subjectId, String declarationVersion);
}
