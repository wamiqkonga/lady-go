package com.ladygo.users.repository;

import java.util.Optional;
import java.util.UUID;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPhone(String phone);

    boolean existsByPhone(String phone);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByRole(UserRole role);

    /** scripts/ci/e2e-seed.sh's throwaway staff accounts all use "@ladygo.test" exclusively - see
     *  E2eArtifactsStartupGuard, which refuses to start under staging/prod if this is ever true. */
    boolean existsByEmailEndingWithIgnoreCase(String suffix);

    /**
     * TOTP replay rejection (docs/security/auth-design.md section 3.4): atomically accepts a code's
     * step only if it is strictly newer than the last accepted step. 0 rows affected means replay.
     * clearAutomatically: a bulk update bypasses the persistence context, so without this, a User
     * already loaded earlier in the same transaction would keep a stale in-memory mfaLastUsedStep
     * even though the DB row changed (classic Hibernate bulk-update-vs-session-cache pitfall).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.mfaLastUsedStep = :step WHERE u.id = :id "
            + "AND (u.mfaLastUsedStep IS NULL OR u.mfaLastUsedStep < :step)")
    int updateMfaLastUsedStepIfNewer(@Param("id") UUID id, @Param("step") long step);
}
