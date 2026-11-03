package com.ladygo.users.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
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

    List<User> findByRoleInOrderByCreatedAtAsc(Collection<UserRole> roles);

    Optional<User> findByIdAndRoleIn(UUID id, Collection<UserRole> roles);

    /** Row-locks every account holding {@code role} in {@code status} (stable order, so two writers cannot deadlock):
     *  the "last active SUPER_ADMIN" count and the change that follows it then cannot interleave with another one. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = :role AND u.status = :status ORDER BY u.id")
    List<User> lockByRoleAndStatus(@Param("role") UserRole role, @Param("status") UserStatus status);
    /**
     * Admin customer suspend/activate: a bounded transition of one CUSTOMER row, guarded on the status
     * the caller just saw so a concurrent change (or a DELETED account) affects 0 rows rather than being
     * overwritten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.status = :to, u.statusChangedAt = :now WHERE u.id = :id "
            + "AND u.role = com.ladygo.users.domain.UserRole.CUSTOMER AND u.status = :from")
    int transitionCustomerStatus(@Param("id") UUID id, @Param("from") UserStatus from, @Param("to") UserStatus to,
            @Param("now") java.time.Instant now);
}
