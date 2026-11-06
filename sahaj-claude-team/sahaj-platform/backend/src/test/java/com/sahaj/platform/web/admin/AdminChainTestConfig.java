package com.sahaj.platform.web.admin;

import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.infrastructure.admin.AdminJwtService;
import com.sahaj.platform.infrastructure.admin.AdminNetworkPolicy;
import com.sahaj.platform.infrastructure.admin.AdminRateLimits;
import com.sahaj.platform.infrastructure.security.JwtProperties;
import com.sahaj.platform.infrastructure.security.JwtTokenService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Real tokens, real network policy and real limiters for the admin chain slice tests. The services
 * and the repository are mocks supplied by each test with {@code @MockBean}.
 */
@TestConfiguration(proxyBeanMethods = false)
class AdminChainTestConfig {

    static final String CLINIC_SECRET = "clinic-secret-clinic-secret-clinic-secret";
    static final String ADMIN_SECRET = "admin-secret-admin-secret-admin-secret-1";

    @Bean
    TokenService clinicTokenService() {
        return new JwtTokenService(new JwtProperties(CLINIC_SECRET, 15, 5), Clock.systemUTC());
    }

    @Bean
    AdminTokenService adminTokenService() {
        return new AdminJwtService(ADMIN_SECRET, Clock.systemUTC());
    }

    @Bean
    AdminNetworkPolicy adminNetworkPolicy(@Value("${test.admin.networks:}") String networks,
                                          @Value("${test.admin.dev:true}") boolean dev) {
        return new AdminNetworkPolicy(networks, dev);
    }

    @Bean
    AdminRateLimits adminRateLimits() {
        return new AdminRateLimits(Clock.systemUTC());
    }
}
