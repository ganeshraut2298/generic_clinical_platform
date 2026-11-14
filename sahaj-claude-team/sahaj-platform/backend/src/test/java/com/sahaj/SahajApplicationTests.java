package com.sahaj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.infrastructure.tenancy.TenantBindingTransactionManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring check that needs no database: the whole application context starts with a mock
 * {@link DataSource} and with Flyway switched off. It proves the beans fit together (security
 * chain, tenant binding transaction manager, audit port). The check against a real PostgreSQL
 * ({@code sahaj_app} and {@code sahaj_migrator}, Flyway) is an integration test.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "SAHAJ_DB_APP_PASSWORD=not-used-no-database-in-this-test",
        "sahaj.opd.partition-check.enabled=false",
        "SAHAJ_JWT_SECRET=not-used-no-database-in-this-test-but-must-be-32-plus-chars"
})
@ActiveProfiles("dev")
@Import(SahajApplicationTests.NoDatabase.class)
class SahajApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class NoDatabase {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithTheTenantBoundTransactionManagerAndDenyByDefaultSecurity() {
        assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(TenantBindingTransactionManager.class);
        assertThat(context.getBean(AuditLog.class)).isNotNull();
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }
}
