package com.ladygo.auth.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
