package com.sahaj.platform.it.devsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the human cleanup runbook ({@code docs/database/runbooks/dev-tools-sample-admin-cleanup.sql}) with a real
 * {@code psql} against {@code sahaj_test}, as {@code sahaj_migrator}, the way the runbook tells a human to (T19). The
 * password reaches the child process through its environment only. Refuses any database that is not {@code sahaj_test}.
 */
public final class PsqlRunner {

    private static final Pattern URL = Pattern.compile("jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+).*");

    public static final String NOT_FOUND = "PRECONDITION NOT MET: psql was not found on the PATH or under "
            + "C:\\Program Files\\PostgreSQL\\*\\bin; T19 is then the manual check M-3 in docs/test-plans/DEC-016.md";

    public record Outcome(int exitCode, String output) {
    }

    private PsqlRunner() {
    }

    /** The psql executable, or null when none is available to the tests. */
    public static Path findPsql() {
        List<Path> candidates = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir).resolve("psql.exe"));
                    candidates.add(Path.of(dir).resolve("psql"));
                }
            }
        }
        Path root = Path.of("C:\\Program Files\\PostgreSQL");
        if (Files.isDirectory(root)) {
            try (Stream<Path> versions = Files.list(root)) {
                versions.forEach(v -> candidates.add(v.resolve("bin").resolve("psql.exe")));
            } catch (IOException ex) {
                // fall through with what we have
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** The runbook, found by walking up from the working directory (the backend folder under Maven). */
    public static Path findScript() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; dir != null && depth < 6; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/database/runbooks/dev-tools-sample-admin-cleanup.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("docs/database/runbooks/dev-tools-sample-admin-cleanup.sql not found above "
                + Path.of("").toAbsolutePath());
    }

    public static Outcome runCleanup(Path psql, boolean onErrorStop) throws Exception {
        Matcher url = URL.matcher(SampleAdminDb.url());
        assertThat(url.matches()).as("SAHAJ_TEST_DB_URL shape").isTrue();
        assertThat(url.group(3)).as("database name").isEqualTo("sahaj_test");
        List<String> command = new ArrayList<>(List.of(psql.toString(), "-X", "-h", url.group(1),
                "-p", url.group(2) == null ? "5432" : url.group(2), "-U", "sahaj_migrator", "-d", url.group(3)));
        if (onErrorStop) {
            command.addAll(List.of("-v", "ON_ERROR_STOP=1"));
        }
        command.addAll(List.of("-f", findScript().toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PGPASSWORD", System.getenv("SAHAJ_DB_MIGRATOR_PASSWORD"));
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("psql finished").isTrue();
        return new Outcome(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
}
