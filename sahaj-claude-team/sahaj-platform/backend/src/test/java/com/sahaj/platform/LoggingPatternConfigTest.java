package com.sahaj.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Finding 10 (code review, SAH-1/SAH-2): the log pattern must print {@code requestId} and
 * {@code tenantId} (from {@code RequestLogContextFilter}'s MDC), so a support engineer can find
 * every line for one request or one clinic without turning on DEBUG.
 */
class LoggingPatternConfigTest {

    @Test
    void theConsoleAndFilePatternsPrintRequestIdAndTenantId() throws IOException {
        String yaml = Files.readString(Path.of("src", "main", "resources", "application.yml"));

        assertThat(yaml).contains("%X{requestId");
        assertThat(yaml).contains("%X{tenantId");
    }
}
