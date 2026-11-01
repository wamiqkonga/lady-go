package com.ladygo.auth.domain;

import java.time.Instant;
import java.util.UUID;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One emailed password-reset link. Only the SHA-256 of the token is stored (docs/security/admin-stepup-lockout-recovery-design.md section 4). */
@Entity
@Table(name = "admin_password_resets")
@Getter
@Setter
public class AdminPasswordReset extends CreatedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;
}
