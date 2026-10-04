package com.ladygo.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
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
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            Optional<AuthPrincipal> principal = jwtService.parse(token);
            principal.ifPresent(p -> SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(p, null, authorities(p))));
        }
        chain.doFilter(request, response);
    }

    private List<GrantedAuthority> authorities(AuthPrincipal principal) {
        String authority = principal.enrolment() ? "SCOPE_ENROLMENT" : "ROLE_" + principal.role().name();
        return List.of(new SimpleGrantedAuthority(authority));
    }
}
