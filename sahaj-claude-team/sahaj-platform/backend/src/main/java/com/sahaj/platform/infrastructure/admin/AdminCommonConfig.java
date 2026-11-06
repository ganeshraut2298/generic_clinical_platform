package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import java.security.SecureRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * What exists as soon as {@code sahaj.admin.enabled=true}, whether or not the secrets are set: the
 * settings, the two startup guards (dev bypass and seed values outside dev) and, in the
 * {@code dev} profile only, the Flyway customizer that adds the dev-seed location and placeholders.
 * With the property absent or false (the default) none of this exists, so nothing changes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sahaj.admin", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AdminProperties.class)
public class AdminCommonConfig {

    @Bean
    AdminTotpDevBypassGuard adminTotpDevBypassGuard(AdminProperties properties, Environment environment) {
        return new AdminTotpDevBypassGuard(properties.totp().devBypass(), environment);
    }

    @Bean
    AdminSeedGuard adminSeedGuard(AdminProperties properties, Environment environment) {
        return new AdminSeedGuard(properties.seed(), environment);
    }

    /** Dev profile only. Hashes the seed password in Java; the plain value never reaches Flyway. */
    @Bean
    @Profile("dev")
    AdminSeedFlywayCustomizer adminSeedFlywayCustomizer(AdminProperties properties) {
        return new AdminSeedFlywayCustomizer(properties.seed(), new AdminPasswordHasher(new SecureRandom()));
    }
}
