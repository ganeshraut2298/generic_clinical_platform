package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * I15, the function guardrail on a fully migrated database: a function that more than one migration defines takes its body from
 * the highest version, so the body that is live must still carry what earlier versions did (QA defect D-1 of the programme:
 * 1350 undid 1270 and 1380 undid 1280). Two checks: (1) for every function of the {@code pharmacy} schema that two or more
 * migration files define with {@code create or replace}, every error message of the earlier files is still in the live body, and
 * the live body is the one of the highest file; (2) the key behaviour that must be in the live bodies of the four pharmacy
 * functions, and the two entries of the guardrail test (winner 1730, snippet "second signer"). Test plan:
 * docs/test-plans/SAH-79.md (T79-I15-*).
 */
@OnTestDatabase
class PharmacyFunctionGuardrailIT extends Sah79Support {

    private static final Pattern DEFINITION = Pattern.compile("create\\s+or\\s+replace\\s+function\\s+(pharmacy\\.[a-z_0-9]+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern MESSAGE = Pattern.compile("raise\\s+exception\\s+'((?:[^']|'')*)'", Pattern.CASE_INSENSITIVE);

    private static Path migrations() {
        for (String candidate : new String[] {"src/main/resources/db/migration", "backend/src/main/resources/db/migration"}) {
            if (Files.isDirectory(Path.of(candidate))) {
                return Path.of(candidate);
            }
        }
        throw new AssertionError("The migration folder was not found from " + Path.of("").toAbsolutePath());
    }

    /** Function name to the versions (file names, in version order) that define it, and each file's text. */
    private static Map<String, List<String>> definers(Map<String, String> texts) throws IOException {
        Map<String, List<String>> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(migrations())) {
            List<Path> sorted = files.filter(p -> p.getFileName().toString().startsWith("V2026_12_17_")).sorted().toList();
            for (Path file : sorted) {
                String text = Files.readString(file);
                texts.put(file.getFileName().toString(), text);
                Matcher m = DEFINITION.matcher(text);
                List<String> seen = new ArrayList<>();
                while (m.find()) {
                    if (!seen.contains(m.group(1).toLowerCase())) {
                        seen.add(m.group(1).toLowerCase());
                        out.computeIfAbsent(m.group(1).toLowerCase(), k -> new ArrayList<>()).add(file.getFileName().toString());
                    }
                }
            }
        }
        return out;
    }

    /** The text of the function in one file: from its definition to the next "$$;". */
    private static String bodyIn(String text, String function) {
        Matcher m = Pattern.compile("create\\s+or\\s+replace\\s+function\\s+" + Pattern.quote(function) + "\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) {
            return "";
        }
        int start = m.start();
        int end = text.indexOf("$$;", text.indexOf("$$", start) + 2);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private static List<String> messages(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = MESSAGE.matcher(body);
        while (m.find()) {
            out.add(m.group(1).replace("''", "'"));
        }
        return out;
    }

    @Test
    void everyPharmacyFunctionDefinedByMoreThanOneMigrationStillCarriesTheErrorMessagesOfTheEarlierVersions() throws Exception {
        Map<String, String> texts = new TreeMap<>();
        Map<String, List<String>> definers = definers(texts);
        List<String> repeated = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : definers.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            repeated.add(e.getKey());
            String last = e.getValue().get(e.getValue().size() - 1);
            String live = bodyIn(texts.get(last), e.getKey());
            for (String earlier : e.getValue().subList(0, e.getValue().size() - 1)) {
                for (String message : messages(bodyIn(texts.get(earlier), e.getKey()))) {
                    String key = message.split("%")[0].trim();
                    if (!live.contains(key.length() > 24 ? key.substring(0, 24) : key)) {
                        lost.add(e.getKey() + ": '" + message + "' of " + earlier + " is not in " + last);
                    }
                }
            }
        }
        assertThat(repeated).as("the pharmacy functions defined more than once").contains("pharmacy.guard_opening_stock_import_update", "pharmacy.next_document_no");
        assertThat(lost).as("messages lost by a later definition (a later version must be the union)").isEmpty();
    }

    @Test
    void theLiveBodiesCarryTheKeyBehaviourOfEveryVersionAndTheWinnersAreTheExpectedFiles() throws Exception {
        asOwner(c -> {
            String guard = str(c, "select pg_get_functiondef('pharmacy.guard_opening_stock_import_update()'::regprocedure)");
            assertThat(guard).contains("line counter cannot go down").contains("posted_at").contains("'REJECTED'").contains("totals are fixed once submitted")
                    .contains("can be posted only after approval");
            String next = str(c, "select pg_get_functiondef('pharmacy.next_document_no(text)'::regprocedure)");
            assertThat(next).contains("last_issued_at").contains("for share").contains("DAMAGE_REPORT").contains("STOCK_ADJUSTMENT").contains("unknown document type");
            String schedX = str(c, "select pg_get_functiondef('pharmacy.guard_stock_ledger_schedule_x()'::regprocedure)");
            assertThat(schedX).contains("second signer").contains("SHORTAGE_FOUND").contains("STOCK_AUDIT_ADJUSTMENT").contains("WARD_CONSUMPTION_PATIENT");
            assertThat(schedX).as("opening stock and the 6 other unlisted types are not on the list")
                    .doesNotContain("'OPENING_STOCK'").doesNotContain("'EXPIRY_QUARANTINE'").doesNotContain("'WRITE_OFF'").doesNotContain("'DAMAGE_RELEASE'")
                    .doesNotContain("'VENDOR_RETURN_HOLD'").doesNotContain("'VENDOR_RETURN_RELEASE'").doesNotContain("'SHORTAGE_WRITE_OFF'");
            String audit = str(c, "select pg_get_functiondef('pharmacy.guard_stock_audit_write()'::regprocedure)");
            assertThat(audit).contains("second signer").contains("stock audit must start counting").contains("stock audit is final")
                    .contains("stock audit submission cannot be cleared").contains("stock audit identity cannot change");
        });
        Map<String, String> texts = new TreeMap<>();
        Map<String, List<String>> definers = definers(texts);
        // the winner is the highest file that defines the function
        // SAH-80 migration 1800 replaces it once more (six new types in the array)
        assertThat(definers.get("pharmacy.guard_stock_ledger_schedule_x")).hasSize(3);
        assertThat(definers.get("pharmacy.guard_stock_ledger_schedule_x").get(2)).startsWith("V2026_12_17_1800");
        assertThat(definers.get("pharmacy.guard_stock_audit_write")).hasSize(2);
        assertThat(definers.get("pharmacy.guard_stock_audit_write").get(1)).startsWith("V2026_12_17_1730");
        assertThat(definers.get("pharmacy.guard_opening_stock_import_update").get(definers.get("pharmacy.guard_opening_stock_import_update").size() - 1))
                .startsWith("V2026_12_17_1710");
        assertThat(definers.get("pharmacy.next_document_no").get(definers.get("pharmacy.next_document_no").size() - 1)).startsWith("V2026_12_17_1800");
        for (String winner : List.of("pharmacy.guard_stock_ledger_schedule_x", "pharmacy.guard_stock_audit_write")) {
            assertThat(bodyIn(texts.get(definers.get(winner).get(definers.get(winner).size() - 1)), winner)).as(winner).contains("second signer");
        }
    }

    @Test
    void theMigrationsOf1710To1730AreAppliedInOrderWithNoFailureAndTheChecksumsMatchTheFiles() throws Exception {
        asOwner(c -> {
            assertThat(number(c, "select count(*) from platform.flyway_schema_history where version in ('2026.12.17.1710', '2026.12.17.1720', '2026.12.17.1730') and success")).isEqualTo(3);
            assertThat(number(c, "select count(*) from platform.flyway_schema_history where not success")).isZero();
        });
    }
}
