package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-58 (ADR-022 D12, DEC-042 H1) in IPD: "Admit now" for a planned day-care or IPD sitting of a treatment course. In the
 * admission's own transaction the sitting is linked to the admission and the IPD deposits earmarked to it are carried from
 * the course as a deposit with source COURSE and no mode (capped at the course's credit and at Rs 10,00,000); a sitting that
 * cannot be admitted is 409 course-sitting-not-available and nothing is written. Also the stored shape of the changed ipd
 * tables (S19, S20 of docs/database/SAH-48-59.md) and the deposit entries that now always carry a source and a course number.
 * Real database as {@code sahaj_app}. Test plan: docs/test-plans/SAH-48-59.md.
 */
@OnTestDatabase
class EmrCourseAdmissionIT extends IpdBillingSupport {

    private static final String OPD = "/api/v1/opd";
    private static final String BILLING = "/api/v1/billing";

    /** A hospital, a patient with an open EMR of the doctor, and a Dental course: S1 OPD Rs 1,000 and S2 a day-care admission of Rs 4,000. */
    private record Setup(Clinic clinic, Person patient, RequestPostProcessor doctor, JsonNode plan) {

        String course() {
            return plan.get("id").asText();
        }

        String s1() {
            return plan.get("sittings").get(0).get("id").asText();
        }

        String s2() {
            return plan.get("sittings").get(1).get("id").asText();
        }
    }

    private JsonNode postKeyed(String path, RequestPostProcessor auth, String body, int expected) throws Exception {
        String text = mvc.perform(MockMvcRequestBuilders.request(HttpMethod.POST, java.net.URI.create(path)).with(auth)
                        .header("Idempotency-Key", "k" + UUID.randomUUID().toString().replace("-", ""))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return text.isEmpty() ? null : objectMapper.readTree(text);
    }

    private RequestPostProcessor doctorToken(Clinic clinic) throws Exception {
        UUID userId = UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid", clinic.doctorMembershipId()).get(0));
        return asUser(clinic.tenantId(), userId, "DOCTOR");
    }

    private Setup setup() throws Exception {
        Clinic clinic = hospital();
        Person patient = person(clinic, "Mohan Joshi");
        RequestPostProcessor doctor = doctorToken(clinic);
        JsonNode visit = ok(HttpMethod.POST, OPD + "/visits", clinic.owner(),
                "{\"patientId\":\"" + patient.id() + "\",\"doctorMembershipId\":\"" + clinic.doctorMembershipId() + "\"}", 201);
        JsonNode opened = ok(HttpMethod.POST, OPD + "/emr/records/open", doctor,
                "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + visit.get("id").asText() + "\"}", 201);
        String body = "{\"fromRecordId\":\"" + opened.get("record").get("id").asText() + "\",\"kind\":\"DENTAL\",\"sittings\":["
                + "{\"addOptionKey\":\"opd_sitting\",\"chargePaise\":100000},{\"addOptionKey\":\"day_care_admission\",\"chargePaise\":400000}]}";
        return new Setup(clinic, patient, doctor, postKeyed(OPD + "/emr/courses", doctor, body, 201));
    }

    private JsonNode pay(Setup s, String type, long paise, String againstSitting) throws Exception {
        return postKeyed(BILLING + "/courses/" + s.course() + "/payments", s.clinic().owner(), "{\"type\":\"" + type + "\",\"amountPaise\":" + paise
                + ",\"mode\":\"UPI\",\"useCredit\":false,\"againstSittingId\":" + (againstSitting == null ? "null" : "\"" + againstSitting + "\"") + "}", 201);
    }

    private JsonNode account(Setup s) throws Exception {
        return ok(HttpMethod.GET, BILLING + "/courses/" + s.course() + "/account", s.clinic().owner(), null, 200);
    }

    private JsonNode course(Setup s) throws Exception {
        return ok(HttpMethod.GET, OPD + "/emr/courses/" + s.course(), s.clinic().owner(), null, 200);
    }

    private Map<String, Object> admitBody(Setup s, String sittingId) throws Exception {
        Map<String, Object> m = body(s.patient().id(), s.clinic().doctorMembershipId(), newBed(s.clinic(), "gen"));
        m.put("treatmentCourseSittingId", sittingId);
        return m;
    }

    private static JsonNode deposit(JsonNode detail, String source) {
        for (JsonNode d : detail.get("deposits")) {
            if (source.equals(d.get("source").asText())) {
                return d;
            }
        }
        return null;
    }

    private String stateOf(Clinic clinic, String sql, Object... params) throws SQLException {
        return db().inTenant(clinic.tenantId(), c -> {
            DbAccess.execute(c, "savepoint probe");
            try {
                DbAccess.execute(c, sql, params);
            } catch (SQLException e) {
                DbAccess.execute(c, "rollback to savepoint probe");
                return e.getSQLState();
            }
            DbAccess.execute(c, "rollback to savepoint probe");
            return "ACCEPTED";
        });
    }

    // ================= Admit now =================

    @Test
    void admittingForAPlannedSittingLinksItAndCarriesTheEarmarkedCreditAsACourseDepositWithNoModeAndTheDeskDepositStaysAsEntered() throws Exception {
        Setup s = setup();
        pay(s, "IPD_DEPOSIT", 150000, s.s2());
        JsonNode admitted = admitOk(s.clinic(), admitBody(s, s.s2()));
        String admissionId = admitted.get("id").asText();
        JsonNode desk = deposit(admitted, "DESK");
        JsonNode fromCourse = deposit(admitted, "COURSE");
        assertThat(admitted.get("deposits")).hasSize(2);
        assertThat(desk.get("amountPaise").asLong()).isEqualTo(1_000_000L);
        assertThat(desk.get("mode").asText()).isEqualTo("UPI");
        assertThat(desk.get("courseNo").isNull()).isTrue();
        assertThat(fromCourse.get("amountPaise").asLong()).isEqualTo(150000L);
        assertThat(fromCourse.get("mode").isNull()).as("no cash was taken at the desk").isTrue();
        assertThat(fromCourse.get("courseNo").asText()).isEqualTo("TC-0001");
        assertThat(fromCourse.get("collectedByName").asText()).isNotBlank();
        assertThat(admitted.get("depositPaise").asLong()).as("the deposit total counts both").isEqualTo(1_150_000L);
        // the stored rows
        assertThat(values(s.clinic(), "select source, mode, course_credit_transfer_id is not null from ipd.deposit where amount_paise = 150000")).containsExactly("COURSE", null, "t");
        assertThat(values(s.clinic(), "select amount_paise::text, course_id::text, sitting_id::text, admission_id::text from billing.course_credit_transfer"))
                .containsExactly("150000", s.course(), s.s2(), admissionId);
        assertThat(values(s.clinic(), "select treatment_course_id::text, treatment_course_sitting_id::text from ipd.admission where id = ?::uuid", admissionId))
                .containsExactly(s.course(), s.s2());
        // the audit of the admission carries the course and the carried amount; the carry has its own entry
        JsonNode after = objectMapper.readTree(audit(s.clinic(), "IPD_ADMITTED").get(0)[3]);
        assertThat(after.get("treatmentCourseId").asText()).isEqualTo(s.course());
        assertThat(after.get("carriedPaise").asLong()).isEqualTo(150000L);
        assertThat(audits(s.clinic(), "COURSE_CREDIT_CARRIED")).isEqualTo(1);
        assertThat(audits(s.clinic(), "COURSE_SITTING_ADMISSION_LINKED")).isEqualTo(1);
        // the course now shows the sitting as billed in IPD with the admission's number and total
        JsonNode sitting = course(s).get("sittings").get(1);
        assertThat(sitting.get("admissionId").asText()).isEqualTo(admissionId);
        assertThat(sitting.get("settledInIpd").asBoolean()).isTrue();
        assertThat(sitting.get("encounterRef").asText()).isEqualTo(admitted.get("ipNumber").asText());
        assertThat(sitting.get("status").asText()).isEqualTo("TODAY");
        assertThat(sitting.get("ipdBillTotalPaise").isNull()).isFalse();
        // and the account has left the stay to the IPD bill: the estimate is the OPD sitting only, the carried credit is a negative line
        JsonNode account = account(s);
        assertThat(account.get("estimatePaise").asLong()).isEqualTo(100000L);
        assertThat(account.get("paidPaise").asLong()).isZero();
        assertThat(account.get("creditPaise").asLong()).isZero();
        assertThat(account.get("balancePaise").asLong()).isEqualTo(100000L);
        assertThat(account.get("nextPlannedAdmission").isNull()).isTrue();
        assertThat(account.get("ledger")).hasSize(2);
        JsonNode carried = account.get("ledger").get(1);
        assertThat(carried.get("kind").asText()).isEqualTo("CARRIED_TO_IPD");
        assertThat(carried.get("amountPaise").asLong()).isEqualTo(-150000L);
        assertThat(carried.get("against").asText()).isEqualTo("Admission " + admitted.get("ipNumber").asText());
        assertThat(account.get("ledger").get(0).get("amountPaise").asLong() + carried.get("amountPaise").asLong()).isEqualTo(account.get("creditPaise").asLong());
        // a discharged admission makes the sitting done
        discharge(s.clinic(), admissionId);
        assertThat(course(s).get("sittings").get(1).get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void theCarriedAmountIsNeverMoreThanTheCoursesCreditNorMoreThanTenLakhAndWithNoEarmarkedDepositNothingIsCarried() throws Exception {
        // the course's other sitting already used part of the money: Rs 2,500 paid, Rs 2,000 of work done, Rs 500 credit left
        Setup capped = setup();
        pay(capped, "ADVANCE", 100000, null);
        pay(capped, "IPD_DEPOSIT", 150000, capped.s2());
        commitAsTenant(capped.clinic().tenantId(), "update opd.treatment_course_sitting set charge_paise = 200000, done_at = now() where id = ?::uuid", capped.s1());
        assertThat(account(capped).get("creditPaise").asLong()).isEqualTo(50000L);
        JsonNode admitted = admitOk(capped.clinic(), admitBody(capped, capped.s2()));
        assertThat(deposit(admitted, "COURSE").get("amountPaise").asLong()).as("capped at the credit").isEqualTo(50000L);
        // Rs 15 lakh earmarked: only Rs 10,00,000 goes (the limit of one IPD deposit), the rest stays on the course
        Setup lakhs = setup();
        pay(lakhs, "IPD_DEPOSIT", 150_000_000L, lakhs.s2());
        JsonNode big = admitOk(lakhs.clinic(), admitBody(lakhs, lakhs.s2()));
        assertThat(deposit(big, "COURSE").get("amountPaise").asLong()).isEqualTo(100_000_000L);
        assertThat(account(lakhs).get("paidPaise").asLong()).as("Rs 5 lakh stays on the course").isEqualTo(50_000_000L);
        assertThat(account(lakhs).get("creditPaise").asLong()).isEqualTo(50_000_000L);
        // nothing earmarked: the admission is made and linked and no course deposit or transfer exists
        Setup none = setup();
        pay(none, "ADVANCE", 100000, null);
        JsonNode plain = admitOk(none.clinic(), admitBody(none, none.s2()));
        assertThat(plain.get("deposits")).hasSize(1);
        assertThat(deposit(plain, "COURSE")).isNull();
        assertThat(rows(none.clinic(), "deposit")).isEqualTo(1);
        assertThat(count(none.clinic(), "select count(*) from billing.course_credit_transfer")).isZero();
        assertThat(course(none).get("sittings").get(1).get("admissionId").asText()).isEqualTo(plain.get("id").asText());
        JsonNode afterNone = objectMapper.readTree(audit(none.clinic(), "IPD_ADMITTED").get(0)[3]);
        assertThat(afterNone.get("carriedPaise").asLong()).isZero();
        assertThat(account(none).get("paidPaise").asLong()).as("the advance stays").isEqualTo(100000L);
    }

    @Test
    void aSittingThatCannotBeAdmittedIs409AndNothingIsWrittenNoAdmissionNoBedNoDepositNoLinkAndNoTransfer() throws Exception {
        Setup s = setup();
        pay(s, "IPD_DEPOSIT", 150000, s.s2());
        // a second patient's own course
        Person other = person(s.clinic(), "Somebody Else");
        JsonNode visit = ok(HttpMethod.POST, OPD + "/visits", s.clinic().owner(),
                "{\"patientId\":\"" + other.id() + "\",\"doctorMembershipId\":\"" + s.clinic().doctorMembershipId() + "\"}", 201);
        JsonNode opened = ok(HttpMethod.POST, OPD + "/emr/records/open", s.doctor(), "{\"encounterType\":\"OPD_VISIT\",\"encounterId\":\"" + visit.get("id").asText() + "\"}", 201);
        JsonNode otherCourse = postKeyed(OPD + "/emr/courses", s.doctor(), "{\"fromRecordId\":\"" + opened.get("record").get("id").asText()
                + "\",\"kind\":\"DENTAL\",\"sittings\":[{\"addOptionKey\":\"day_care_admission\",\"chargePaise\":300000}]}", 201);
        String foreignSitting = otherCourse.get("sittings").get(0).get("id").asText();
        long admissions = rows(s.clinic(), "admission");
        long deposits = rows(s.clinic(), "deposit");
        String[] refused = {s.s1(), foreignSitting, UUID.randomUUID().toString()};
        for (String sitting : refused) {
            JsonNode problem = problem(call(HttpMethod.POST, ADMISSIONS, s.clinic().owner(), js(admitBody(s, sitting))), 409);
            assertThat(problem.get("type").asText()).as(sitting).isEqualTo(PROBLEM_BASE + "course-sitting-not-available");
        }
        assertThat(rows(s.clinic(), "admission")).isEqualTo(admissions);
        assertThat(rows(s.clinic(), "deposit")).isEqualTo(deposits);
        assertThat(count(s.clinic(), "select count(*) from billing.course_credit_transfer")).isZero();
        assertThat(count(s.clinic(), "select count(*) from opd.treatment_course_sitting where ipd_admission_id is not null")).isZero();
        assertThat(audits(s.clinic(), "IPD_ADMITTED")).isZero();
        // the beds made for the refused tries are all still free (the admission would have taken one)
        assertThat(count(s.clinic(), "select count(*) from ipd.bed b where not exists (select 1 from ipd.admission a where a.bed_id = b.id)")).isEqualTo(3);
        // the real one works, then the same sitting a second time is refused and writes nothing more
        JsonNode firstAdmission = admitOk(s.clinic(), admitBody(s, s.s2()));
        discharge(s.clinic(), firstAdmission.get("id").asText()); // else the same patient answers already-admitted before the sitting is looked at
        long afterFirst = rows(s.clinic(), "admission");
        JsonNode again = problem(call(HttpMethod.POST, ADMISSIONS, s.clinic().owner(), js(admitBody(s, s.s2()))), 409);
        assertThat(again.get("type").asText()).isEqualTo(PROBLEM_BASE + "course-sitting-not-available");
        assertThat(rows(s.clinic(), "admission")).isEqualTo(afterFirst);
        assertThat(count(s.clinic(), "select count(*) from billing.course_credit_transfer")).isEqualTo(1);
        // the other patient's own sitting does not belong to the first patient even when the course is the same clinic's
        Map<String, Object> swapped = body(other.id(), s.clinic().doctorMembershipId(), newBed(s.clinic(), "gen"));
        swapped.put("treatmentCourseSittingId", s.s2());
        problem(call(HttpMethod.POST, ADMISSIONS, s.clinic().owner(), js(swapped)), 409);
    }

    @Test
    void aSittingOfAnotherClinicOrAnInvalidIdIsRefusedAndAPlannedSelfAdmissionStillNeedsItsDeskDepositWhateverTheCourseHolds() throws Exception {
        Setup s = setup();
        Setup foreign = setup();
        pay(s, "IPD_DEPOSIT", 150000, s.s2());
        problem(call(HttpMethod.POST, ADMISSIONS, s.clinic().owner(), js(admitBody(s, foreign.s2()))), 409);
        Map<String, Object> notAnId = admitBody(s, s.s2());
        notAnId.put("treatmentCourseSittingId", "not-a-uuid");
        assertThat(call(HttpMethod.POST, ADMISSIONS, s.clinic().owner(), js(notAnId)).andReturn().getResponse().getStatus()).isEqualTo(400);
        // the carried credit is not counted toward the desk minimum: a planned Self admission with no desk deposit is refused as before
        Map<String, Object> noDesk = admitBody(s, s.s2());
        noDesk.put("depositPaise", 0);
        noDesk.remove("depositMode");
        assertThat(call(HttpMethod.POST, ADMISSIONS, s.clinic().owner(), js(noDesk)).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(count(s.clinic(), "select count(*) from billing.course_credit_transfer")).isZero();
        assertThat(count(s.clinic(), "select count(*) from opd.treatment_course_sitting where ipd_admission_id is not null")).isZero();
        assertThat(rows(s.clinic(), "admission")).isZero();
        // the sitting is still free: the right request works
        admitOk(s.clinic(), admitBody(s, s.s2()));
    }

    @Test
    void whoMayAdmitIsAsBeforeAndTheCourseFieldChangesNoRole() throws Exception {
        Setup s = setup();
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            assertThat(call(HttpMethod.POST, ADMISSIONS, s.clinic().as(role), js(admitBody(s, s.s2()))).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
        }
        assertThat(call(HttpMethod.POST, ADMISSIONS, anonymousToken(), js(admitBody(s, s.s2()))).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(rows(s.clinic(), "admission")).isZero();
        assertThat(ok(HttpMethod.POST, ADMISSIONS, s.clinic().as("FRONT_DESK"), js(admitBody(s, s.s2())), 201).get("id").asText()).isNotBlank();
    }

    private static RequestPostProcessor anonymousToken() {
        return request -> request;
    }

    @Test
    void everyDepositEntryNowCarriesASourceAndACourseNumberAndAnOrdinaryAdmissionIsDesk() throws Exception {
        Clinic clinic = hospital();
        JsonNode admitted = quickAdmit(clinic);
        JsonNode first = admitted.get("deposits").get(0);
        assertThat(first.get("source").asText()).isEqualTo("DESK");
        assertThat(first.get("courseNo").isNull()).isTrue();
        assertThat(first.get("mode").asText()).isEqualTo("UPI");
        JsonNode more = deposit(clinic, admitted.get("id").asText(), 250000);
        for (JsonNode d : more.get("deposits")) {
            assertThat(d.get("source").asText()).isEqualTo("DESK");
            assertThat(d.has("courseNo")).isTrue();
            assertThat(d.get("mode").isNull()).isFalse();
        }
        assertThat(values(clinic, "select count(*) from ipd.deposit where source = 'DESK' and course_credit_transfer_id is null")).containsExactly("2");
        JsonNode listed = detail(clinic, admitted.get("id").asText());
        assertThat(listed.get("deposits").get(1).get("source").asText()).isEqualTo("DESK");
        JsonNode after = objectMapper.readTree(audit(clinic, "IPD_ADMITTED").get(0)[3]);
        assertThat(after.has("treatmentCourseId")).as("the audit of an ordinary admission is as before").isFalse();
        assertThat(after.has("carriedPaise")).isFalse();
        assertThat(values(clinic, "select treatment_course_id is null and treatment_course_sitting_id is null from ipd.admission")).containsExactly("t");
    }

    // ================= S19, S20 · the stored shape of the changed ipd tables =================

    private static final String ADMISSION_INSERT = "insert into ipd.admission (patient_id, ip_number, bed_id, doctor_membership_id, admission_type, payer, "
            + "consent_signed, status, admitted_by, treatment_course_id, treatment_course_sitting_id) values (?, ?, ?, ?, 'planned', 'Self', true, 'ADMITTED', ?, ?, ?)";

    @Test
    void anAdmissionHoldsBothCourseIdsOrNeitherAndOneSittingGoesToOneAdmissionOnly() throws Exception {
        Clinic clinic = hospital();
        Person patient = person(clinic, "Mohan Joshi");
        UUID doctor = clinic.doctorMembershipId();
        UUID owner = clinic.ownerUserId();
        UUID course = UUID.randomUUID();
        UUID sitting = UUID.randomUUID();
        assertThat(stateOf(clinic, ADMISSION_INSERT, patient.id(), "S19/1", newBed(clinic, "gen"), doctor, owner, course, null)).as("only one id").isEqualTo("23514");
        assertThat(stateOf(clinic, ADMISSION_INSERT, patient.id(), "S19/2", newBed(clinic, "gen"), doctor, owner, null, sitting)).as("only the other id").isEqualTo("23514");
        assertThat(stateOf(clinic, ADMISSION_INSERT, patient.id(), "S19/3", newBed(clinic, "gen"), doctor, owner, null, null)).as("the old shape").isEqualTo("ACCEPTED");
        assertThat(stateOf(clinic, ADMISSION_INSERT, patient.id(), "S19/4", newBed(clinic, "gen"), doctor, owner, course, sitting)).as("both").isEqualTo("ACCEPTED");
        // the same sitting on two admissions: committed once, refused the second time
        commitAsTenant(clinic.tenantId(), ADMISSION_INSERT, patient.id(), "S19/5", newBed(clinic, "gen"), doctor, owner, course, sitting);
        assertThat(stateOf(clinic, ADMISSION_INSERT, person(clinic, "Other Patient").id(), "S19/6", newBed(clinic, "gen"), doctor, owner, UUID.randomUUID(), sitting))
                .as("one sitting, one admission").isEqualTo("23505");
        assertThat(values(clinic, "select count(*) from ipd.admission where treatment_course_sitting_id = ?::uuid", sitting.toString())).containsExactly("1");
        // the columns are set at admission only: they cannot be updated afterwards
        assertDenied(clinic, "update ipd.admission set treatment_course_id = gen_random_uuid()");
        assertDenied(clinic, "update ipd.admission set treatment_course_sitting_id = null");
    }

    @Test
    void aDepositIsDeskWithAModeOrCourseWithNoModeAndATransferAndTheTableStaysAppendOnly() throws Exception {
        Clinic clinic = hospital();
        JsonNode admitted = quickAdmit(clinic);
        String id = admitted.get("id").asText();
        UUID owner = clinic.ownerUserId();
        String base = "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by, source, course_credit_transfer_id) values (?::uuid, 100000, ?, ?, ?, ?)";
        UUID transfer = UUID.randomUUID();
        // the old shape: mode and no source columns
        assertThat(stateOf(clinic, "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by) values (?::uuid, 100000, 'UPI', ?)", id, owner)).isEqualTo("ACCEPTED");
        assertThat(stateOf(clinic, base, id, "UPI", owner, "DESK", null)).isEqualTo("ACCEPTED");
        assertThat(stateOf(clinic, base, id, null, owner, "COURSE", transfer)).as("a course deposit has no mode").isEqualTo("ACCEPTED");
        assertThat(stateOf(clinic, base, id, "UPI", owner, "COURSE", transfer)).as("course with a mode").isEqualTo("23514");
        assertThat(stateOf(clinic, base, id, null, owner, "DESK", null)).as("desk with no mode").isIn("23514", "23502");
        assertThat(stateOf(clinic, base, id, null, owner, "COURSE", null)).as("course with no transfer").isEqualTo("23514");
        assertThat(stateOf(clinic, base, id, "UPI", owner, "DESK", transfer)).as("desk with a transfer").isEqualTo("23514");
        assertThat(stateOf(clinic, base, id, "UPI", owner, "BANK", null)).as("source outside the list").isEqualTo("23514");
        // a transfer is used once
        commitAsTenant(clinic.tenantId(), "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by, source, course_credit_transfer_id) "
                + "values (?::uuid, 100000, null, ?, 'COURSE', ?)", id, owner, transfer);
        assertThat(stateOf(clinic, base, id, null, owner, "COURSE", transfer)).isEqualTo("23505");
        // the amount limit of one deposit is the merged one
        assertThat(stateOf(clinic, "insert into ipd.deposit (admission_id, amount_paise, mode, collected_by) values (?::uuid, 100000100, 'UPI', ?)", id, owner)).isEqualTo("23514");
        // append-only as before
        assertDenied(clinic, "update ipd.deposit set source = 'DESK'");
        assertDenied(clinic, "delete from ipd.deposit");
        assertDenied(clinic, "truncate ipd.deposit");
        // another clinic cannot see these rows
        Clinic other = hospital();
        assertThat(rows(other, "deposit")).isZero();
    }
}
