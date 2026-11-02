package com.sahaj.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.SalesEnquiryService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Behind a configured reverse proxy (SAHAJ_REMOTE_IP_HEADER and SAHAJ_TRUSTED_PROXIES set), the
 * real client address is read from the right-hand end of X-Forwarded-For, and only when the direct
 * peer is a trusted proxy. Here the test machine's loopback address plays the proxy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "SAHAJ_DB_APP_PASSWORD=not-used-no-database-in-this-test",
        "SAHAJ_JWT_SECRET=not-used-no-database-in-this-test-but-must-be-32-plus-chars",
        "SAHAJ_REMOTE_IP_HEADER=X-Forwarded-For",
        "SAHAJ_TRUSTED_PROXIES=127\\.0\\.0\\.1"
})
@Import(ForwardedHeaderTestSupport.NoDatabase.class)
class ForwardedHeaderTrustedProxyTest extends ForwardedHeaderTestSupport {

    @LocalServerPort
    private int port;

    @MockBean
    private SalesEnquiryService service;

    @Test
    void theClientBehindATrustedProxyIsKeyedByItsOwnAddress() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());

        for (int i = 0; i < 5; i++) {
            assertThat(post(port, "203.0.113.50")).isEqualTo(201);
        }
        assertThat(post(port, "203.0.113.50")).isEqualTo(429);
        // a different real client behind the same proxy has its own allowance
        assertThat(post(port, "203.0.113.51")).isEqualTo(201);
    }

    @Test
    void aForgedLeftHandEntryDoesNotHelp() throws Exception {
        when(service.create(any())).thenReturn(UUID.randomUUID());

        // The proxy appends the address it saw (198.51.100.9) at the right; the client forged what is left of it.
        for (int i = 0; i < 5; i++) {
            assertThat(post(port, "203.0.113." + i + ", 198.51.100.9")).isEqualTo(201);
        }
        assertThat(post(port, "203.0.113.200, 198.51.100.9")).isEqualTo(429);
    }
}
