package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * DEC-016 / ADR-008 test T11 (forced 500) and T11+ (R4): when the database call fails, the answer is a bodyless 500 and
 * neither the logs nor the response hold the email, a password hash, a ciphertext or the words "Failing row", even with
 * {@code org.springframework.web}, {@code org.springframework.security}, {@code org.springframework.jdbc}, Hikari and the
 * root logger at DEBUG (the level at which Spring prints "Resolved [exception]" and "Writing [...]").
 *
 * <p>How the failure is forced, and why it works whatever the state of the database: the application's connection pool
 * is started with {@code connection-init-sql = SET default_transaction_read_only = on}. Every transaction of the
 * application is then read-only, so the database refuses the function call: with the dev-tools function missing (today)
 * as "function does not exist" (SQLSTATE 42883); with it present as "cannot execute INSERT in a read-only transaction"
 * (25006). Both reach the controller as a database failure, are wrapped in the message-free exception, and nothing is
 * written. A failing row (constraint violation) cannot be produced through the endpoint; the unit tests
 * {@code JdbcDevSampleAdminRepositoryTest} and {@code DevSampleAdminEndpointTest} cover an exception whose message and cause
 * carry the email.
 */
class DevSampleAdminFailureLogsIT {

    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}");
    private static final Pattern CIPHERTEXT_HEX = Pattern.compile("[0-9a-fA-F]{96}");

    @Test
    void aForcedDatabaseFailureAnswersABodylessFiveHundredAndLeaksNothingAtDebugLevel() throws IOException {
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try (AppRun app = AppRun.builder()
                .option("spring.datasource.hikari.connection-init-sql", "SET default_transaction_read_only = on")
                .debug("root", "org.springframework.web", "org.springframework.web.servlet", "org.springframework.security",
                        "org.springframework.jdbc", "org.springframework.transaction", "com.zaxxer.hikari", "org.postgresql")
                .start()) {
            String nonce = app.nonceFromLog();
            assertThat(nonce).matches("[0-9a-f]{32}");

            RawHttp.Response response = app.devCall(nonce).send();

            assertThat(response.status()).as("forced failure").isEqualTo(500);
            assertThat(response.body()).as("bodyless 500 (contract DevServerError)").isEmpty();
            assertThat(response.hasHeader("Content-Type")).isFalse();
            assertThat(response.header("Cache-Control")).isEqualTo("no-store");

            String logs = app.logText();
            assertThat(logs).as("the failure was logged by our controller (the path under test ran)")
                    .contains("The sample admin could not be created (DevSampleAdminFailedException");
            assertThat(logs).doesNotContain("sample-admin@example.test").doesNotContain("@example.test")
                    .doesNotContain("Failing row").doesNotContain("secret=").doesNotContain("otpauth://")
                    .doesNotContain("Sample admin");
            assertThat(BCRYPT.matcher(logs).find()).as("a BCrypt hash in the logs").isFalse();
            assertThat(CIPHERTEXT_HEX.matcher(logs).find()).as("a 48-byte ciphertext as hex in the logs").isFalse();
            assertThat(logs).as("the nonce appears only in its start-up line").contains(nonce);
            assertThat(logs.split(nonce, -1).length - 1).as("occurrences of the nonce").isEqualTo(1);
            assertThat(response.bodyText() + response.headers().stream().map(h -> h[0] + h[1]).toList())
                    .doesNotContain("@example.test").doesNotContain(nonce);
        } finally {
            SampleAdminDb.cleanup(snapshot);
        }
        assertThat(SampleAdminDb.adminCount()).as("nothing written by the failed call").isEqualTo(snapshot.adminsBefore());
    }
}
