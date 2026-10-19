package com.ladygo.auth.domain;

import java.time.Instant;
import com.ladygo.common.persistence.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "otp_requests")
@Getter
@Setter
public class OtpRequest extends CreatedEntity {

    @Column(name = "phone", length = 16, nullable = false)
    private String phone;

    /** Delivery address when the email channel is on (lower-cased); null for SMS requests. */
    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "code_hash", length = 255, nullable = false)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 20, nullable = false)
    private OtpPurpose purpose;

    @Column(name = "attempts", nullable = false)
    private short attempts = (short) 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
