package com.sahaj.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * DEC-054: every connection of the request pool is started with a lock timeout and a statement timeout, so one stuck
 * request can never hold the server for ever. The values are set as the pgjdbc start-up parameter {@code options}; this
 * test fails if someone removes them or renames the property.
 */
class DatabaseTimeoutConfigTest {

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    @Test
    void theRequestPoolStartsEveryConnectionWithALockAndAStatementTimeout() {
        String options = load().getProperty("spring.datasource.hikari.data-source-properties.options");

        assertThat(options).isNotNull();
        assertThat(options).contains("-c lock_timeout=${SAHAJ_DB_LOCK_TIMEOUT_MS:10000}");
        assertThat(options).contains("-c statement_timeout=${SAHAJ_DB_STATEMENT_TIMEOUT_MS:30000}");
    }

    @Test
    void theDefaultsAreTenSecondsForALockAndThirtyForAStatement() {
        String options = load().getProperty("spring.datasource.hikari.data-source-properties.options");

        assertThat(options).contains(":10000}").contains(":30000}");
    }

    @Test
    void thePropertyIsTheNamePgjdbcReads() {
        assertThat(PGProperty.OPTIONS.getName()).isEqualTo("options");
    }

    @Test
    void aRequestThatFindsNoFreeConnectionGivesUpSoonerThanThePoolDefault() {
        Properties properties = load();

        assertThat(properties.getProperty("spring.datasource.hikari.connection-timeout"))
                .isEqualTo("${SAHAJ_DB_CONNECTION_TIMEOUT_MS:10000}");
        assertThat(properties.getProperty("spring.datasource.hikari.leak-detection-threshold"))
                .isEqualTo("${SAHAJ_DB_LEAK_DETECTION_MS:60000}");
    }
}
