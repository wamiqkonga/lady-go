package com.ladygo.security;

import java.util.List;
import com.ladygo.security.jwt.JwtAuthenticationFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API, secure headers, strict CORS allow-list, every endpoint authenticated except explicit
 * public ones. JWT authentication (Phase 3): {@link JwtAuthenticationFilter} runs ahead of
 * {@link UsernamePasswordAuthenticationFilter} and populates the security context from a Bearer token;
 * enrolment-scoped tokens only satisfy {@code hasAuthority('SCOPE_ENROLMENT')}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/system/ping",
            "/api/v1/auth/otp/request",
            "/api/v1/auth/otp/verify",
            "/api/v1/auth/refresh",
            "/api/v1/auth/admin/login",
            "/api/v1/auth/admin/refresh",
            "/api/v1/auth/admin/logout",
            "/api/v1/auth/admin/password/forgot",
            "/api/v1/auth/admin/password/reset",
            "/api/v1/trip-shares/lookup",
            "/internal/files",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, JsonSecurityErrorHandler errorHandler, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http
                // Spring's own CsrfFilter is for session-cookie-authenticated apps; the great majority
                // of this API is stateless Bearer-token auth, immune to CSRF by construction (a
                // cross-site request cannot forge an Authorization header), so that filter is disabled
                // here. The two admin-web endpoints that DO rely on an ambient cookie
                // (AdminAuthCookies.REFRESH_TOKEN, for /auth/admin/refresh and /auth/admin/logout) are
                // instead protected by the purpose-built AdminCsrfGuard (double-submit token + Origin/
                // Referer allow-list) called explicitly from AdminAuthController - not by this filter.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-CSRF-Token: admin-web's double-submit header (com.ladygo.auth.web.AdminAuthCookies.CSRF_HEADER
        // - not referenced directly here to avoid a security<->auth package cycle; keep both in sync).
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key", "X-CSRF-Token"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        // true only because the admin web's refresh-token cookie needs it to be sent/received
        // cross-origin at all (docs/product/admin-web-design.md section 7) - every origin on the list
        // is already explicit and never "*", so this does not widen who can call the API, only
        // whether a cookie travels with an already-allowed origin's request.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
