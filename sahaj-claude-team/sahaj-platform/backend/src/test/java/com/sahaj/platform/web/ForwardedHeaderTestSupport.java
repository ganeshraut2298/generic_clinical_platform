package com.sahaj.platform.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

/** Shared helpers for the tests that run the real embedded server and send real HTTP requests. */
abstract class ForwardedHeaderTestSupport {

    static final String BODY = """
            {"name": "Asha", "mobile": "9123456789", "source": "CONTACT_SALES"}
            """;

    /** No database in these tests: the data source is a mock and the enquiry service is mocked. */
    @TestConfiguration(proxyBeanMethods = false)
    static class NoDatabase {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();

    /** Posts one enquiry from the test machine's loopback address, with an optional X-Forwarded-For. */
    int post(int port, String forwardedFor) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/platform/public/sales-enquiries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
