package com.sahaj.platform.web.admin;

import com.sahaj.platform.infrastructure.admin.ConditionalOnAdminUnavailable;
import com.sahaj.platform.infrastructure.security.ProblemJsonWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The admin portal is switched on ({@code sahaj.admin.enabled}) but {@code SAHAJ_ADMIN_JWT_SECRET}
 * or {@code SAHAJ_ADMIN_TOTP_KEY} is missing: the application still starts for clinic work and every
 * {@code /api/v1/admin/**} request answers {@code 503 admin-unavailable} (ADR-003 D3 "Negative").
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminUnavailable
class AdminUnavailableConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminUnavailableConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain adminUnavailableFilterChain(HttpSecurity http, ProblemJsonWriter problemWriter)
            throws Exception {
        log.warn("The admin portal is enabled but SAHAJ_ADMIN_JWT_SECRET or SAHAJ_ADMIN_TOTP_KEY is missing. "
                + "Every /api/v1/admin request answers 503.");
        http
                .securityMatcher(AdminRequestPaths.BASE + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        problemWriter.write(response, AdminProblems.unavailable());
                    }
                }, SecurityContextHolderFilter.class);
        return http.build();
    }
}
