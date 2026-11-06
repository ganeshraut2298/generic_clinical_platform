package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SAH-30 AC13, DEC-013 item 11, on the real {@code sahaj_test} database: opening the Nursing sheet writes exactly one
 * patient record-access row and a refusal or a save writes none; the audit row of a save holds ids, the score, the
 * risk and whether a note was added, never a value, the note, a name or a mobile number, and a replay writes none;
 * the application log across a save, a repeat, refusals, a sheet open and a database failure holds no name, mobile,
 * vital sign, temperature or note; the four tables have no personal column. Test plan: docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsPrivacyIT extends VitalsSupport {

    /** Values that are easy to search for: none of them may appear anywhere they should not. */
    private record Secret(Adm adm, String note, Map<String, Object> body) {
        List<String> everything() {
            return List.of(adm.name(), adm.mobile(), note, "137/83", "36.7");
        }
    }

    private Secret secret(Clinic clinic, String first) throws Exception {
        Adm adm = admitNamed(clinic, first + " Wadkar" + uniqueMobile().substring(5));
        String note = "ZXQ-" + UUID.randomUUID();
        return new Secret(adm, note, noted(vit("137/83", "77", "97", "36.7", "17"), note));
    }

    private long accessRows(Clinic clinic, Adm adm) throws SQLException {
        return recordAccess(clinic, adm.patientId());
    }

    // ---------------- the record-access log (DEC-013 item 11) ----------------

    @Test
    void openingTheSheetAndAReplayWriteExactlyOneRecordAccessRowEachAndNewSavesRefusalsAndConflictsWriteNone() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        long start = accessRows(clinic, adm);

        nurseSheet(clinic, adm.id());
        assertThat(accessRows(clinic, adm)).isEqualTo(start + 1);
        assertThat(count(clinic, "select count(*) from patient.record_access where patient_id = ? and user_id = ?",
                adm.patientId(), clinic.ownerUserId())).isGreaterThanOrEqualTo(1);
        nurseSheetAs(clinic, "NURSE", adm.id());
        nurseSheetAs(clinic, "DOCTOR", adm.id());
        assertThat(accessRows(clinic, adm)).isEqualTo(start + 3);

        long afterReads = accessRows(clinic, adm);
        // a new save writes none; a replay with equal values writes exactly one (its answer is the full sheet, security
        // review L2); a refused body and a 409 client-key-reused write none
        Map<String, Object> body = noted(medium3(), "Access test");
        save(clinic, adm.id(), body);
        assertThat(accessRows(clinic, adm)).as("a new POST writes no access row").isEqualTo(afterReads);
        replay(clinic, adm.id(), body);
        assertThat(accessRows(clinic, adm)).as("a replay writes exactly one access row").isEqualTo(afterReads + 1);
        refused(clinic, adm.id(), with(low(), "bp", "x"));
        problem(saveCall(clinic.owner(), adm.id(), with(body, "pulse", "99")), 409);
        assertThat(accessRows(clinic, adm)).as("a refused body and a 409 write none").isEqualTo(afterReads + 1);
        afterReads = afterReads + 1;

        // refusals write none: 403 (role), 401, 404 (other clinic and unknown id), 400 (path), Off
        for (String role : NURSING_NON_VIEWERS) {
            problem(sheetCall(clinic.as(role), adm.id()), 403);
        }
        problem(sheetCall(noSession(), adm.id()), 401);
        Clinic other = hospital();
        problem(sheetCall(other.owner(), adm.id()), 404);
        problem(sheetCall(clinic.owner(), UUID.randomUUID()), 404);
        assertThat(accessRows(clinic, adm)).as("403, 401 and 404 write no access row").isEqualTo(afterReads);
        assertThat(count(other, "select count(*) from patient.record_access")).isZero();

        // the admission sheet of SAH-23 and the discharge sheet already write theirs; the nursing sheet adds its own
        detail(clinic, adm.id());
        assertThat(accessRows(clinic, adm)).isEqualTo(afterReads + 1);
        nurseSheet(clinic, adm.id());
        assertThat(accessRows(clinic, adm)).isEqualTo(afterReads + 2);
    }

    // ---------------- the audit row ----------------

    @Test
    void theAuditRowOfASaveHoldsIdsScoreRiskAndTheNoteFlagAndNeverAValueTheNoteAOrANameOrAMobile() throws Exception {
        Clinic clinic = hospital();
        Secret s = secret(clinic, "Zubin");
        JsonNode saved = save(clinic, s.adm().id(), s.body());
        String vitalsId = saved.get("saved").get("vitalsId").asText();

        List<String[]> rows = audit(clinic, "IPD_VITALS_RECORDED");
        assertThat(rows).hasSize(1);
        String[] row = rows.get(0);
        assertThat(row[0]).isEqualTo("IPD_VITALS");
        assertThat(row[1]).isEqualTo(vitalsId);
        assertThat(row[2]).as("no before image").isNull();
        assertThat(row[4]).isEqualTo(clinic.ownerUserId().toString());
        JsonNode after = objectMapper.readTree(row[3]);
        Set<String> keys = new TreeSet<>();
        after.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("admissionId", "vitalsId", "ewsScore", "ewsRisk", "noteAdded", "escalationId",
                "escalationOutcome");
        assertThat(after.get("admissionId").asText()).isEqualTo(s.adm().id());
        assertThat(after.get("vitalsId").asText()).isEqualTo(vitalsId);
        assertThat(after.get("ewsScore").asInt()).isZero();
        assertThat(after.get("ewsRisk").asText()).isEqualTo("LOW");
        assertThat(after.get("noteAdded").asBoolean()).isTrue();
        assertThat(after.get("escalationOutcome").asText()).isEqualTo("NONE");
        for (String secret : s.everything()) {
            assertThat(row[2] + row[3]).as("audit must not hold " + (secret.startsWith("ZXQ") ? "the note" : "personal data"))
                    .doesNotContain(secret);
        }
        // no audit row of any action holds the note or a vital value either
        List<String> allAudit = values(clinic, "select coalesce(string_agg(coalesce(before_json::text, '') || coalesce(after_json::text, ''), ' '), '') "
                + "from platform.audit_event");
        for (String secret : List.of(s.note(), "137/83", "36.7")) {
            assertThat(allAudit.get(0)).doesNotContain(secret);
        }
    }

    @Test
    void aReplayARefusalAClientKeyConflictAndAReadWriteNoAuditRow() throws Exception {
        Clinic clinic = hospital();
        Secret s = secret(clinic, "Meera");
        save(clinic, s.adm().id(), s.body());
        assertThat(vitalsAudits(clinic)).isOne();
        long all = allAudits(clinic);

        ok(org.springframework.http.HttpMethod.POST, vitalsUrl(s.adm().id()), clinic.owner(), js(s.body()), 200);
        refused(clinic, s.adm().id(), with(s.body(), "pulse", "0"));
        problem(saveCall(clinic.owner(), s.adm().id(), with(s.body(), "pulse", "99")), 409);
        problem(saveCall(clinic.as("DOCTOR"), s.adm().id(), newKey(s.body())), 403);
        problem(saveCall(hospital().owner(), s.adm().id(), newKey(s.body())), 404);
        nurseSheet(clinic, s.adm().id());
        nurseSheetAs(clinic, "DOCTOR", s.adm().id());
        assertThat(vitalsAudits(clinic)).isOne();
        assertThat(allAudits(clinic)).isEqualTo(all);
    }

    // ---------------- the application log ----------------

    @Test
    void theLogAcrossASaveARepeatRefusalsASheetOpenAndAFailureHoldsNoNameMobileVitalOrNote() throws Exception {
        Clinic clinic = hospital();
        Secret s = secret(clinic, "Kabir");
        Clinic other = hospital();
        List<String> answers = new ArrayList<>();
        String logged;
        try (LogCapture logs = new LogCapture()) {
            answers.add(save(clinic, s.adm().id(), s.body()).toString());
            answers.add(ok(org.springframework.http.HttpMethod.POST, vitalsUrl(s.adm().id()), clinic.owner(), js(s.body()), 200)
                    .toString());
            answers.add(nurseSheet(clinic, s.adm().id()).toString());
            answers.add(nurseSheetAs(clinic, "DOCTOR", s.adm().id()).toString());
            // refusals that carry the typed values
            answers.add(problem(saveCall(clinic.owner(), s.adm().id(), with(s.body(), "bp", "137/8x")), 400).toString());
            answers.add(problem(saveCall(clinic.owner(), s.adm().id(), noted(newKey(s.body()), s.note() + "\u0007")), 400).toString());
            answers.add(problem(saveCall(clinic.as("DOCTOR"), s.adm().id(), newKey(s.body())), 403).toString());
            answers.add(problem(saveCall(other.owner(), s.adm().id(), newKey(s.body())), 404).toString());
            answers.add(problem(sheetCall(other.owner(), s.adm().id()), 404).toString());
            // a database failure inside the save
            failingInsert(clinic, "ipd.clinical_event", "new.kind = 'info'", () ->
                    answers.add(problem(saveCall(clinic.owner(), s.adm().id(), newKey(s.body())), 500).toString()));
            logged = logs.allText();
        }
        assertThat(logged).as("something was logged at all").isNotEmpty();
        for (String secret : s.everything()) {
            assertThat(logged).as("the log must not hold " + (secret.startsWith("ZXQ") ? "the note" : secret)).doesNotContain(secret);
        }
        assertThat(logged).doesNotContain("VitalsInput[bp").doesNotContain("bp=137");
        // and the refusal bodies repeat nothing that was typed (the successful answers show the data on screen only)
        for (int i = 4; i < answers.size(); i++) {
            for (String secret : s.everything()) {
                assertThat(answers.get(i)).as("answer " + i).doesNotContain(secret);
            }
        }
    }

    // ---------------- the tables ----------------

    @Test
    void theFourTablesHaveNoColumnThatCouldHoldANameAMobileNumberOrAPatientKey() throws Exception {
        List<String> columns = db().asMigrator(c -> {
            List<String> out = new ArrayList<>();
            try (var ps = c.prepareStatement("select table_name || '.' || column_name from information_schema.columns "
                    + "where table_schema = 'ipd' and table_name in ('vitals', 'nursing_note', 'ews_escalation', 'clinical_event') "
                    + "order by 1");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
        assertThat(columns).hasSizeGreaterThan(40);
        for (String column : columns) {
            assertThat(column).doesNotContainIgnoringCase("name").doesNotContainIgnoringCase("mobile")
                    .doesNotContainIgnoringCase("phone").doesNotContainIgnoringCase("abha").doesNotContainIgnoringCase("patient_id");
        }
        assertThat(columns).contains("vitals.note_added", "nursing_note.note", "clinical_event.text");
    }

    @Test
    void theSheetCarriesTheNameOfTheScreenButNoMobileNumberAndNoIdentifierOfThePatientRecord() throws Exception {
        Clinic clinic = hospital();
        Secret s = secret(clinic, "Farah");
        JsonNode saved = save(clinic, s.adm().id(), s.body());
        for (JsonNode sheet : new JsonNode[] {saved, nurseSheet(clinic, s.adm().id()), nurseSheetAs(clinic, "DOCTOR", s.adm().id())}) {
            assertThat(sheet.get("patientName").asText()).isEqualTo(s.adm().name());
            assertThat(sheet.toString()).doesNotContain(s.adm().mobile()).doesNotContain(s.adm().patientId().toString());
            assertThat(sheet.has("mobile")).isFalse();
            assertThat(sheet.has("patientId")).isFalse();
        }
    }
}
