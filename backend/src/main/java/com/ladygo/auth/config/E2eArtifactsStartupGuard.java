package com.ladygo.auth.config;

import java.util.Arrays;
import java.util.Set;
import com.ladygo.config.ConfigurationService;
import com.ladygo.users.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Same defence-in-depth shape as {@code SmsSenderStartupGuard}/{@code DevOnlyFlagsStartupGuard} (docs/
 * security/auth-design.md section 9), for two things {@code scripts/ci/e2e-seed.sh} deliberately
 * leaves behind in whatever database it ran against: an admin login rate limit raised far above any
 * reasonable production value, and throwaway staff accounts. Both are safe in the CI job's own
 * ephemeral, destroyed-at-job-end database - neither should ever be true of a database a real
 * staging/prod app instance connects to. Turns "someone pointed this profile at the wrong database,
 * or a seed script ran somewhere it shouldn't have" into an explicit startup failure instead of a
 * silently weakened anti-brute-force limit and known-credential accounts sitting in a real
 * environment. An {@link ApplicationRunner}, not {@code @PostConstruct} like the other two guards -
 * both checks here need a working DataSource/JPA, which {@code @PostConstruct} on an eagerly
 * constructed bean is not guaranteed to have yet (an ApplicationRunner, like
 * {@link AdminBootstrapRunner}, only runs once the whole context - including Flyway and JPA - is up).
 */
@Component
public class E2eArtifactsStartupGuard implements ApplicationRunner {

    private static final Set<String> GUARDED_PROFILES = Set.of("staging", "prod");

    /** scripts/ci/e2e-seed.sh raises admin.login.max.attempts to 200, far above the seeded default of
     *  5 (V12 migration) - this is a wide threshold, not an exact match on either value, so a
     *  legitimate future change to the real production default (within reason) never trips it. */
    private static final int MAX_REASONABLE_LOGIN_ATTEMPTS = 20;

    /** Every account scripts/ci/e2e-seed.sh creates uses this domain exclusively - no real staff
     *  account should ever be provisioned with it. */
    private static final String SEED_EMAIL_DOMAIN = "@ladygo.test";

    private final Environment environment;
    private final ConfigurationService config;
    private final UserRepository users;

    public E2eArtifactsStartupGuard(Environment environment, ConfigurationService config, UserRepository users) {
        this.environment = environment;
        this.config = config;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (!guarded) {
            return;
        }

        int maxAttempts = config.getInt("admin.login.max.attempts", 5);
        if (maxAttempts > MAX_REASONABLE_LOGIN_ATTEMPTS) {
            throw new IllegalStateException(
                    "admin.login.max.attempts is " + maxAttempts + ", far above any reasonable production "
                            + "value - this looks like scripts/ci/e2e-seed.sh ran against this database. "
                            + "Refusing to start under staging/prod.");
        }
        if (users.existsByEmailEndingWithIgnoreCase(SEED_EMAIL_DOMAIN)) {
            throw new IllegalStateException(
                    "A user with a " + SEED_EMAIL_DOMAIN + " email exists - scripts/ci/e2e-seed.sh's "
                            + "throwaway accounts use this domain exclusively. Refusing to start under "
                            + "staging/prod.");
        }
    }
}
