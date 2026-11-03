package com.ladygo.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.UserStatus;
import com.ladygo.users.repository.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates the security context from a Bearer JWT. An enrolment-scoped token (see AuthPrincipal) gets
 * the single authority SCOPE_ENROLMENT instead of a ROLE_* authority, so {@code @PreAuthorize} on the
 * enrolment endpoints and on every normal endpoint stays a simple, uniform authority check.
 *
 * <p>Enrolment tokens are confined to the three enrolment endpoints here, at the filter level, rather
 * than relying on every other endpoint remembering to add a role-based {@code @PreAuthorize} (adversarial
 * review finding, HIGH: without this, an enrolment token satisfied the blanket
 * {@code anyRequest().authenticated()} rule and could reach endpoints like {@code /auth/logout} that
 * carry no specific role check). Outside those paths, an enrolment token is treated as if it were never
 * presented at all, so the request falls through to the normal unauthenticated handling.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> ENROLMENT_ALLOWED_PATHS = Set.of(
            "/api/v1/auth/admin/password/change",
            "/api/v1/auth/admin/mfa/enroll",
            "/api/v1/auth/admin/mfa/enroll/confirm");

    private final JwtService jwtService;

    private static final Set<UserRole> STAFF = EnumSet.of(UserRole.ADMIN, UserRole.SUPER_ADMIN, UserRole.SUPPORT);

    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    /** A staff access token is only as good as the account behind it: deactivating or re-roling someone must take
     *  effect now, not when their (up to minutes-long) token expires. One primary-key lookup per staff request. */
    private boolean staffAccountStillMatches(AuthPrincipal p) {
        if (p.role() == null || !STAFF.contains(p.role())) {
            return true;
        }
        return users.findById(p.userId())
                .map(u -> u.getStatus() == UserStatus.ACTIVE && u.getRole() == p.role())
                .orElse(false);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            Optional<AuthPrincipal> principal = jwtService.parse(token);
            principal.ifPresent(p -> {
                if (p.enrolment() && !ENROLMENT_ALLOWED_PATHS.contains(request.getRequestURI())) {
                    return;
                }
                if (!p.enrolment() && !staffAccountStillMatches(p)) {
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(p, null, authorities(p)));
            });
        }
        chain.doFilter(request, response);
    }

    private List<GrantedAuthority> authorities(AuthPrincipal principal) {
        String authority = principal.enrolment() ? "SCOPE_ENROLMENT" : "ROLE_" + principal.role().name();
        return List.of(new SimpleGrantedAuthority(authority));
    }
}
