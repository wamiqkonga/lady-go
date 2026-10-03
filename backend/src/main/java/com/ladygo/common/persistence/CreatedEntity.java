package com.ladygo.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/** Immutable-after-creation rows (events, logs): id + created_at. */
@MappedSuperclass
@Getter
public abstract class CreatedEntity extends BaseEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
