package com.velora.api.common.security;

import com.velora.api.identity.security.JwtAuthenticationFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security rules for the VELORA API.
 *
 * <p>Authorization is enforced HERE, on the server, for every request. Hiding a
 * button in Angular is a user-experience choice, never a security control.
 *
 * <p>Object-level checks are a separate concern and belong in the service layer:
 * {@code GET /api/v1/me/orders/{id}} must verify the order belongs to the caller.
 * Changing an id in the URL to read someone else's order is the most common
 * real-world API vulnerability, and no filter chain can prevent it for you.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Reachable without authentication. */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/otp/**",
            "/api/v1/auth/password/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/actuator/health",
            "/actuator/info",
            "/error"
    };

    /** Storefront browsing — public for GET only. */
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/products/**",
            "/api/v1/categories/**",
            "/api/v1/brands/**",
            "/api/v1/portfolio/**",
            "/api/v1/variants/*/availability",
            "/api/v1/geo/**",
            "/api/v1/ping",
            "/uploads/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${velora.cors.allowed-origins}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Stateless token API: there is no session cookie for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        // Guest checkout: cart and order placement work without an account.
                        .requestMatchers("/api/v1/cart/**").permitAll()
                        .requestMatchers("/api/v1/shipping/quote").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").permitAll()
                        // Custom requests: a guest may ask without an account. The upload
                        // is guarded by a signed per-request token, not by login.
                        .requestMatchers(HttpMethod.POST, "/api/v1/custom-requests",
                                "/api/v1/custom-requests/*/attachments").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt with strength 12. Never MD5, SHA-1 or plain SHA-256 — those are fast,
     * which is exactly the wrong property for a password hash.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "X-Guest-Token", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
