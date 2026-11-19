package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SAH-76 contract 0.4.4 (DEC-065 answer A, ADR-027 A9 point 8) on the real {@code sahaj_test} database: a scan of a medicine whose
 * drug has no code loaded.
 * <ul>
 *   <li>(a) a code of the ordered drug records SCAN;</li>
 *   <li>(b) a code of another drug (known to the drug master) is 409 medicine-mismatch with the stop row, the deny entry, the mismatch
 *       audit and the 15-minute rule, also while the ordered drug has no code;</li>
 *   <li>(c) an unknown code while the ordered drug has no code is 409 no-code-loaded: no stop row, no deny entry, no mismatch audit,
 *       exactly one IPD_DOSE_REFUSED with fixed keys, the scanned text nowhere, and the same nurse's override is accepted at once;</li>
 *   <li>(d) an unknown code while the ordered drug has codes is 409 medicine-mismatch.</li>
 * </ul>
 * Every case runs on both server paths: a normal order (the first transaction decides, then the locked one records) and a
 * high-alert order (the second nurse's PIN is checked between the two transactions). A refusal comes from the first transaction, so
 * the high-alert requests that are refused carry a wrong PIN on purpose: it must never be tried. Test plan:
 * docs/test-plans/IPD-go-live.md (section "Round after reviews 2", tests N-01 to N-10).
 */
@OnTestDatabase
@WithPinPepper
class Sah76NoCodeLoadedIT extends Sah76NursingSupport {

    private static final String NO_CODE_TITLE = "No code loaded";
    private static final String NO_CODE_DETAIL = "No code is loaded for this medicine. Use Manual override: Medicine has no barcode.";
    private static final String AFTER_MISMATCH = "A scan for this dose did not match in the last 15 minutes. "
            + "Check the patient and the medicine, then scan the medicine code.";
    private static final String NOTE = "Scanner is down, read the label";

    /** One ward with one ACTIVE once-a-day order (8 AM) of a normal or a high-alert medicine; the clock reads 8:05 AM IST. */
    private record Path(Ward w, String order, String drug, boolean high) {
    }

    private Path path(boolean high) throws Exception {
        Ward w = ward();
        String drug = high ? "hep" : "pcm";
        String order = high ? activeScheduled(w, "hep", "od", "IU", "SC") : activeScheduled(w, "pcm", "od", "TABLET", "ORAL");
        clockAt(8, 5);
        assertThat(count(w.clinic(), "select count(*) from ipd.drug_code where drug_key = ?", drug))
                .as("precondition: the shipped drug master has no code of " + drug).isZero();
        return new Path(w, order, drug, high);
    }

    /** Adds the second nurse (a high-alert medicine); a valid PIN, or a wrong one for a request that must be refused before the PIN. */
    private Map<String, Object> second(Path p, Map<String, Object> body, boolean validPin) {
        if (p.high()) {
            body.put("secondNurseId", p.w().nurse2().membershipId().toString());
            body.put("secondNursePin", validPin ? p.w().nurse2Pin() : otherPin(p.w().nurse2(), p.w().nurse2Pin()));
        }
        return body;
    }

    private Map<String, Object> scanBody(Path p, String code, boolean validPin) {
        return second(p, scanGiven(p.w(), ist(8, 0), 1, code), validPin);
    }

    private Map<String, Object> overrideBody(Path p, boolean validPin) {
        return second(p, overrideGiven(p.w(), ist(8, 0), 1, "NO_BARCODE", NOTE), validPin);
    }

    private static String unknownCode() {
        return "UNKN" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private long stops(Path p) throws SQLException {
        return count(p.w().clinic(), "select count(*) from ipd.dose_scan_stop where order_id = ?::uuid", p.order());
    }

    /** Everything a dose request may write, as lower-case text: audit rows, clinical events, dose rows and scan-stop rows. */
    private String everythingWritten(Clinic clinic) throws SQLException {
        return String.join("\n", allAuditTexts(clinic, "%").toString(), allEventText(clinic), allDoseText(clinic),
                values(clinic, "select coalesce(string_agg(s::text, E'\\n'), '') from ipd.dose_scan_stop s").get(0)).toLowerCase();
    }

    // ---------------- N-01 (a): a code of the ordered drug ----------------

    @ParameterizedTest(name = "high-alert path = {0}")
    @ValueSource(booleans = {false, true})
    void aCodeOfTheOrderedDrugIsRecordedAsScanOnBothPaths(boolean high) throws Exception {
        Path p = path(high);
        Ward w = p.w();
        String code = codeOf(p.drug());
        if (high) {
            freshPinAllowance(w.nurse());
        }
        JsonNode a = doseOk(w.nurse().auth(), p.order(), scanBody(p, " " + code.toLowerCase() + " ", true));
        assertThat(a.get("dose").get("method").asText()).isEqualTo("SCAN");
        Dose d = dosesOf(w.clinic(), p.order()).get(0);
        assertThat(d.scanMethod()).isEqualTo("SCAN");
        assertThat(d.medicineScan()).isEqualTo(code);
        assertThat(d.overrideReason()).isEmpty();
        assertThat(stops(p)).isZero();
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).isEmpty();
        assertThat(audit(w.clinic(), "IPD_DOSE_GIVEN")).hasSize(1);
    }

    // ---------------- N-02 (b): a known code of another drug stops ----------------

    @ParameterizedTest(name = "high-alert path = {0}")
    @ValueSource(booleans = {false, true})
    void aKnownCodeOfAnotherDrugStopsEvenWhileTheOrderedDrugHasNoCodeAndTheSameNursesOverrideIsRefused(boolean high) throws Exception {
        Path p = path(high);
        Ward w = p.w();
        String other = codeOf("ceft");
        DSnap before = dsnap(w);
        long denies = denyEvents(w.clinic(), w.adm().id());

        refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, other, false)), 409, "medicine-mismatch", null);
        assertThat(table(w.clinic(), "select check_kind, actor::text from ipd.dose_scan_stop where order_id = ?::uuid", p.order()))
                .containsExactly(List.of("MEDICINE", w.nurse().userId().toString()));
        assertThat(denyEvents(w.clinic(), w.adm().id())).as("a deny entry").isEqualTo(denies + 1);
        assertThat(audit(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).isEmpty();
        assertThat(doseRowsOfOrder(w.clinic(), p.order())).isZero();
        assertThat(dsnap(w).pins()).as("the second nurse's PIN was never tried").isEqualTo(before.pins());

        // the same nurse's manual override: refused for 15 minutes (before the PIN, so a wrong one is never tried)
        refused(doseCall(w.nurse().auth(), p.order(), overrideBody(p, false)), 409, "override-after-mismatch", AFTER_MISMATCH);
        assertThat(doseRowsOfOrder(w.clinic(), p.order())).isZero();
        List<String[]> refusals = audit(w.clinic(), "IPD_DOSE_REFUSED");
        assertThat(refusals).hasSize(1);
        assertThat(objectMapper.readTree(refusals.get(0)[3]).get("refusal").asText()).isEqualTo("override-after-mismatch");
        assertThat(stops(p)).as("the refusal adds no stop row").isOne();
        assertThat(dsnap(w).pins()).isEqualTo(before.pins());
    }

    // ---------------- N-02b (security round 3, N-5): the code of another drug typed with spaces, upper case and a line break ----------------

    @ParameterizedTest(name = "high-alert path = {0}")
    @ValueSource(booleans = {false, true})
    void aKnownCodeOfAnotherDrugTypedWithSpacesLowerCaseAndALineBreakIsStillAMismatchOnBothPaths(boolean high) throws Exception {
        Path p = path(high);
        Ward w = p.w();
        String typed = " " + codeOf("ceft").toLowerCase() + "\r\n";
        DSnap before = dsnap(w);
        long denies = denyEvents(w.clinic(), w.adm().id());

        refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, typed, false)), 409, "medicine-mismatch", null);
        assertThat(table(w.clinic(), "select check_kind, actor::text from ipd.dose_scan_stop where order_id = ?::uuid", p.order()))
                .containsExactly(List.of("MEDICINE", w.nurse().userId().toString()));
        assertThat(audit(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).as("a mismatch, not a no-code-loaded refusal").isEmpty();
        assertThat(denyEvents(w.clinic(), w.adm().id())).as("one more deny event").isEqualTo(denies + 1);
        assertThat(doseRowsOfOrder(w.clinic(), p.order())).isZero();
        assertThat(dsnap(w).pins()).as("the second nurse's PIN was never tried").isEqualTo(before.pins());
    }

    // ---------------- N-03 (c): an unknown code while the ordered drug has no code ----------------

    @ParameterizedTest(name = "high-alert path = {0}")
    @ValueSource(booleans = {false, true})
    void anUnknownCodeWhileTheOrderedDrugHasNoCodeIsNoCodeLoadedWritesOneAuditRowAndTheOverrideIsAcceptedAtOnce(boolean high)
            throws Exception {
        Path p = path(high);
        Ward w = p.w();
        String scanned = unknownCode();
        try (LogCapture logs = new LogCapture()) {
            DSnap before = dsnap(w);
            long denies = denyEvents(w.clinic(), w.adm().id());

            JsonNode problem = refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, scanned, false)), 409, "no-code-loaded",
                    NO_CODE_DETAIL);
            assertThat(problem.get("title").asText()).isEqualTo(NO_CODE_TITLE);
            assertThat(problem.toString().toLowerCase()).as("the answer never echoes the scanned text").doesNotContain(scanned.toLowerCase());

            assertThat(stops(p)).as("no ipd.dose_scan_stop row").isZero();
            assertThat(denyEvents(w.clinic(), w.adm().id())).as("no deny entry (clinical event)").isEqualTo(denies);
            assertThat(eventCount(w.clinic(), w.adm().id())).as("no clinical event at all").isEqualTo(before.events());
            assertThat(audit(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH")).as("no mismatch audit").isEmpty();
            assertThat(doseRowsOfOrder(w.clinic(), p.order())).isZero();
            assertThat(dsnap(w).pins()).as("the second nurse's PIN was never tried").isEqualTo(before.pins());

            List<String[]> refusals = audit(w.clinic(), "IPD_DOSE_REFUSED");
            assertThat(refusals).as("exactly one IPD_DOSE_REFUSED, committed with the 409").hasSize(1);
            JsonNode after = objectMapper.readTree(refusals.get(0)[3]);
            assertThat(after.get("refusal").asText()).isEqualTo("no-code-loaded");
            assertThat(after.get("orderId").asText()).isEqualTo(p.order());
            assertThat(after.get("admissionId").asText()).isEqualTo(w.adm().id());
            assertThat(after.has("device")).isTrue();
            List<String> keys = new ArrayList<>();
            after.fieldNames().forEachRemaining(keys::add);
            assertThat(Set.copyOf(keys)).as("fixed keys only").isEqualTo(Set.of("orderId", "admissionId", "refusal", "device"));
            assertThat(allAudits(w.clinic())).isEqualTo(before.audits() + 1);

            // a second and a third kind of unknown text: a text that is no code at all, and one over 100 characters
            String words = "not a code " + UUID.randomUUID().toString().substring(0, 8) + "!";
            String longText = "zq".repeat(60) + UUID.randomUUID().toString().substring(0, 6);
            refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, words, false)), 409, "no-code-loaded", NO_CODE_DETAIL);
            refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, longText, false)), 409, "no-code-loaded", NO_CODE_DETAIL);
            assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).hasSize(3);
            assertThat(stops(p)).isZero();
            assertThat(denyEvents(w.clinic(), w.adm().id())).isEqualTo(denies);

            // the same nurse's override with an empty code, right after: accepted, nothing in the way
            if (high) {
                freshPinAllowance(w.nurse());
            }
            JsonNode given = doseOk(w.nurse().auth(), p.order(), overrideBody(p, true));
            assertThat(given.get("dose").get("method").asText()).isEqualTo("OVERRIDE");
            assertThat(given.get("dose").get("overrideReason").get("key").asText()).isEqualTo("NO_BARCODE");
            assertThat(dosesOf(w.clinic(), p.order()).get(0).overrideReason()).isEqualTo("NO_BARCODE");
            assertThat(doseRowsOfOrder(w.clinic(), p.order())).isOne();
            assertThat(audit(w.clinic(), "IPD_DOSE_GIVEN")).hasSize(1);
            assertThat(stops(p)).isZero();

            // the scanned text is in no audit row, event, dose row, scan-stop row or log line (also not in its upper-case form)
            String written = everythingWritten(w.clinic());
            String logText = logs.allText().toLowerCase();
            for (String text : List.of(scanned, words, longText)) {
                String needle = text.toLowerCase();
                assertThat(written).as("written: " + text.length() + " characters typed").doesNotContain(needle);
                assertThat(written).doesNotContain(needle.replace(" ", ""));
                assertThat(logText).as("logged: " + text.length() + " characters typed").doesNotContain(needle);
                assertThat(logText).doesNotContain(needle.replace(" ", ""));
            }
            // the override note is stored on the dose row (that is its place) but is in no audit row, event or log line
            assertThat(allAuditTexts(w.clinic(), "%").toString().toLowerCase()).doesNotContain(NOTE.toLowerCase());
            assertThat(allEventText(w.clinic()).toLowerCase()).doesNotContain(NOTE.toLowerCase());
            assertThat(logText).doesNotContain(NOTE.toLowerCase());
        }
    }

    // ---------------- N-04: after no-code-loaded a code of another drug still stops ----------------

    @ParameterizedTest(name = "high-alert path = {0}")
    @ValueSource(booleans = {false, true})
    void afterNoCodeLoadedAKnownCodeOfAnotherDrugIsStillAStopWithTheFifteenMinuteRule(boolean high) throws Exception {
        Path p = path(high);
        Ward w = p.w();
        String other = codeOf("ceft");
        refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, unknownCode(), false)), 409, "no-code-loaded", NO_CODE_DETAIL);
        assertThat(stops(p)).isZero();

        refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, other, false)), 409, "medicine-mismatch", null);
        assertThat(stops(p)).as("the code of another drug is a real mismatch").isOne();
        assertThat(audit(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH")).hasSize(1);
        refused(doseCall(w.nurse().auth(), p.order(), overrideBody(p, false)), 409, "override-after-mismatch", AFTER_MISMATCH);
        assertThat(doseRowsOfOrder(w.clinic(), p.order())).isZero();
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).as("no-code-loaded and override-after-mismatch").hasSize(2);
    }

    // ---------------- N-05 (d): an unknown code while the ordered drug has codes ----------------

    @ParameterizedTest(name = "high-alert path = {0}")
    @ValueSource(booleans = {false, true})
    void anUnknownCodeWhileTheOrderedDrugHasCodesIsAMismatchWithTheStopRowAndTheRule(boolean high) throws Exception {
        Path p = path(high);
        Ward w = p.w();
        codeOf(p.drug());
        String scanned = unknownCode();
        DSnap before = dsnap(w);
        long denies = denyEvents(w.clinic(), w.adm().id());

        refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, scanned, false)), 409, "medicine-mismatch", null);
        assertThat(table(w.clinic(), "select check_kind from ipd.dose_scan_stop where order_id = ?::uuid", p.order()))
                .containsExactly(List.of("MEDICINE"));
        assertThat(denyEvents(w.clinic(), w.adm().id())).isEqualTo(denies + 1);
        assertThat(audit(w.clinic(), "IPD_DOSE_MEDICINE_MISMATCH")).hasSize(1);
        assertThat(audit(w.clinic(), "IPD_DOSE_REFUSED")).isEmpty();
        assertThat(eventLines(w.clinic(), w.adm().id())).contains(
                "deny|Stopped at the medicine check: entered a code not in the medicine list, expected "
                        + (high ? "Inj. Heparin 5,000 IU" : "Tab. Paracetamol 650 mg"));
        assertThat(everythingWritten(w.clinic())).as("the typed code is never written").doesNotContain(scanned.toLowerCase());
        assertThat(dsnap(w).pins()).isEqualTo(before.pins());
        refused(doseCall(w.nurse().auth(), p.order(), overrideBody(p, false)), 409, "override-after-mismatch", AFTER_MISMATCH);
        // a code that is too long counts as not in the drug master: a mismatch here as well (the drug has codes)
        refused(doseCall(w.nurse().auth(), p.order(), scanBody(p, "zq".repeat(60), false)), 409, "medicine-mismatch", null);
        assertThat(stops(p)).isEqualTo(2);
    }
}
