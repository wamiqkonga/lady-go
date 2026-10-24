package com.ladygo.verification.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** The versioned legal text shown and recorded verbatim at acceptance time (docs/api/driver-onboarding.md section 2). */
@Entity
@Table(name = "declaration_versions")
@Getter
@Setter
public class DeclarationVersion {

    @Id
    @Column(name = "version", length = 20, nullable = false)
    private String version;

    @Column(name = "text", columnDefinition = "text", nullable = false)
    private String text;

    @CreationTimestamp
    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;
}
