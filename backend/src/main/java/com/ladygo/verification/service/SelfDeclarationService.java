package com.ladygo.verification.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.verification.domain.DeclarationVersion;
import com.ladygo.verification.domain.SelfDeclaration;
import com.ladygo.verification.domain.SubjectType;
import com.ladygo.verification.repository.DeclarationVersionRepository;
import com.ladygo.verification.repository.SelfDeclarationRepository;
import com.ladygo.verification.web.dto.DeclarationResponse;
import com.ladygo.verification.web.dto.DeclarationStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** docs/api/driver-onboarding.md section 2 - shared by both rider and driver self-declarations. */
@Service
public class SelfDeclarationService {

    private final SelfDeclarationRepository declarations;
    private final DeclarationVersionRepository versions;

    public SelfDeclarationService(SelfDeclarationRepository declarations, DeclarationVersionRepository versions) {
        this.declarations = declarations;
        this.versions = versions;
    }

    @Transactional
    public DeclarationResponse accept(SubjectType subjectType, UUID subjectId, String requestedVersion) {
        DeclarationVersion current = currentVersion(subjectType);
        if (!current.getVersion().equals(requestedVersion)) {
            throw new ValidationException(
                    "declarationVersion must be the current published version (" + current.getVersion() + ").");
        }
        SelfDeclaration existing = declarations
                .findBySubjectTypeAndSubjectIdAndDeclarationVersion(subjectType, subjectId, requestedVersion)
                .orElse(null);
        if (existing != null) {
            return new DeclarationResponse(existing.getDeclarationVersion(), existing.getAcceptedAt());
        }

        SelfDeclaration declaration = new SelfDeclaration();
        declaration.setSubjectType(subjectType);
        declaration.setSubjectId(subjectId);
        declaration.setDeclarationVersion(requestedVersion);
        declaration.setAcceptedAt(Instant.now());
        SelfDeclaration saved = declarations.saveAndFlush(declaration);
        return new DeclarationResponse(saved.getDeclarationVersion(), saved.getAcceptedAt());
    }

    public boolean hasAcceptedCurrentVersion(SubjectType subjectType, UUID subjectId) {
        DeclarationVersion current = currentVersion(subjectType);
        return declarations
                .findBySubjectTypeAndSubjectIdAndDeclarationVersion(subjectType, subjectId, current.getVersion())
                .isPresent();
    }

    /** The current version's text (so a client can actually show it before asking for acceptance) plus
     *  whether this subject has already accepted it - previously nothing exposed either. */
    public DeclarationStatusResponse currentStatus(SubjectType subjectType, UUID subjectId) {
        DeclarationVersion current = currentVersion(subjectType);
        SelfDeclaration existing = declarations
                .findBySubjectTypeAndSubjectIdAndDeclarationVersion(subjectType, subjectId, current.getVersion())
                .orElse(null);
        return new DeclarationStatusResponse(
                current.getVersion(), current.getText(), existing != null,
                existing != null ? existing.getDeclarationVersion() : null,
                existing != null ? existing.getAcceptedAt() : null);
    }

    /** The newest version addressed to everyone or to this kind of subject (captains and riders have different wording). */
    private DeclarationVersion currentVersion(SubjectType subjectType) {
        return versions.findTopByAudienceInOrderByPublishedAtDesc(List.of("ALL", subjectType.name()))
                .orElseThrow(ResourceNotFoundException::new);
    }
}
