package com.ladygo.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.UpdateTimestamp;

/** Mutable rows: id + created_at + updated_at. */
@MappedSuperclass
@Getter
public abstract class AuditedEntity extends CreatedEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
