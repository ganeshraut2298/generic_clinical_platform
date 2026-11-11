package com.sahaj.platform.devtools.bootstrap;

import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.infrastructure.admin.AdminKeyMaterial;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Everything the bootstrap registration needs except its security chain and controller (ADR-018 D1, D2): the gate,
 * the PIN verifier, the ticket hasher, the repository, the service and the Flyway location of the dev-tools callback.
 * Exists only when conditions B2 to B7 hold, and only in a build made with the Maven profile {@code dev-tools}. The
 * PIN hash is read once, here, into the verifier; no endpoint exposes it and nothing logs it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAdminBootstrap
class BootstrapConfiguration {

    @Bean
    BootstrapGate bootstrapGate() {
        return new BootstrapGate();
    }

    @Bean
    BootstrapPinVerifier bootstrapPinVerifier(Environment environment) {
        return new BootstrapPinVerifier(environment.getRequiredProperty(BootstrapConditions.PIN_HASH),
                new SecureRandom());
    }

    @Bean
    BootstrapTicketHasher bootstrapTicketHasher(AdminKeyMaterial keys) {
        return new BootstrapTicketHasher(keys.jwtSecret());
    }

    @Bean
    BootstrapRepository bootstrapRepository(JdbcClient jdbc) {
        return new JdbcBootstrapRepository(jdbc);
    }

    @Bean
    BootstrapService bootstrapService(BootstrapRepository repository, BootstrapPinVerifier verifier,
                                      BootstrapTicketHasher ticketHasher, AdminPasswordHasher passwordHasher,
                                      Clock clock, PlatformTransactionManager transactionManager) {
        return new BootstrapService(repository, verifier, ticketHasher, passwordHasher,
                new SecureRandom(), clock, transactionManager,
                duration -> Thread.sleep(duration.toMillis()));
    }

    @Bean
    BootstrapFlywayCustomizer bootstrapFlywayCustomizer() {
        return new BootstrapFlywayCustomizer();
    }
}
