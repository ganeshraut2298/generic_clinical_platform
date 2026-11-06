package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminAuthRepository;
import com.sahaj.platform.application.admin.AdminAuthService;
import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.AdminTokenHasher;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The admin realm itself: keys (checked at start), token service, hashers, cipher, network policy,
 * rate limits, repository and sign-in service. Exists only when {@code sahaj.admin.enabled=true} and
 * both secrets are present. A secret that is present but invalid stops the application.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminRealm
public class AdminRealmConfig {

    @Bean
    AdminKeyMaterial adminKeyMaterial(AdminProperties properties,
                                      @Value("${sahaj.jwt.secret:}") String clinicJwtSecret) {
        return AdminKeyMaterial.validate(properties, clinicJwtSecret);
    }

    @Bean
    AdminTokenHasher adminTokenHasher(AdminKeyMaterial keys) {
        return new AdminTokenHasher(keys.jwtSecret());
    }

    @Bean
    TotpSecretCipher totpSecretCipher(AdminKeyMaterial keys) {
        return new TotpSecretCipher(keys.totpKey(), new SecureRandom());
    }

    @Bean
    AdminTokenService adminTokenService(AdminKeyMaterial keys, Clock clock) {
        return new AdminJwtService(keys.jwtSecret(), clock);
    }

    @Bean
    AdminPasswordHasher adminPasswordHasher() {
        return new AdminPasswordHasher(new SecureRandom());
    }

    @Bean
    AdminNetworkPolicy adminNetworkPolicy(AdminProperties properties, Environment environment) {
        return new AdminNetworkPolicy(properties.allowedNetworks(), environment.acceptsProfiles(Profiles.of("dev")));
    }

    @Bean
    AdminRateLimits adminRateLimits(Clock clock) {
        return new AdminRateLimits(clock);
    }

    @Bean
    AdminDevBypass adminDevBypass(AdminProperties properties) {
        return new AdminDevBypass(properties.totp().devBypass());
    }

    @Bean
    AdminAuthRepository adminAuthRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        return new JdbcAdminAuthRepository(jdbc, transactionManager);
    }

    @Bean
    AdminAuthService adminAuthService(AdminAuthRepository repository, AdminTokenService tokens,
                                      AdminTokenHasher hasher, AdminPasswordHasher passwords,
                                      TotpSecretCipher cipher, AdminDevBypass devBypass, AdminRateLimits limits,
                                      Clock clock) {
        return new AdminAuthService(repository, tokens, hasher, passwords, cipher, devBypass, limits,
                new SecureRandom(), clock);
    }
}
