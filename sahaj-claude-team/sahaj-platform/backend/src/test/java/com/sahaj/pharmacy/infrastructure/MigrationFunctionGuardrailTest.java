package com.sahaj.pharmacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Code review m-1, security I15 (DEC-080): Flyway runs the migrations in version order, so a function that several files
 * {@code create or replace} takes its body from the last one. A later file written from an older copy silently undoes an
 * earlier fix (QA defect D-1: 1350 undid 1270, 1380 undid 1280; fixed forward by 1710). This test scans
 * {@code db/migration} for every function defined in more than one file. Each such function must have an entry in
 * {@link #KNOWN} that names the file of the winning body and a snippet the winning body must contain. A new function
 * defined twice without an entry fails the test: copy the body from the latest migration that defines it, add what you
 * change, and add the entry (with a snippet that proves the earlier fix is still there).
 *
 * <p>An entry names the winner as a file-name prefix and means "this file or a later one": a forward fix that redefines a
 * function again is accepted as long as its body still holds the snippet. The snippet is compared ignoring case.
 */
class MigrationFunctionGuardrailTest {

    /** A function body, one migration. */
    private record Definition(String file, String body) {
    }

    /** What the winning body must contain. */
    private record Known(String winnerFilePrefix, List<String> snippets) {

        Known(String winnerFilePrefix, String snippet) {
            this(winnerFilePrefix, List.of(snippet));
        }

        Known(String winnerFilePrefix, String first, String second) {
            this(winnerFilePrefix, List.of(first, second));
        }
    }

    /**
     * Functions defined in more than one migration. The pharmacy rows are the defect D-1 pair (winner 1710). The ipd, opd and
     * platform rows are those of docs/database/SAH-78-pharmacy-masters.md section 11 (winner = the last version, audited by
     * diff there).
     */
    private static final Map<String, Known> KNOWN = Map.ofEntries(
            Map.entry("pharmacy.guard_opening_stock_import_update",
                    new Known("V2026_12_17_1710", "line counter cannot go down")),
            // 1280 last_issued_at and 1720 the unit row FOR SHARE (L11) must both be in the winning body
            Map.entry("pharmacy.next_document_no", new Known("V2026_12_17_1720", "last_issued_at", "for share")),
            // 1730: the final person-driven list and the stock audit signer rules (L14, ADR-029 P7)
            Map.entry("pharmacy.guard_stock_ledger_schedule_x", new Known("V2026_12_17_1730", "second signer")),
            Map.entry("pharmacy.guard_stock_audit_write", new Known("V2026_12_17_1730", "second signer")),
            // 1740 (round 5): each keeps the whole earlier body and adds one move (short-supply-only debit note, approved receipt discard)
            Map.entry("pharmacy.guard_debit_note_write",
                    new Known("V2026_12_17_1740", "debit note picking and hand-over data are set once")),
            Map.entry("pharmacy.guard_debit_note_credit_insert",
                    new Known("V2026_12_17_1740", "credit note can be recorded only after hand-over")),
            Map.entry("pharmacy.guard_goods_receipt_write",
                    new Known("V2026_12_17_1740", "goods receipt cannot be edited once it has been sent for approval")),
            Map.entry("ipd.guard_dose_administration_insert",
                    new Known("V2026_12_16_1000", "dose of an active order is not before verification")),
            Map.entry("ipd.guard_emergency_case_update",
                    new Known("V2026_12_12_1110", "emergency case cannot go back to waiting")),
            Map.entry("ipd.guard_ews_escalation_update",
                    new Known("V2026_11_26_1000", "early warning escalation risk cannot go down")),
            Map.entry("ipd.guard_medicine_order_insert",
                    new Known("V2026_12_15_1000", "medicine order must start at version 1")),
            Map.entry("ipd.guard_medicine_order_update",
                    new Known("V2026_12_15_1000", "medicine order version must go up by exactly one with every update")),
            Map.entry("ipd.guard_medicine_order_query_insert",
                    new Known("V2026_12_16_1000", "cannot be asked by the person who ordered or changed it")),
            Map.entry("ipd.guard_medicine_order_version_update",
                    new Known("V2026_12_16_1000", "medicine order version is already verified")),
            Map.entry("opd.deny_chemo_order_change",
                    new Known("V2026_12_12_1010", "opd.chemo_order of a signed emr record is immutable")),
            Map.entry("platform.deny_audit_mutation", new Known("V2026_11_07_1000", "is append-only")),
            Map.entry("platform.guard_staff_pin_update",
                    new Known("V2026_12_03_1000", "staff pin pepper version cannot go down")));

    private static final Pattern DEFINITION = Pattern.compile(
            "create\\s+or\\s+replace\\s+function\\s+([a-z0-9_.\"]+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOLLAR_BODY = Pattern.compile("\\$([a-z_0-9]*)\\$(.*?)\\$\\1\\$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void everyFunctionDefinedInMoreThanOneMigrationIsOnTheListAndItsWinningBodyKeepsTheEarlierFix() throws IOException {
        Map<String, String> files = migrations();
        assertThat(files).as("migration files read").hasSizeGreaterThan(50);

        assertThat(problems(files, KNOWN)).isEmpty();
    }

    @Test
    void theListHasTheFourteenFunctionsOfTheDesignNotesAndTheThreeOfMigration1740() {
        assertThat(KNOWN).hasSize(17);
    }

    @Test
    void aNewFunctionDefinedTwiceWithoutAnEntryFailsWithTheCopyTheBodyMessage() {
        Map<String, String> files = new TreeMap<>();
        files.put("V2030_01_01_0900__a.sql", fn("pharmacy.new_function", "first body"));
        files.put("V2030_01_02_0900__b.sql", fn("pharmacy.new_function", "second body"));

        List<String> found = problems(files, Map.of());

        assertThat(found).hasSize(1);
        assertThat(found.get(0)).contains("pharmacy.new_function").contains("V2030_01_01_0900__a.sql")
                .contains("V2030_01_02_0900__b.sql")
                .contains("copy the body from the latest migration that defines it");
    }

    @Test
    void aFunctionDefinedOnceNeedsNoEntry() {
        Map<String, String> files = new TreeMap<>();
        files.put("V2030_01_01_0900__a.sql", fn("pharmacy.only_once", "body") + fn("pharmacy.other", "body"));

        assertThat(problems(files, Map.of())).isEmpty();
    }

    @Test
    void aLaterDefinitionThatLosesTheSnippetFailsAndOneThatKeepsItPasses() {
        Map<String, Known> known = Map.of("pharmacy.f", new Known("V2030_01_01_0900", "keep this line"));
        Map<String, String> files = new TreeMap<>();
        files.put("V2030_01_01_0900__a.sql", fn("pharmacy.f", "x; -- keep this line"));
        files.put("V2030_01_02_0900__b.sql", fn("pharmacy.f", "x"));

        List<String> found = problems(files, known);
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).contains("V2030_01_02_0900__b.sql").contains("keep this line")
                .contains("copy the body from the latest migration that defines it");

        files.put("V2030_01_02_0900__b.sql", fn("pharmacy.f", "x; -- KEEP THIS LINE; y"));
        assertThat(problems(files, known)).isEmpty();
    }

    @Test
    void anEntryThatNamesALaterWinnerThanTheLastFileFailsAndAMissingFunctionFails() {
        Map<String, Known> known = Map.of("pharmacy.f", new Known("V2030_02_01_0900", "x"),
                "pharmacy.gone", new Known("V2030_01_01_0900", "x"));
        Map<String, String> files = new TreeMap<>();
        files.put("V2030_01_01_0900__a.sql", fn("pharmacy.f", "x"));
        files.put("V2030_01_02_0900__b.sql", fn("pharmacy.f", "x"));

        List<String> found = problems(files, known);

        assertThat(found).hasSize(2);
        assertThat(String.join("\n", found)).contains("names V2030_02_01_0900").contains("pharmacy.gone");
    }

    // ---- the scan ----

    private static String fn(String name, String body) {
        return "create or replace function " + name + "()\nreturns trigger language plpgsql as $$\nbegin\n" + body
                + "\nend;\n$$;\n";
    }

    /** Every problem found, as a message; empty when the migrations are in order. */
    private static List<String> problems(Map<String, String> files, Map<String, Known> known) {
        Map<String, List<Definition>> byFunction = new TreeMap<>();
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Map<String, Definition> inFile = new TreeMap<>();
            Matcher m = DEFINITION.matcher(file.getValue());
            while (m.find()) {
                String name = m.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
                Matcher body = DOLLAR_BODY.matcher(file.getValue());
                String text = body.find(m.end()) ? body.group(2) : "";
                inFile.put(name, new Definition(file.getKey(), text.toLowerCase(Locale.ROOT)));
            }
            inFile.forEach((name, definition) -> byFunction.computeIfAbsent(name, k -> new ArrayList<>()).add(definition));
        }
        List<String> problems = new ArrayList<>();
        byFunction.forEach((name, definitions) -> {
            if (definitions.size() < 2) {
                return;
            }
            Definition last = definitions.get(definitions.size() - 1);
            String where = definitions.stream().map(Definition::file).toList().toString();
            Known entry = known.get(name);
            if (entry == null) {
                problems.add(name + " is defined in more than one migration " + where + " and is not on the list: copy the body "
                        + "from the latest migration that defines it (" + last.file() + "), change only what you mean to "
                        + "change, and add an entry with the winning file and a snippet the body must contain");
                return;
            }
            if (last.file().compareTo(entry.winnerFilePrefix()) < 0) {
                problems.add(name + ": the entry names " + entry.winnerFilePrefix() + " but the last definition is in "
                        + last.file() + " " + where);
            }
            for (String snippet : entry.snippets()) {
                if (!last.body().contains(snippet.toLowerCase(Locale.ROOT))) {
                    problems.add(name + ": the winning body in " + last.file() + " does not contain \"" + snippet
                            + "\": copy the body from the latest migration that defines it, then add your change " + where);
                }
            }
        });
        known.forEach((name, entry) -> {
            if (!byFunction.containsKey(name)) {
                problems.add(name + " is on the list but no migration defines it");
            }
        });
        return problems;
    }

    private static Map<String, String> migrations() throws IOException {
        Path dir = Path.of("src", "main", "resources", "db", "migration");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("backend").resolve(dir);
        }
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(p -> p.getFileName().toString().matches("V.*\\.sql")).toList()) {
                files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
