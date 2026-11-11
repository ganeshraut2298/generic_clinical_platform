package com.sahaj.platform.devtools.it;

import static com.sahaj.platform.devtools.it.BootstrapSupport.NAME;
import static com.sahaj.platform.devtools.it.BootstrapSupport.PASSWORD;
import static com.sahaj.platform.devtools.it.BootstrapSupport.PIN;
import static com.sahaj.platform.devtools.it.BootstrapSupport.json;
import static com.sahaj.platform.devtools.it.BootstrapSupport.unlock;
import static com.sahaj.platform.devtools.it.BootstrapSupport.wrongPin;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.devtools.it.BootstrapSupport.Ticket;
import com.sahaj.platform.it.devsample.AppRun;
import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.RawHttp;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SAH-42 ADR-018 D8 and story 6: what the logs may and may not say, on the whole application with {@code DEBUG} on for the
 * web, security and JDBC loggers and the real bootstrap functions in {@code sahaj_test}.
 *
 * <ul>
 *   <li>the lock is logged ONCE per lock, at WARN, after the commit of the 5th wrong PIN, with the end time (ISO 8601 UTC, the
 *       value of the audit row) and the request id; a refusal during a lock writes no line (a local loop must not fill the log);
 *   <li>the allowed lines carry no value: wrong PIN "(n of 5)", "admin created adminId=...";
 *   <li>no PIN (right or wrong), hash, ticket, binding, password, e-mail, name, pre-auth token, signing secret or authenticator
 *       text appears anywhere in the logs of a whole run, and no audit row holds any of them.
 * </ul>
 *
 * <p>Each test starts its own application so the log list holds only its own run. Same preconditions and skip behaviour as
 * {@link BootstrapUnlockIT}.
 */
class BootstrapLogsIT extends BootstrapHttpBase {

    private static final Pattern LOCK_LINE = Pattern.compile("WARN \\S+ - Bootstrap registration locked until (\\S+)");

    private AppRun loggingApp() {
        return BootstrapSupport.builder().debug("root", "org.springframework.web", "org.springframework.web.servlet",
                "org.springframework.security", "org.springframework.jdbc", "com.zaxxer.hikari").start();
    }

    private static List<String> lockLines(AppRun run) {
        return run.logLines().stream().filter(l -> l.contains("Bootstrap registration locked until")).toList();
    }

    @Test
    void theLockIsLoggedOnceAtWarnWithTheEndTimeAndTheRequestIdAndRefusalsDuringTheLockWriteNoLine() throws Exception {
        try (AppRun run = loggingApp()) {
            for (int n = 1; n <= 4; n++) {
                assertThat(unlock(run, wrongPin(n)).status()).isEqualTo(401);
            }
            assertThat(lockLines(run)).as("no lock line before the 5th wrong PIN").isEmpty();

            assertThat(unlock(run, wrongPin(5)).status()).isEqualTo(423);

            List<String> lines = lockLines(run);
            assertThat(lines).as("exactly one lock line").hasSize(1);
            Matcher matcher = LOCK_LINE.matcher(lines.get(0));
            assertThat(matcher.find()).as("a WARN line: " + lines.get(0)).isTrue();
            Instant logged = Instant.parse(matcher.group(1));
            assertThat(lines.get(0)).as("ISO 8601 UTC").contains(matcher.group(1)).contains("Z");
            assertThat(lines.get(0)).as("the request id from the MDC").contains("requestId=");
            assertThat(Duration.between(logged, BootstrapDb.state().lockedUntil()).abs().toSeconds())
                    .as("the logged end time is the stored end time").isLessThanOrEqualTo(1);
            String audited = json(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCKED").get(0)).get("after_json").get("lockedUntil").asText();
            assertThat(Duration.between(Instant.parse(audited), logged).abs().toSeconds()).as("same value as the audit row")
                    .isLessThanOrEqualTo(1);

            // Refusals during the lock: no new line at any level that mentions the lock or the PIN.
            int linesBefore = run.logLines().stream().filter(l -> l.contains("Bootstrap registration")).toList().size();
            unlock(run, PIN);
            unlock(run, wrongPin(9));
            BootstrapSupport.registerRequest(run, BootstrapSupport.registerBody("A".repeat(43), NAME, "x@example.test", PASSWORD),
                    "B".repeat(43)).send();
            assertThat(lockLines(run)).as("still exactly one lock line").hasSize(1);
            assertThat(run.logLines().stream().filter(l -> l.contains("Bootstrap registration")).toList())
                    .as("a refusal during a lock writes no bootstrap line").hasSize(linesBefore);
        }
    }

    @Test
    void aSecondLockWritesASecondLineWithItsOwnEndTimeAndTheWrongPinLinesCarryOnlyTheNumber() throws Exception {
        try (AppRun run = loggingApp()) {
            for (int n = 1; n <= 5; n++) {
                unlock(run, wrongPin(n));
            }
            BootstrapDb.lockEndedAgo(Duration.ofSeconds(1));
            for (int n = 6; n <= 10; n++) {
                unlock(run, wrongPin(n));
            }
            assertThat(lockLines(run)).as("two locks, two lines").hasSize(2);
            List<String> wrongLines = run.logLines().stream().filter(l -> l.contains("Bootstrap registration: wrong PIN")).toList();
            assertThat(wrongLines).as("four INFO lines per lock cycle, the 5th is the lock line").hasSize(8);
            for (int i = 0; i < wrongLines.size(); i++) {
                assertThat(wrongLines.get(i)).startsWith("INFO ").contains("wrong PIN (" + (i % 4 + 1) + " of 5)")
                        .doesNotContain("qa-wrong");
            }
        }
    }

    @Test
    void noPinHashTicketPasswordEmailNameOrSecretAppearsInTheLogsOfAWholeRunAtDebugOrInAnyAuditRow() throws Exception {
        String email = uniqueEmail();
        String hashOfPassword;
        String preAuth;
        Ticket ticket;
        try (AppRun run = loggingApp()) {
            unlock(run, wrongPin(1));
            unlock(run, "");
            BootstrapSupport.unlockRequest(run, "{\"pin\":123,\"zzUnknown\":\"zzValue\"}").send();
            ticket = BootstrapSupport.newTicket(run);
            BootstrapSupport.register(run, ticket, NAME, email, "short");
            RawHttp.Response created = BootstrapSupport.register(run, ticket, NAME, email, PASSWORD);
            assertThat(created.status()).isEqualTo(201);
            preAuth = json(created).get("preAuthToken").asText();
            RawHttp.Response enrol = run.post("/api/v1/admin/auth/totp/enrol").json("{\"preAuthToken\":\"" + preAuth + "\"}").send();
            assertThat(enrol.status()).isEqualTo(200);
            String secretKey = json(enrol).get("secretKey").asText();
            String otpauth = json(enrol).get("otpauthUri").asText();
            // A failing call too, so the error paths are in the log.
            unlock(run, PIN);

            try (Connection c = SampleAdminDb.migrator(); PreparedStatement s = c.prepareStatement(
                    "select password_hash from platform.platform_admin where email = ?")) {
                s.setString(1, email);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    hashOfPassword = rs.getString(1);
                }
            }
            String logs = run.logText();
            List<String> forbidden = new ArrayList<>(List.of(PIN, wrongPin(1), BootstrapSupport.pinHash(), BootstrapSupport.pinHash().substring(7, 27),
                    ticket.value(), ticket.binding(), BootstrapSupport.atRestHash(ticket.value()), BootstrapSupport.atRestHash(ticket.binding()),
                    PASSWORD, hashOfPassword, hashOfPassword.substring(7, 27), email, NAME, preAuth, secretKey,
                    secretKey.replace(" ", ""), otpauth, "otpauth://",
                    "zzUnknown", "zzValue"));
            for (String value : forbidden) {
                assertThat(logs).as("logs at DEBUG must not contain a secret or typed value").doesNotContain(value);
            }
            assertThat(logs).as("the created line names the id only").contains("Bootstrap registration: admin created adminId=");
            for (String row : SampleAdminDb.auditRowsSince(snapshot)) {
                for (String value : forbidden) {
                    assertThat(row).as("audit rows must not contain a secret or typed value").doesNotContain(value);
                }
            }
        }
    }

