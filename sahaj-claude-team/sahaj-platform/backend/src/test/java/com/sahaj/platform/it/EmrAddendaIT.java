package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-51-5 on the real database (docs/api/opd.yaml 0.6.1, ADR-025 D3): a correction to a signed record is an addendum that
 * is stored with reason, text, doctor and time, never changes the record, the workflow step, the follow-up or the bill, shows
 * newest last on the record, and can never be changed (the table is append-only: see EmrPhase5TablesIT). Test plan:
 * docs/test-plans/SAH-51-68.md (T-51-5.x).
 */
@OnTestDatabase
class EmrAddendaIT extends EmrSignSupport {

    private String path(Rec rec) {
        return RECORDS + "/" + rec.id() + "/addenda";
    }

    private String body(String reason, String text) {
        return objectMapper.createObjectNode().put("reason", reason).put("text", text).toString();
    }

    private Rec signed() throws Exception {
        Rec rec = chooseInterval(readyRec(), "D7");
        signOk(rec);
        return rec;
    }

    private JsonNode add(Rec rec, String reason, String text) throws Exception {
        return okKeyed(HttpMethod.POST, path(rec), rec.token(), body(reason, text), key(), 201);
    }

    // ---------------- adding ----------------

    @Test
    void aDraftRecordCannotHaveAnAddendum() throws Exception {
        Rec rec = readyRec();
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), body("Wrong dose", "Should be 5 ml"), key(), 409, "emr-record-not-signed");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isZero();
    }

    @Test
    void theRecordsDoctorAddsAnAddendumStampedWithTheDoctorAndTheTimeAndItShowsNewestLastOnTheRecord() throws Exception {
        Rec rec = signed();
        JsonNode first = add(rec, "Wrong dose", "The dose of the syrup is 5 ml");
        JsonNode second = add(rec, "Missed allergy", "Allergic to dust");
        assertThat(first.get("id").asText()).isNotBlank();
        assertThat(first.get("reason").asText()).isEqualTo("Wrong dose");
        assertThat(first.get("text").asText()).isEqualTo("The dose of the syrup is 5 ml");
        assertThat(first.get("createdByName").asText()).isNotBlank();
        assertThat(first.get("createdAt").asText()).isNotBlank();
        JsonNode view = record(rec.token(), rec.id());
        assertThat(view.get("addenda")).hasSize(2);
        assertThat(view.get("addenda").get(0).get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(view.get("addenda").get(1).get("id").asText()).isEqualTo(second.get("id").asText());
        assertThat(view.get("canAddAddendum").asBoolean()).isTrue();
        assertThat(row(rec.clinic(), "select created_by_user_id::text, created_by_membership_id::text from opd.emr_record_addendum "
                + "where id = ?::uuid", first.get("id").asText())).containsExactly(rec.doctor().userId().toString(),
                rec.doctor().membershipId().toString());
    }

    @Test
    void anAddendumChangesNothingOfTheRecordTheCardTheFollowUpTheBillOrTheSignatureAudit() throws Exception {
        Rec rec = signed();
        String sections = row(rec.clinic(), "select sections::text from opd.emr_record where id = ?::uuid", rec.id()).get(0);
        List<String> columns = signColumns(rec.clinic(), rec.id());
        String stage = boardVisit(rec.clinic(), rec.visitId()).get("stage").asText();
        long followUps = followUpRows(rec.clinic(), rec.visitId());
        long billItems = count(rec.clinic(), "select count(*) from billing.bill_item");
        long signAudits = auditCount(rec.clinic(), "EMR_RECORD_SIGNED");
        add(rec, "Correction", "Typed dose was wrong");
        assertThat(row(rec.clinic(), "select sections::text from opd.emr_record where id = ?::uuid", rec.id()).get(0)).isEqualTo(sections);
        assertThat(signColumns(rec.clinic(), rec.id())).isEqualTo(columns);
        assertThat(boardVisit(rec.clinic(), rec.visitId()).get("stage").asText()).isEqualTo(stage);
        assertThat(followUpRows(rec.clinic(), rec.visitId())).isEqualTo(followUps);
        assertThat(count(rec.clinic(), "select count(*) from billing.bill_item")).isEqualTo(billItems);
        assertThat(auditCount(rec.clinic(), "EMR_RECORD_SIGNED")).isEqualTo(signAudits);
        assertThat(record(rec.token(), rec.id()).get("status").asText()).isEqualTo("SIGNED");
    }

    @Test
    void theSameKeyAndBodyGivesTheFirstAddendumAgainAndAnotherBodyIs409IdempotencyConflict() throws Exception {
        Rec rec = signed();
        String key = key();
        JsonNode first = okKeyed(HttpMethod.POST, path(rec), rec.token(), body("Reason", "Text one"), key, 201);
        JsonNode again = okKeyed(HttpMethod.POST, path(rec), rec.token(), body("Reason", "Text one"), key, 200);
        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), body("Reason", "Text two"), key, 409, "idempotency-conflict");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isOne();
        assertThat(auditCount(rec.clinic(), "EMR_ADDENDUM_ADDED")).isOne();
    }

    // ---------------- validation ----------------

    @Test
    void aReasonOrTextThatIsEmptyTooLongOrHasControlCharactersIs400AndNothingIsStored() throws Exception {
        Rec rec = signed();
        String[][] bad = {{"   ", "Text"}, {"Reason", "   "}, {"x".repeat(201), "Text"}, {"Reason", "x".repeat(4001)},
                {"Line\nbreak", "Text"}, {"Reason", "Bell\u0007char"}};
        for (String[] b : bad) {
            JsonNode problem = problemKeyed(HttpMethod.POST, path(rec), rec.token(), body(b[0], b[1]), key(), 400, "validation");
            assertThat(problem.toString()).doesNotContain("Bell");
        }
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), "{\"reason\":\"Only a reason\"}", key(), 400, "validation");
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), "{\"text\":\"Only a text\"}", key(), 400, "validation");
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), body("Reason", "Text"), null, 400, "validation");
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), body("Reason", "Text"), "bad key!", 400, "validation");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isZero();
    }

    @Test
    void theLimitsAreInclusiveAndALineBreakIsAllowedInTheText() throws Exception {
        Rec rec = signed();
        add(rec, "x".repeat(200), "y".repeat(4000));
        add(rec, "Reason", "First line\nSecond line");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isEqualTo(2);
    }

    @Test
    void aRecordHoldsAtMostFiftyAddenda() throws Exception {
        Rec rec = signed();
        for (int i = 0; i < 50; i++) {
            add(rec, "Reason " + i, "Text " + i);
        }
        problemKeyed(HttpMethod.POST, path(rec), rec.token(), body("One more", "Too many"), key(), 400, "addendum-limit");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isEqualTo(50);
        assertThat(record(rec.token(), rec.id()).get("canAddAddendum").asBoolean()).isFalse();
    }

    // ---------------- who may add ----------------

    @Test
    void theOwnerWithDoctorDetailsMayAddToAnotherDoctorsRecordAndOtherPeopleMayNot() throws Exception {
        Rec rec = signed();
        Staff owner = ownerWithDoctorDetails(rec.clinic(), "General physician", "MBBS", "REG-" + shortId());
        okKeyed(HttpMethod.POST, path(rec), owner.token(rec.clinic()), body("Owner note", "Owner correction"), key(), 201);
        Staff other = addDoctor(rec.clinic(), "Dentist");
        Staff visiting = addStaff(rec.clinic(), "VISITING_DOCTOR", "Dr Visiting Person", "Dentist", "BDS", "REG-" + shortId());
        for (Staff who : new Staff[] {other, visiting}) {
            problemKeyed(HttpMethod.POST, path(rec), who.token(rec.clinic()), body("Reason", "Text"), key(), 403, "not-own-patient");
        }
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(rec.clinic(), role);
            problemKeyed(HttpMethod.POST, path(rec), staff.token(rec.clinic()), body("Reason", "Text"), key(), 403, "forbidden");
        }
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).as("only the owner's").isOne();
    }

    @Test
    void anotherClinicsRecordIs404AndNoSessionIs401() throws Exception {
        Rec rec = signed();
        Rec foreign = newRec();
        problemKeyed(HttpMethod.POST, path(rec), foreign.token(), body("Reason", "Text"), key(), 404, "record-not-found");
        problemKeyed(HttpMethod.POST, path(rec), anonymous(), body("Reason", "Text"), key(), 401, "unauthorized");
        assertThat(count(rec.clinic(), "select count(*) from opd.emr_record_addendum")).isZero();
        assertThat(count(foreign.clinic(), "select count(*) from opd.emr_record_addendum")).isZero();
    }

    // ---------------- privacy ----------------

    @Test
    void theAuditEntryHoldsTheRecordAndTheAddendumIdOnlyAndNoLogLineHoldsTheText() throws Exception {
        Rec rec = signed();
        JsonNode added;
        try (LogCapture logs = new LogCapture()) {
            added = add(rec, "Zxqreason marker", "Zxqtext marker");
            problemKeyed(HttpMethod.POST, path(rec), rec.token(), body("Zxqreason marker", " "), key(), 400, "validation");
            assertThat(logs.allText()).doesNotContain("Zxqreason").doesNotContain("Zxqtext");
        }
        List<List<String>> audits = auditRows(rec.clinic(), "EMR_ADDENDUM_ADDED");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get(0)).isEqualTo("EMR_RECORD");
        assertThat(audits.get(0).get(2)).contains(added.get("id").asText()).doesNotContain("Zxq");
        assertThat(allAuditText(rec.clinic())).doesNotContain("Zxqreason").doesNotContain("Zxqtext");
    }
}
