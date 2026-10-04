package com.ladygo.auth.config;

import com.ladygo.users.domain.User;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first SUPER_ADMIN if (and only if) none exists yet (docs/security/auth-design.md
 * section 3.1). The bootstrap password is one-time: the created account has {@code must_change_password
 * = true}, so it cannot be used past the forced enrolment flow.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final String bootstrapPhone;

    public AdminBootstrapRunner(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${LADYGO_BOOTSTRAP_ADMIN_EMAIL:}") String bootstrapEmail,
            @Value("${LADYGO_BOOTSTRAP_ADMIN_PASSWORD:}") String bootstrapPassword,
            @Value("${LADYGO_BOOTSTRAP_ADMIN_PHONE:}") String bootstrapPhone) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
        this.bootstrapPhone = bootstrapPhone;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(UserRole.SUPER_ADMIN)) {
            return;
        }
        // users.phone is NOT NULL for every role (V2__users_auth.sql); a staff account needs one too,
        // even though staff sign in by email - it is not used as a login factor for ADMIN/SUPER_ADMIN/SUPPORT.
        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank() || bootstrapPhone.isBlank()) {
            log.warn("operation=admin_bootstrap result=skipped reason=no_super_admin_and_no_bootstrap_env_vars");
            return;
        }
        User admin = new User();
        admin.setEmail(bootstrapEmail);
        admin.setPhone(bootstrapPhone);
        admin.setRole(UserRole.SUPER_ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
        admin.setMustChangePassword(true);
        admin.setMfaEnabled(false);
        users.save(admin);
        log.warn("operation=admin_bootstrap result=created email={}", bootstrapEmail);
    }
}
