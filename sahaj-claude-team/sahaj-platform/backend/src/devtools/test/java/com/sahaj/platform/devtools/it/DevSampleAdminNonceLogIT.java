package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.RawHttp;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * DEC-016 / ADR-008 test T17 (R1): the nonce is printed exactly once, in the single start-up WARN line, changes at every
 * start, and is found nowhere else: not in another log line (also at DEBUG), not in a response header or body, and not in
 * any property source value. (The audit-column and 201-output parts of T17 are in {@code DevSampleAdminEndToEndIT}, which
 * needs the admin tables.)
 */
class DevSampleAdminNonceLogIT {

    private static final String SENTENCE_START = "Dev-only sample admin endpoint is on (dev-tools build). POST "
            + "/api/v1/admin/dev/sample-admin from this machine creates an admin once. Send header X-Sahaj-Dev-Nonce: ";
    private static final String SENTENCE_END = ". The value changes at every start.";

    private static List<String> linesWithTheNonce(AppRun app, String nonce) {
        List<String> lines = new ArrayList<>();
        for (String line : app.logLines()) {
            if (line.contains(nonce)) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    void theWarnLineIsPrintedOnceWithExactlyThatSentenceAndTheNonceIsInNoOtherPlace() throws IOException {
        try (AppRun app = AppRun.builder().debug("root", "org.springframework.web", "org.springframework.security").start()) {
            String nonce = app.nonceFromLog();
            assertThat(nonce).matches("[0-9a-f]{32}");

            List<String> warnLines = app.logLines().stream().filter(l -> l.contains("Dev-only sample admin endpoint is on")).toList();
            assertThat(warnLines).as("start-up WARN lines").hasSize(1);
            assertThat(warnLines.get(0)).startsWith("WARN ").contains(SENTENCE_START + nonce + SENTENCE_END);
            assertThat(warnLines.get(0).replace(SENTENCE_START + nonce + SENTENCE_END, ""))
                    .as("the line holds no other value").doesNotContain("@").doesNotContain("password").doesNotContain("secret");

            // Some traffic: refusals of several kinds, a wrong value and a right value with a forbidden header.
            List<RawHttp.Response> responses = new ArrayList<>();
            responses.add(app.devCall(null).send());
            responses.add(app.devCall("f".repeat(32)).send());
            responses.add(app.devCall(nonce).header("X-Forwarded-For", "127.0.0.1").send());
            responses.add(app.devCall(nonce).header("Origin", "http://localhost:4200").send());
            responses.add(app.request("GET", AppRun.PATH).send());
            for (RawHttp.Response response : responses) {
                response.assertDevNotFound();
                assertThat(response.bodyText() + response.headers().stream().map(h -> h[0] + ": " + h[1]).toList())
                        .as("nonce in a response").doesNotContain(nonce);
            }

            assertThat(linesWithTheNonce(app, nonce)).as("log events that hold the nonce (DEBUG on)").hasSize(1);

            ConfigurableEnvironment environment = app.context().getEnvironment();
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        assertThat(String.valueOf(enumerable.getProperty(name)))
                                .as("value of " + name + " in property source " + source.getName()).doesNotContain(nonce);
                        assertThat(name).doesNotContain(nonce);
                    }
                }
            }
            assertThat(System.getProperties().toString()).as("JVM system properties").doesNotContain(nonce);
        }
    }

    @Test
    void everyStartMakesANewNonce() {
        String first;
        try (AppRun one = AppRun.builder().start()) {
            first = one.nonceFromLog();
        }
        String second;
        try (AppRun two = AppRun.builder().start()) {
            second = two.nonceFromLog();
        }
        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(second).matches("[0-9a-f]{32}");
        assertThat(second).as("nonce of the second start").isNotEqualTo(first);
    }

    @Test
    void theNonceOfAnEarlierStartIsRefusedByALaterOne() throws IOException {
        String old;
        try (AppRun one = AppRun.builder().start()) {
            old = one.nonceFromLog();
        }
        try (AppRun two = AppRun.builder().start()) {
            two.devCall(old).send().assertDevNotFound();
        }
    }
}
