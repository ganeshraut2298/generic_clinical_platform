package com.sahaj.platform.devtools.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sahaj.platform.it.devsample.BootstrapDb;
import com.sahaj.platform.it.devsample.PsqlRunner;
import com.sahaj.platform.it.devsample.SampleAdminDb;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SAH-42 design case S28: the human runbook {@code docs/database/runbooks/admin-bootstrap-clear-lock.sql}, run with a real
 * {@code psql} against {@code sahaj_test} as {@code sahaj_migrator} the way the runbook tells a human to (OQ-42-14). The password
 * reaches the child process through its environment only; the database name is checked to be {@code sahaj_test} before anything
 * runs.
 *
 * <p>Runs today, because they fail or stop before any change: the run without {@code ON_ERROR_STOP} (prints "Stopped" and quits),
 * the run in a database that is not {@code sahaj_dev} or {@code sahaj_test} (a copy of the script with the name replaced in memory,
 * written to a temporary file; it must refuse), and the run while the state table does not exist yet ("Nothing to clear"). The
 * case that clears a real lock and writes the audit row needs the bootstrap objects and skips until they exist.
 *
 * <p>The runbook is a human tool: it changes only the state row (back to a clean slate) and appends one audit row; it deletes
 * nothing. The tests that need it to change something re-create their own lock first and clean the audit row afterwards through
 * {@link SampleAdminDb#cleanup}.
 */
class BootstrapClearLockRunbookIT {

    private static final Pattern URL = Pattern.compile("jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+).*");

    private record Outcome(int exitCode, String output) {
    }

    private static Path psql() {
        Path psql = PsqlRunner.findPsql();
        assumeTrue(psql != null, PsqlRunner.NOT_FOUND);
        return psql;
    }

    private static Path script() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; dir != null && depth < 6; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/database/runbooks/admin-bootstrap-clear-lock.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("docs/database/runbooks/admin-bootstrap-clear-lock.sql not found above " + Path.of("").toAbsolutePath());
    }

    private static Outcome run(Path psql, Path file, boolean onErrorStop) throws Exception {
        Matcher url = URL.matcher(SampleAdminDb.url());
        assertThat(url.matches()).as("SAHAJ_TEST_DB_URL shape").isTrue();
        assertThat(url.group(3)).as("database name").isEqualTo("sahaj_test");
        List<String> command = new ArrayList<>(List.of(psql.toString(), "-X", "-h", url.group(1), "-p",
                url.group(2) == null ? "5432" : url.group(2), "-U", "sahaj_migrator", "-d", url.group(3)));
        if (onErrorStop) {
            command.addAll(List.of("-v", "ON_ERROR_STOP=1"));
        }
        command.addAll(List.of("-f", file.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PGPASSWORD", System.getenv("SAHAJ_DB_MIGRATOR_PASSWORD"));
        Process process = builder.start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("psql finished").isTrue();
        String text = new String(output, StandardCharsets.UTF_8);
        assertThat(text).as("the output never holds the password").doesNotContain(System.getenv("SAHAJ_DB_MIGRATOR_PASSWORD"));
        return new Outcome(process.exitValue(), text);
    }

    @Test
    void withoutOnErrorStopItPrintsStoppedAndChangesNothing() throws Exception {
        Path psql = psql();
        boolean objects = BootstrapDb.objectsPresent();
        BootstrapDb.State before = objects ? BootstrapDb.state() : null;

        Outcome outcome = run(psql, script(), false);

        assertThat(outcome.output()).contains("Stopped").contains("ON_ERROR_STOP=1");
        if (objects) {
            assertThat(BootstrapDb.state()).as("nothing changed").isEqualTo(before);
        }
    }

    @Test
    void inADatabaseThatIsNotDevOrTestItRefusesBeforeAnythingElse() throws Exception {
        Path psql = psql();
        String text = Files.readString(script(), StandardCharsets.UTF_8);
        assertThat(text).contains("current_database()");
        Path copy = Files.createTempFile("sah42-clear-lock-other-db", ".sql");
        try {
            Files.writeString(copy, text.replace("current_database()", "'sahaj_staging'"), StandardCharsets.UTF_8);
            Outcome outcome = run(psql, copy, true);
            assertThat(outcome.exitCode()).as("psql stops with an error").isNotZero();
            assertThat(outcome.output()).contains("Refused: database \"sahaj_staging\" is not a Sahaj dev or test database");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    @Test
    void whileTheStateTableDoesNotExistYetItStopsWithNothingToClear() throws Exception {
        Path psql = psql();
        assumeTrue(!BootstrapDb.tablePresent(),
                "The bootstrap state table exists, so the 'Nothing to clear' path cannot be reached.");
        Outcome outcome = run(psql, script(), true);
        assertThat(outcome.exitCode()).isNotZero();
        assertThat(outcome.output()).contains("Nothing to clear");
    }

    @Test
    void itClearsALockAndTheTicketWritesExactlyOneAuditRowAndASecondRunWritesNone() throws Exception {
        Path psql = psql();
        BootstrapDb.assumeObjects();
        BootstrapDb.failIfAnAdminExists();
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try {
            BootstrapDb.resetState();
            BootstrapDb.lockFor(Duration.ofMinutes(45));
            assertThat(BootstrapDb.state().lockedUntil()).isNotNull();

            Outcome first = run(psql, script(), true);

            assertThat(first.exitCode()).as(first.output()).isZero();
            BootstrapDb.State state = BootstrapDb.state();
            assertThat(state.failures()).isZero();
            assertThat(state.lockedUntil()).isNull();
            assertThat(state.hasTicket()).isFalse();
            List<String> rows = BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCK_CLEARED");
            assertThat(rows).as("one audit row").hasSize(1);
            assertThat(rows.get(0)).contains("\"kind\":\"AUTH\"").contains("\"outcome\":\"APPLIED\"")
                    .contains("Bootstrap registration lock cleared by hand").contains("\"ip\":null").contains("\"request_id\":null")
                    .contains("\"admin_id\":null");

            Outcome second = run(psql, script(), true);

            assertThat(second.exitCode()).as(second.output()).isZero();
            assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCK_CLEARED")).as("the second run leaves no trace").hasSize(1);

            // A count of 3 and no lock is also "something to clear": the count goes back to 0, one more row.
            BootstrapDb.setFailures(3);
            Outcome third = run(psql, script(), true);
            assertThat(third.exitCode()).as(third.output()).isZero();
            assertThat(BootstrapDb.state().failures()).isZero();
            assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCK_CLEARED")).hasSize(2);
        } finally {
            SampleAdminDb.cleanup(snapshot);
            BootstrapDb.resetState();
        }
    }

    /**
     * ADR-018 R3-I12 / design case S28 (Revision 3): the runbook also clears the lease. A count of 1 with a live lease and nothing
     * else is "something to clear"; afterwards both lease columns are null and a judge of the pending try raises SB004 (the app
     * answers a bodyless 500 and the try stays uncounted, which is what clearing by hand means).
     */
    @Test
    void r3I12ItClearsALiveLeaseTooAndThePendingJudgeThenGetsSB004() throws Exception {
        Path psql = psql();
        BootstrapDb.assumeObjects();
        BootstrapDb.failIfAnAdminExists();
        SampleAdminDb.Snapshot snapshot = SampleAdminDb.snapshot();
        try {
            BootstrapDb.resetState();
            java.util.UUID pending = java.util.UUID.randomUUID();
            BootstrapSql.claimAndCommit(pending); // count 1 and a live lease, no lock, no ticket
            BootstrapDb.State before = BootstrapDb.state();
            assertThat(before.failures()).isEqualTo(1);
            assertThat(before.tryId()).isEqualTo(pending);

            Outcome first = run(psql, script(), true);

            assertThat(first.exitCode()).as(first.output()).isZero();
            BootstrapDb.State state = BootstrapDb.state();
            assertThat(state.failures()).isZero();
            assertThat(state.tryId()).as("the lease is cleared").isNull();
            assertThat(state.tryExpiresAt()).isNull();
            assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCK_CLEARED")).as("one audit row").hasSize(1);
            try (java.sql.Connection c = SampleAdminDb.app()) {
                c.setAutoCommit(false);
                try {
                    BootstrapSql.unlockFailed(c, pending);
                    throw new AssertionError("the pending judge must be refused");
                } catch (java.sql.SQLException ex) {
                    assertThat(ex.getSQLState()).as("the judge of the cleared try: " + ex.getMessage()).isEqualTo("SB004");
                } finally {
                    c.rollback();
                }
            }
            assertThat(BootstrapDb.state().failures()).as("the pending try stays uncounted").isZero();

            Outcome second = run(psql, script(), true);
            assertThat(second.exitCode()).as(second.output()).isZero();
            assertThat(BootstrapDb.auditRows(snapshot, "ADMIN_BOOTSTRAP_LOCK_CLEARED")).as("the second run leaves no trace").hasSize(1);
        } finally {
            SampleAdminDb.cleanup(snapshot);
            BootstrapDb.resetState();
        }
    }
}
