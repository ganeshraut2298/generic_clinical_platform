package com.sahaj.platform.infrastructure.tenancy;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces the default transaction manager with one that binds the tenant at transaction start.
 * The request pool (the {@code spring.datasource} pool, user {@code sahaj_app}) is the only data
 * source it serves; Flyway uses its own connection as {@code sahaj_migrator}.
 */
@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantBindingTransactionManager(dataSource);
    }
}
