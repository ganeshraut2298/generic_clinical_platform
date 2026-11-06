package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

/**
 * SAH-27 audit rows, logs and the patient access log, on the real {@code sahaj_test} database: the four audit actions
 * hold ids, codes, types and amounts only (never the name, the mobile, the diagnosis or the follow-up reason), the log
 * holds none of them either, and the answers that show the patient's name write one {@code patient.record_access} row.
 * Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
@ExtendWith(OutputCaptureExtension.class)
class DischargePrivacyIT extends DischargeSupport {

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void theFourAuditActionsCarryIdsCodesTypesAndAmountsAndNeverTheNameTheMobileTheDiagnosisOrTheFollowUpReason(
            CapturedOutput output) throws Exception {
        Clinic clinic = hospital();
        String secretName = "Zubin Wadkar" + uniqueMobile().substring(5);
        String secretReason = "ZXQ-" + UUID.randomUUID();
        Person p = person(clinic, secretName);
        UUID bed = newBed(clinic, "gen");
        java.util.Map<String, Object> m = body(p.id(), clinic.doctorMembershipId(), bed);
        m.put("reason", secretReason);
        String id = admitOk(clinic, m).get("id").asText();
        String bedCode = values(clinic, "select code from ipd.bed where id = ?", bed).get(0);
        String ip = detail(clinic, id).get("ipNumber").asText();

        putSettings(clinic, "surgical", false, 9);
        sign(clinic, id, "lama", "surgical");
        sheet(clinic, id);
        JsonNode d = dischargeNow(clinic, id, "lama", "surgical", "Cash");
        long total = d.get("finalBill").get("totalPaise").asLong();
        long balance = d.get("finalBill").get("balancePaise").asLong();

        List<String[]> signed = audit(clinic, "IPD_DISCHARGE_SIGNED");
        assertThat(signed).hasSize(1);
        assertThat(signed.get(0)[0]).isEqualTo("IPD_DISCHARGE_SIGNOFF");
        assertThat(signed.get(0)[1]).isEqualTo(values(clinic, "select id::text from ipd.discharge_signoff").get(0));
        assertThat(signed.get(0)[2]).isNull();
        assertThat(signed.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        JsonNode signedAfter = objectMapper.readTree(signed.get(0)[3]);
        assertThat(keys(signedAfter)).containsExactlyInAnyOrder("admissionId", "type", "templateKey");
        assertThat(signedAfter.get("admissionId").asText()).isEqualTo(id);
        assertThat(signedAfter.get("type").asText()).isEqualTo("lama");
        assertThat(signedAfter.get("templateKey").asText()).isEqualTo("surgical");

        List<String[]> done = audit(clinic, "IPD_DISCHARGED");
        assertThat(done).hasSize(1);
        assertThat(done.get(0)[0]).isEqualTo("IPD_ADMISSION");
        assertThat(done.get(0)[1]).isEqualTo(id);
        assertThat(done.get(0)[2]).isNull();
        assertThat(done.get(0)[4]).isEqualTo(clinic.ownerUserId().toString());
        JsonNode after = objectMapper.readTree(done.get(0)[3]);
        assertThat(keys(after)).containsExactlyInAnyOrder("admissionId", "ipNumber", "type", "totalPaise", "balancePaise", "mode",
                "bedCode");
        assertThat(after.get("admissionId").asText()).isEqualTo(id);
        assertThat(after.get("ipNumber").asText()).isEqualTo(ip);
        assertThat(after.get("type").asText()).isEqualTo("lama");
        assertThat(after.get("totalPaise").asLong()).isEqualTo(total);
        assertThat(after.get("balancePaise").asLong()).isEqualTo(balance);
        assertThat(after.get("mode").asText()).isEqualTo("Cash");
        assertThat(after.get("bedCode").asText()).isEqualTo(bedCode);

        List<String[]> setup = audit(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED");
        assertThat(setup).hasSize(1);
        assertThat(setup.get(0)[0]).isEqualTo("IPD_CONFIG");
        assertThat(objectMapper.readTree(setup.get(0)[2])).isEqualTo(objectMapper.readTree(
                "{\"templateKey\":null,\"approvalChain\":true,\"followUpDays\":7}"));
        assertThat(objectMapper.readTree(setup.get(0)[3])).isEqualTo(objectMapper.readTree(
                "{\"templateKey\":\"surgical\",\"approvalChain\":false,\"followUpDays\":9}"));

        List<String[]> followUp = audit(clinic, "FOLLOW_UP_CREATED");
        assertThat(followUp).hasSize(1);
        JsonNode fu = objectMapper.readTree(followUp.get(0)[3]);
        assertThat(keys(fu)).containsExactlyInAnyOrder("patientId", "doctorMembershipId", "sourceAdmissionId", "dueDate");
        assertThat(fu.get("sourceAdmissionId").asText()).isEqualTo(id);
        assertThat(fu.get("patientId").asText()).isEqualTo(p.id().toString());
        assertThat(fu.get("doctorMembershipId").asText()).isEqualTo(clinic.doctorMembershipId().toString());
        assertThat(fu.get("dueDate").asText()).isEqualTo(d.get("discharge").get("followUpDate").asText());

        // the reason of the follow-up and the diagnosis are stored where they belong and nowhere else
        assertThat(count(clinic, "select count(*) from opd.follow_up where reason like ?", "%" + secretReason + "%")).isOne();
        assertThat(count(clinic, "select count(*) from ipd.admission where reason = ?", secretReason)).isOne();
        for (String secret : new String[] {secretReason, secretName, p.mobile(), "Zubin", "Wadkar", "Review after discharge"}) {
            assertThat(count(clinic, "select count(*) from platform.audit_event where audit_event::text like ?",
                    "%" + secret + "%")).as("audit rows must not contain " + secret).isZero();
        }
        for (String secret : new String[] {secretReason, secretName, p.mobile(), "Zubin", "Wadkar"}) {
            assertThat(output.getAll()).as("log must not contain " + secret).doesNotContain(secret);
        }
    }

    @Test
    void aSettledDischargeAuditsNoModeAndAnUnchangedSetupWritesNoAuditRow() throws Exception {
        Clinic clinic = hospital();
        String id = admitGeneral(clinic, 6).get("id").asText();
        charge(clinic, id, "dressing");
        signNormal(clinic, id);
        dischargeNow(clinic, id, "normal", "general", null);
        JsonNode after = objectMapper.readTree(audit(clinic, "IPD_DISCHARGED").get(0)[3]);
        assertThat(after.get("balancePaise").asLong()).isZero();
        assertThat(after.path("mode").isNull() || after.path("mode").isMissingNode()).isTrue();

        putSettings(clinic, null, true, 7);                 // equal to the defaults
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).isZero();
        putSettings(clinic, "daycare", true, 7);
        putSettings(clinic, "daycare", true, 7);
        assertThat(audits(clinic, "IPD_DISCHARGE_SETTINGS_UPDATED")).isOne();
    }

    // ---------------- the patient access log ----------------

    @Test
    void theSheetAndTheDischargeAnswerShowThePatientSoEachWritesOneAccessRowAndRefusedCallsAndOtherReadsWriteNone() throws Exception {
        Clinic clinic = hospital();
        Person p = person(clinic, "Kishor Lonkar");
        String id = admitOk(clinic, body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "gen"))).get("id").asText();
        assertThat(recordAccess(clinic, p.id())).isZero();

        problem(call(HttpMethod.GET, dischargeUrl(UUID.randomUUID()), clinic.owner(), null), 404);   // not found: no row
        ok(HttpMethod.GET, ADMISSIONS, clinic.owner(), null, 200);
        ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.owner(), null, 200);
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, "UPI")), 409);   // not signed
        problem(postDischarge(clinic.as("NURSE"), id, dischargeBody("normal", "general", DAY, "UPI")), 403);
        problem(signAs(clinic, clinic.as("NURSE"), id, "normal", "general"), 403);
        assertThat(recordAccess(clinic, p.id())).as("lists, settings, a 404 and refused calls write no access row").isZero();

        // security review M3: opening the Discharge sheet shows the name and the diagnosis, so it is logged like an open
        sheet(clinic, id);
        assertThat(recordAccess(clinic, p.id())).isOne();
        sheet(clinic, id);
        assertThat(recordAccess(clinic, p.id())).isEqualTo(2);
        assertThat(values(clinic, "select user_id::text from patient.record_access where patient_id = ?", p.id()))
                .containsOnly(clinic.ownerUserId().toString());

        // sign-off access is asserted in the next test
        signNormal(clinic, id);
        long afterSign = recordAccess(clinic, p.id());   // 2 sheet opens + 1 sign-off (the answer shows the name)
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY + 5, "UPI")), 409);   // bill changed
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, null)), 400);        // no mode
        assertThat(recordAccess(clinic, p.id())).isEqualTo(afterSign);

        dischargeNow(clinic, id, "normal", "general", "UPI");     // reads the sheet (one row), then the answer is the full sheet (one row)
        assertThat(recordAccess(clinic, p.id())).as("the answer is the full sheet, so it is logged like an open")
                .isEqualTo(afterSign + 2);
        // the sheet of a discharged admission is 409 not-admitted and writes no row
        problem(call(HttpMethod.GET, dischargeUrl(id), clinic.owner(), null), 409);
        assertThat(recordAccess(clinic, p.id())).isEqualTo(afterSign + 2);
    }

    @Test
    void aRealSignoffWritesOnePatientAccessRowBecauseItsAnswerShowsThePatientsName() throws Exception {
        // FINDING F-27-1 (tech lead decision): the java-developer adds this in the next pass. This test fails until then.
        Clinic clinic = hospital();
        Person p = person(clinic, "Kishor Lonkar");
        String id = admitOk(clinic, body(p.id(), clinic.doctorMembershipId(), newBed(clinic, "gen"))).get("id").asText();
        assertThat(recordAccess(clinic, p.id())).isZero();
        sign(clinic, id, "normal", "general");
        assertThat(recordAccess(clinic, p.id())).as("FINDING F-27-1: a sign-off answer shows the name and is not logged yet")
                .isOne();
        // the access row is for the patient and the signed-in person
        assertThat(values(clinic, "select user_id::text from patient.record_access where patient_id = ?", p.id()))
                .containsExactly(clinic.ownerUserId().toString());
    }

}
