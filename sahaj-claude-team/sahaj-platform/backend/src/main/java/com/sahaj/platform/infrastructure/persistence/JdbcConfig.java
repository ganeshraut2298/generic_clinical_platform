package com.sahaj.platform.infrastructure.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Supplies the {@code NamedParameterJdbcTemplate} that Spring Boot wraps in the shared
 * {@code JdbcClient}. See {@link InstantAwareNamedParameterJdbcTemplate} for why.
 */
@Configuration(proxyBeanMethods = false)
public class JdbcConfig {

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
        return new InstantAwareNamedParameterJdbcTemplate(jdbcTemplate);
    }
}
