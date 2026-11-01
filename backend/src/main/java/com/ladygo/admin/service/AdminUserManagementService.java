package com.ladygo.admin.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ladygo.admin.domain.AuditLog;
import com.ladygo.admin.repository.AuditLogRepository;
import com.ladygo.admin.web.dto.AdminUserResponse;
import com.ladygo.auth.repository.AdminPasswordResetRepository;
import com.ladygo.auth.service.AdminLoginThrottle;
import com.ladygo.auth.service.AdminPasswordResetService;
import com.ladygo.auth.service.RefreshTokenService;
import com.ladygo.common.exception.ForbiddenException;
import com.ladygo.common.exception.ResourceNotFoundException;
import com.ladygo.common.exception.ValidationException;
import com.ladygo.config.ConfigurationService;
import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SUPER_ADMIN-only staff management (plan step 8). The rules live here, not in the UI: the actor is re-read from the
 * database under a row lock (a token minted before a demotion must not keep working), nobody changes or deactivates
 * their own account, and the last active SUPER_ADMIN can never be demoted or deactivated. Every write is audited with
 * its reason; none of them ever writes a password, token or link to a log or a response.
 */
@Service
public class AdminUserManagementService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserManagementService.class);
    private static final Set<UserRole> STAFF = EnumSet.of(UserRole.ADMIN, UserRole.SUPER_ADMIN, UserRole.SUPPORT);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DEFAULT_INVITE_TTL = Duration.ofHours(24);

    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final RefreshTokenService refreshTokens;
    private final AdminPasswordResetRepository resets;
    private final AdminPasswordResetService passwordResets;
    private final AdminLoginThrottle loginThrottle;
    private final ConfigurationService config;

    public AdminUserManagementService(
            UserRepository users, AuditLogRepository auditLogs, RefreshTokenService refreshTokens,
            AdminPasswordResetRepository resets, AdminPasswordResetService passwordResets,
            AdminLoginThrottle loginThrottle, ConfigurationService config) {
        this.users = users;
        this.auditLogs = auditLogs;
        this.refreshTokens = refreshTokens;
        this.resets = resets;
        this.passwordResets = passwordResets;
        this.loginThrottle = loginThrottle;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> list() {
        return users.findByRoleInOrderByCreatedAtAsc(STAFF).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AdminUserResponse get(UUID id) {
        return toResponse(staff(id));
    }

    @Transactional
    public AdminUserResponse invite(UUID actorId, String email, String name, UserRole role, String reason, String ip) {
        requireStaffRole(role);
        requireActiveSuperAdmin(actorId);
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        if (users.findByEmailIgnoreCase(normalised).isPresent()) {
            throw new ValidationException("An account with this email already exists.");
        }
        User invitee = new User();
        invitee.setEmail(normalised);
        invitee.setName(name.trim());
        invitee.setRole(role);
        invitee.setPhone(placeholderPhone()); // users.phone is NOT NULL and unique; staff sign in by email.
        invitee.setPasswordHash(null); // no usable password until the emailed link is used.
        invitee.setMustChangePassword(false);
        User saved;
        try {
            saved = users.saveAndFlush(invitee);
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException("An account with this email already exists.");
        }
        Duration ttl = config.getMinutes("admin.invite.ttl.minutes", DEFAULT_INVITE_TTL);
        passwordResets.sendInvite(saved, ttl);
        audit(actorId, "ADMIN_USER_INVITED", saved.getId(), ip, Map.of("role", role.name(), "reason", reason.trim()));
        log.info("operation=admin_user_invited actorId={} userId={} role={}", actorId, saved.getId(), role);
        return toResponse(saved);
    }

    @Transactional
    public AdminUserResponse changeRole(UUID actorId, UUID targetId, UserRole newRole, String reason, String ip) {
        requireStaffRole(newRole);
        List<User> activeSuperAdmins = requireActiveSuperAdmin(actorId);
        if (actorId.equals(targetId)) {
            throw new ForbiddenException("You cannot change your own role.");
        }
        User target = staff(targetId);
        if (target.getRole() == newRole) {
            throw new ValidationException("This account already has that role.");
        }
        if (target.getRole() == UserRole.SUPER_ADMIN && target.getStatus() == UserStatus.ACTIVE) {
            requireAnotherActiveSuperAdmin(activeSuperAdmins, targetId);
        }
        UserRole before = target.getRole();
        target.setRole(newRole);
        users.save(target);
        refreshTokens.revokeAll(targetId); // the next sign-in carries the new role.
        audit(actorId, "ADMIN_USER_ROLE_CHANGED", targetId, ip,
                Map.of("from", before.name(), "to", newRole.name(), "reason", reason.trim()));
        log.info("operation=admin_user_role_changed actorId={} userId={} from={} to={}", actorId, targetId, before, newRole);
        return toResponse(target);
    }

    @Transactional
    public AdminUserResponse deactivate(UUID actorId, UUID targetId, String reason, String ip) {
        List<User> activeSuperAdmins = requireActiveSuperAdmin(actorId);
        if (actorId.equals(targetId)) {
            throw new ForbiddenException("You cannot deactivate your own account.");
        }
        User target = staff(targetId);
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new ValidationException("This account is not active.");
        }
        if (target.getRole() == UserRole.SUPER_ADMIN) {
            requireAnotherActiveSuperAdmin(activeSuperAdmins, targetId);
        }
        target.setStatus(UserStatus.SUSPENDED);
        target.setStatusChangedAt(Instant.now());
        users.save(target);
        refreshTokens.revokeAll(targetId);
        resets.invalidateOpenForUser(targetId, Instant.now()); // an unused invite or reset link dies with the account.
        audit(actorId, "ADMIN_USER_DEACTIVATED", targetId, ip, Map.of("reason", reason.trim()));
        log.info("operation=admin_user_deactivated actorId={} userId={}", actorId, targetId);
        return toResponse(target);
    }

    @Transactional
    public AdminUserResponse reactivate(UUID actorId, UUID targetId, String reason, String ip) {
        requireActiveSuperAdmin(actorId);
        User target = staff(targetId);
        if (target.getStatus() != UserStatus.SUSPENDED) {
            throw new ValidationException("This account is not deactivated.");
        }
        target.setStatus(UserStatus.ACTIVE);
        target.setStatusChangedAt(Instant.now());
        users.save(target);
        audit(actorId, "ADMIN_USER_REACTIVATED", targetId, ip, Map.of("reason", reason.trim()));
        log.info("operation=admin_user_reactivated actorId={} userId={}", actorId, targetId);
        return toResponse(target);
    }

    /**
     * Throws unless some active SUPER_ADMIN other than {@code targetId} exists in {@code activeSuperAdmins} (the
     * row-locked list, so the count cannot change under the caller).
     */
    public static void requireAnotherActiveSuperAdmin(List<User> activeSuperAdmins, UUID targetId) {
        boolean another = activeSuperAdmins.stream().anyMatch(u -> u.getRole() == UserRole.SUPER_ADMIN
                && u.getStatus() == UserStatus.ACTIVE && !u.getId().equals(targetId));
        if (!another) {
            throw new ForbiddenException("This is the last active SUPER_ADMIN, so it cannot be demoted or deactivated.");
        }
    }

    /** Locks every active SUPER_ADMIN row and checks the actor is one of them right now (not just in their token). */
    private List<User> requireActiveSuperAdmin(UUID actorId) {
        List<User> locked = users.lockByRoleAndStatus(UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
        if (locked.stream().noneMatch(u -> u.getId().equals(actorId))) {
            throw new ForbiddenException();
        }
        return locked;
    }

    private static void requireStaffRole(UserRole role) {
        if (!STAFF.contains(role)) {
            throw new ValidationException("Role must be ADMIN, SUPPORT or SUPER_ADMIN.");
        }
    }

    private User staff(UUID id) {
        return users.findByIdAndRoleIn(id, STAFF).orElseThrow(ResourceNotFoundException::new);
    }

    private String placeholderPhone() {
        // Country code 999 is unassigned, so this can never be a real customer's or driver's number.
        for (int attempt = 0; attempt < 10; attempt++) {
            String phone = "+999" + String.format("%010d", RANDOM.nextLong(10_000_000_000L));
            if (!users.existsByPhone(phone)) {
                return phone;
            }
        }
        throw new IllegalStateException("Could not allocate a placeholder phone number.");
    }

    private void audit(UUID actorId, String action, UUID targetId, String ip, Map<String, Object> metadata) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setActorRole(UserRole.SUPER_ADMIN.name());
        entry.setAction(action);
        entry.setTargetType("users");
        entry.setTargetId(targetId.toString());
        entry.setMetadata(metadata);
        entry.setIp(ip);
        auditLogs.save(entry);
    }

    private AdminUserResponse toResponse(User user) {
        boolean locked = user.getEmail() != null && loginThrottle.isLocked(user.getEmail());
        return new AdminUserResponse(user.getId(), user.getName(), maskEmail(user.getEmail()), user.getRole(),
                user.getStatus(), user.isMfaEnabled(), user.getLastLoginAt(), locked,
                user.getPasswordHash() == null, user.getCreatedAt());
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