    @Test
    void aFailedJudgeIsLoggedWithoutTheTryIdThePinOrTheDatabaseMessageAndAFailedFifthJudgeWritesNoLockLine() throws Exception {
        // ADR-018 D7/D8 and R3-U2: a judge that fails after the claim answers a bodyless 500; the lock WARN line is written only
        // after the judge's commit, so a failed 5th judge has none; the try id (a random value that is never a secret but is
        // never audited or logged either) and the typed PIN appear in no log line and no audit row.
        List<UUID> tryIds = new ArrayList<>();
        try (AppRun run = loggingApp()) {
            BootstrapDb.failAuditInsertsOf("ADMIN_BOOTSTRAP_UNLOCK_FAILED");
            try {
                for (int n = 1; n <= 5; n++) {
                    BootstrapDb.expireLease();
                    assertThat(unlock(run, wrongPin(n)).status()).as("failed judge " + n).isEqualTo(500);
                    tryIds.add(BootstrapDb.state().tryId());
                }
            } finally {
                BootstrapDb.dropForcedFailures();
            }
            String logs = run.logText();
            assertThat(lockLines(run)).as("no lock line when the judge of the 5th try failed").isEmpty();
            for (UUID id : tryIds) {
                assertThat(id).isNotNull();
                assertThat(logs).as("the try id is never logged").doesNotContain(id.toString());
            }
            for (int n = 1; n <= 5; n++) {
                assertThat(logs).as("the typed PIN is never logged").doesNotContain(wrongPin(n));
            }
            assertThat(logs).as("the database message of the forced failure is not logged (class and SQLSTATE only)")
                    .doesNotContain("qa forced failure");
            // Move the last lease out and let the next begin write the UNJUDGED row, then read every audit row.
            BootstrapDb.expireLease();
            assertThat(unlock(run, PIN).status()).as("locked after five counted tries").isEqualTo(423);
            for (String row : SampleAdminDb.auditRowsSince(snapshot)) {
                for (UUID id : tryIds) {
                    assertThat(row).as("an audit row never holds a try id").doesNotContain(id.toString());
                }
                assertThat(row).doesNotContain(PIN);
            }
        }
    }

    @Test
    void theStartupLineIsOnAndItNeverPrintsTheHashItsLengthOrItsPrefix() throws Exception {
        try (AppRun run = loggingApp()) {
            List<String> lines = run.logLines().stream().filter(l -> l.contains("Admin bootstrap registration is ")).toList();
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0)).startsWith("WARN ").contains("is on (dev-tools build)")
                    .doesNotContain("$2").doesNotContain("60").doesNotContain(BootstrapSupport.pinHash().substring(0, 7));
        }
    }
}
