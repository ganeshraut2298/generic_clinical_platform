package com.sahaj;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the Spring context loads in the {@code dev} profile with no database and no tenancy
 * code present (SAH-1-1 acceptance criterion). Per ADR-001 decision D3, no {@code DataSource} is
 * on the classpath, so this starts no outbound connection.
 */
@SpringBootTest
@ActiveProfiles("dev")
class SahajApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
