package com.ladygo.verification.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One acceptance record per subject per declaration version - riders and drivers both use this (docs/api/driver-onboarding.md section 2). */
@Entity
@Table(name = "self_declarations")
@Getter
@Setter
public class SelfDeclaration extends CreatedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 10, nullable = false)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "declaration_version", length = 20, nullable = false)
    private String declarationVersion;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt;
}
