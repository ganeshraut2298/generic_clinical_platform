package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.SalesEnquiryService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * With the default configuration (no trusted proxy), a client cannot change the address the rate
 * limit sees by sending X-Forwarded-For: the embedded server is run for real here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "SAHAJ_DB_APP_PASSWORD=not-used-no-database-in-this-test",
        "sahaj.opd.partition-check.enabled=false",
        "SAHAJ_JWT_SECRET=not-used-no-database-in-this-test-but-must-be-32-plus-chars"
})
@Import(ForwardedHeaderTestSupport.NoDatabase.class)
class ForwardedHeaderIgnoredByDefaultTest extends ForwardedHeaderTestSupport {

    @LocalServerPort
    private int port;

    @MockBean
    private SalesEnquiryService service;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void aSpoofedForwardedForHeaderDoesNotGiveAFreshAllowance() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());
        assertThat(environment.getProperty("server.tomcat.remoteip.remote-ip-header")).isNullOrEmpty();

        for (int i = 0; i < 5; i++) {
            assertThat(post(port, "203.0.113." + i)).isEqualTo(201);
        }
        for (int i = 10; i < 30; i++) {
            assertThat(post(port, "203.0.113." + i)).isEqualTo(429);
        }
        assertThat(post(port, null)).isEqualTo(429);
    }
}
