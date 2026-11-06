package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.infrastructure.admin.AdminProperties;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Everything the dev-only sample admin needs except its security chain and controller (ADR-008 D2): the gate,
 * the repository, the service, the Flyway location of the dev-tools callback and the start-up warning. Exists
 * only when conditions C1 to C5 hold, and only in a build made with the Maven profile {@code dev-tools}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDevSampleAdmin
class DevSampleAdminConfiguration {

    /** One value per context start (ADR-008 R1): a restart makes a new one and prints a new line. */
    @Bean
    DevSampleAdminNonce devSampleAdminNonce() {
        return DevSampleAdminNonce.generate();
    }

    @Bean
    DevSampleAdminGate devSampleAdminGate(DevSampleAdminNonce nonce) {
        return new DevSampleAdminGate(nonce);
    }

    @Bean
    DevSampleAdminRepository devSampleAdminRepository(JdbcClient jdbc) {
        return new JdbcDevSampleAdminRepository(jdbc);
    }

    @Bean
    DevSampleAdminService devSampleAdminService(DevSampleAdminRepository repository, AdminPasswordHasher hasher,
                                                TotpSecretCipher cipher, AdminProperties properties) {
        return new DevSampleAdminService(repository, hasher, cipher, new SecureRandom(),
                properties.totp().devBypass());
    }

    @Bean
    DevSampleAdminFlywayCustomizer devSampleAdminFlywayCustomizer() {
        return new DevSampleAdminFlywayCustomizer();
    }

    @Bean
    DevSampleAdminStartupNotice devSampleAdminStartupNotice(DevSampleAdminNonce nonce) {
        return new DevSampleAdminStartupNotice(nonce);
    }
}
