package com.ladygo.users.domain;

import java.time.Instant;
import com.ladygo.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends AuditedEntity {

    @Column(name = "phone", length = 16, nullable = false)
    private String phone;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "name", length = 120)
    private String name;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "profile_photo_key", length = 500)
    private String profilePhotoKey;

    @Column(name = "mfa_secret_enc", columnDefinition = "text")
    private String mfaSecretEnc;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "mfa_pending_secret_enc", columnDefinition = "text")
    private String mfaPendingSecretEnc;

    @Column(name = "mfa_last_used_step")
    private Long mfaLastUsedStep;
}
