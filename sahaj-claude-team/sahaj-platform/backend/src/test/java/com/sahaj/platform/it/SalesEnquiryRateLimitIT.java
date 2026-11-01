package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.sahaj.platform.support.LogCapture;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * SAH-2: the public enquiry endpoint allows 5 requests per address per hour; the 6th gets 429.
 * Uses the real embedded server and a real HTTP client (not MockMvc), against sahaj_test. This class
 * has a Spring context of its own (the marker property), so its counter starts at zero and no other
 * test shares the loopback address key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "it.context=rate-limit")
@ActiveProfiles("test")
class SalesEnquiryRateLimitIT {

    private static final String MOBILE = "9345678012";

    @LocalServerPort
    private int port;

    @Autowired
    private Environment env;

    @Test
    void theSixthRequestFromOneAddressInAnHourIs429AndIsNotSaved() throws Exception {
        assertThat(env.getProperty("sahaj.public-enquiry-limit.max-requests")).as("default limit").isEqualTo("5");
        RealHttpSupport http = new RealHttpSupport(port);
        DbAccess db = new DbAccess(env);
        String name = "IT rate limit " + UUID.randomUUID();

        try (LogCapture logs = new LogCapture()) {
            for (int i = 1; i <= 5; i++) {
                HttpResponse<String> ok = http.post(RealHttpSupport.body(name, MOBILE));
                assertThat(ok.statusCode()).as("request " + i).isEqualTo(201);
                assertThat((String) JsonPath.read(ok.body(), "$.id")).isNotBlank();
            }

            HttpResponse<String> sixth = http.post(RealHttpSupport.body(name, MOBILE));
            assertThat(sixth.statusCode()).as("6th request").isEqualTo(429);
            assertThat(sixth.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(sixth.headers().firstValue("Retry-After").orElse("")).matches("\\d+");
            assertThat((Integer) JsonPath.read(sixth.body(), "$.status")).isEqualTo(429);
            assertThat((String) JsonPath.read(sixth.body(), "$.type")).isEqualTo("https://sahaj.in/problems/rate-limit");
            assertThat((Integer) JsonPath.read(sixth.body(), "$.retryAfterSeconds")).isPositive();
            assertThat(sixth.body()).doesNotContain(MOBILE);

            // A 7th request, even an invalid one, is also refused: the limit is checked before the body is read.
            HttpResponse<String> seventh = http.post("{}");
            assertThat(seventh.statusCode()).as("7th request (invalid body)").isEqualTo(429);

            assertThat(logs.allText()).as("the mobile number must not be logged").doesNotContain(MOBILE);
        }

        long saved = db.asMigrator(c -> DbAccess.count(c, "select count(*) from platform.sales_enquiry where name = ?", name));
        assertThat(saved).as("only the five allowed requests were saved").isEqualTo(5);
    }
}
