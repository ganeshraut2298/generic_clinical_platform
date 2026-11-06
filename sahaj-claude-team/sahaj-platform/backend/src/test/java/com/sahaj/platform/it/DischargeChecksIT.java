package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-27 the checks of the sheet, the sign-off and the discharge on the real {@code sahaj_test} database: their order
 * and exact messages (404, not-admitted, ipd-readonly, summary-not-signed, bill-changed, refund-needs-owner, the
 * mode), roles, invalid bodies, module state, what a discharged admission refuses, and tenant isolation. Nothing is
 * written by a refused call. Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeChecksIT extends DischargeSupport {

    private static final String TEAM = "/api/v1/platform/team";

    // ---------------- order of the checks ----------------

    @Test
    void theDischargeChecksRunInTheStoryOrderAndEachAnswerHasItsExactMessage() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor frontDesk = otherPerson(clinic, "FRONT_DESK"); // a real front desk member: R3 reads the role from the membership
        String id = quickAdmit(clinic).get("id").asText();          // total 1600, deposit 10000: a refund of 8400

        // 404 beats everything that follows
        problem(postDischarge(clinic.owner(), UUID.randomUUID(), dischargeBody("normal", "general", 1L, null)), 404);

        // not signed (even with a wrong total and no mode)
        JsonNode unsigned = problem(postDischarge(frontDesk, id, dischargeBody("normal", "general", 7L, null)), 409);
        assertType(unsigned, "summary-not-signed");
        assertThat(unsigned.get("detail").asText()).isEqualTo(SIGN_FIRST);

        signNormal(clinic, id);
        Written before = written(clinic, id);

        // wrong total beats the refund rule and the missing mode
        JsonNode changed = problem(postDischarge(frontDesk, id, dischargeBody("normal", "general", DAY + 1, null)), 409);
        assertType(changed, "bill-changed");
        assertThat(changed.get("detail").asText()).isEqualTo(BILL_CHANGED);
        assertType(problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", 0L, "UPI")), 409), "bill-changed");

        // the right total: a refund by front desk is refused, even without a mode (403 beats 400)
        JsonNode refund = problem(postDischarge(frontDesk, id, dischargeBody("normal", "general", DAY, null)), 403);
        assertType(refund, "refund-needs-owner");
        assertThat(refund.get("detail").asText()).isEqualTo(ONLY_OWNER_REFUNDS);
        assertType(problem(postDischarge(frontDesk, id, dischargeBody("normal", "general", DAY, "Cash")), 403),
                "refund-needs-owner");

        // the owner without a mode, or with a mode that does not exist: 400 on the field mode
        for (String mode : new String[] {null, "Bitcoin", "upi", ""}) {
            JsonNode p = problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, mode)), 400);
            assertFieldError(p, "mode");
            assertThat(p.get("fieldErrors").get(0).get("message").asText()).isEqualTo(CHOOSE_MODE);
        }
        assertThat(written(clinic, id)).as("every refusal left everything as it was").isEqualTo(before);

        // and the owner with everything right goes through
        ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), dischargeBody("normal", "general", DAY, "UPI"), 200);
    }

    @Test
    void aDifferenceInTheDepositsAloneIsBillChangedAndComesBeforeTheRefundRuleAndTheMissingMode() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor frontDesk = otherPerson(clinic, "FRONT_DESK"); // a real front desk member (ADR-007 R3)
        String id = quickAdmit(clinic).get("id").asText();          // total 1600, deposit 10000: a refund of 8400
        signNormal(clinic, id);
        Written before = written(clinic, id);

        // the right total, deposits that differ (higher, lower, zero): bill-changed for front desk and for the owner,
        // before the refund rule (403) and before the missing mode (400)
        for (long wrong : new long[] {DEPOSIT + 1, DEPOSIT - 1, 0L}) {
            for (String mode : new String[] {null, "UPI"}) {
                JsonNode fd = problem(postDischarge(frontDesk, id,
                        dischargeBody("normal", "general", DAY, wrong, mode)), 409);
                assertType(fd, "bill-changed");
                assertThat(fd.get("detail").asText()).isEqualTo(BILL_CHANGED);
                assertType(problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, wrong, mode)), 409),
                        "bill-changed");
            }
        }
        assertThat(written(clinic, id)).as("every refusal left everything as it was").isEqualTo(before);

        // with the right deposits the refund rule applies again, so the deposits were what was refused
        assertType(problem(postDischarge(frontDesk, id,
                dischargeBody("normal", "general", DAY, DEPOSIT, "UPI")), 403), "refund-needs-owner");
        ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), dischargeBody("normal", "general", DAY, DEPOSIT, "UPI"), 200);
    }

    @Test
    void frontDeskMayDischargeWhenSomethingIsToCollectOrTheBillIsSettled() throws Exception {
        Clinic clinic = hospital();
        RequestPostProcessor frontDesk = otherPerson(clinic, "FRONT_DESK");
        String collect = admitGeneral(clinic, 10).get("id").asText();
        String settled = admitGeneral(clinic, 6).get("id").asText();
        charge(clinic, settled, "dressing");
        for (String id : new String[] {collect, settled}) {
            sign(clinic, id, "normal", "general");
        }
        long total = totalOf(sheetAs(clinic, "FRONT_DESK", collect));
        JsonNode a = ok(HttpMethod.POST, dischargeUrl(collect), frontDesk,
                dischargeBody("normal", "general", total, "UPI"), 200);
        assertThat(a.get("finalBill").get("outcome").asText()).isEqualTo("collected");
        JsonNode b = ok(HttpMethod.POST, dischargeUrl(settled), frontDesk,
                dischargeBody("normal", "general", totalOf(sheet(clinic, settled)), null), 200);
        assertThat(b.get("finalBill").get("outcome").asText()).isEqualTo("settled");
    }

    @Test
    void aDemotedOwnersOldTokenCannotRefundADeposit() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, id);
        Member second = addMember(clinic, "OWNER");
        UUID firstId = UUID.fromString(ok(HttpMethod.GET, "/api/v1/platform/me", clinic.owner(), null, 200)
                .get("membershipId").asText());
        RequestPostProcessor secondOwner = bearer(login(second.mobile()).accessToken());
        ok(HttpMethod.PATCH, TEAM + "/" + firstId, secondOwner, "{\"role\":\"NURSE\"}", 200);

        Written before = written(clinic, id);
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, "UPI")), 403);
        assertThat(written(clinic, id)).isEqualTo(before);
        // the real owner refunds
        ok(HttpMethod.POST, dischargeUrl(id), secondOwner, dischargeBody("normal", "general", DAY, "UPI"), 200);
    }

    @Test
    void theNotAdmittedAnswerComesBeforeReadOnlyAndReadOnlyBeforeTheSignatureAndTheBill() throws Exception {
        Clinic clinic = hospital();
        String unsigned = quickAdmit(clinic).get("id").asText();
        String signed = quickAdmit(clinic).get("id").asText();
        String done = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, signed);
        signAndDischarge(clinic, done);
        expireTrial(clinic);
        Written before = written(clinic, unsigned);

        JsonNode ro = problem(postDischarge(clinic.owner(), unsigned, dischargeBody("normal", "general", 7L, null)), 409);
        assertType(ro, "ipd-readonly");
        assertThat(ro.get("detail").asText()).isEqualTo(READ_ONLY);
        assertType(problem(postDischarge(clinic.owner(), signed, dischargeBody("normal", "general", DAY, "UPI")), 409), "ipd-readonly");
        JsonNode gone = problem(postDischarge(clinic.owner(), done, dischargeBody("normal", "general", DAY, "UPI")), 409);
        assertType(gone, "not-admitted");
        assertThat(gone.get("detail").asText()).isEqualTo(NOT_ADMITTED_DISCHARGE);
        problem(postDischarge(clinic.owner(), UUID.randomUUID(), dischargeBody("normal", "general", DAY, "UPI")), 404);

        // the sign-off: 404, then not-admitted, then read-only
        assertType(problem(signAs(clinic, clinic.as("DOCTOR"), unsigned, "normal", "general"), 409), "ipd-readonly");
        assertType(problem(signAs(clinic, clinic.as("DOCTOR"), done, "normal", "general"), 409), "not-admitted");
        problem(signAs(clinic, clinic.as("DOCTOR"), UUID.randomUUID(), "normal", "general"), 404);

        // reading still works and nothing can be done from the sheet
        for (String role : READERS) {
            JsonNode s = sheetAs(clinic, role, unsigned);
            assertThat(s.get("canSign").asBoolean()).as(role).isFalse();
            assertThat(s.get("canDischarge").asBoolean()).as(role).isFalse();
            assertThat(s.get("canRefund").asBoolean()).as(role).isFalse();
        }
        // reading the sheet writes one access row per open (security review M3) and nothing else
        assertThat(written(clinic, unsigned)).usingRecursiveComparison().ignoringFields("access").isEqualTo(before);
        assertThat(written(clinic, unsigned).access()).isEqualTo(before.access() + READERS.length);
        assertThat(count(clinic, "select count(*) from ipd.discharge where admission_id = ?::uuid", signed)).isZero();
    }

    // ---------------- roles ----------------

    @Test
    void onlyDoctorAndOwnerSignOnlyOwnerAndFrontDeskDischargeAndAnonymousGets401() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        Written before = written(clinic, id);
        for (String role : NON_SIGNERS) {
            problem(signAs(clinic, clinic.as(role), id, "normal", "general"), 403);
            // the role is checked before the input and before the admission
            problem(signAs(clinic, clinic.as(role), UUID.randomUUID(), "bad", "bad"), 403);
        }
        for (String role : NON_DISCHARGERS) {
            problem(postDischarge(clinic.as(role), id, dischargeBody("normal", "general", DAY, "UPI")), 403);
            problem(postDischarge(clinic.as(role), UUID.randomUUID(), "{}"), 403);
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, dischargeUrl(id), clinic.as(role), null), 403);
        }
        assertThat(written(clinic, id)).isEqualTo(before);

        mvc.perform(get(dischargeUrl(id))).andExpect(status().isUnauthorized());
        mvc.perform(put(signoffUrl(id)).contentType(MediaType.APPLICATION_JSON).content(signBody("normal", "general")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(dischargeUrl(id)).contentType(MediaType.APPLICATION_JSON)
                .content(dischargeBody("normal", "general", DAY, "UPI"))).andExpect(status().isUnauthorized());

        for (String role : SIGNERS) {
            ok(HttpMethod.PUT, signoffUrl(id), clinic.as(role), signBody("normal", "general"), 200);
        }
        assertThat(rows(clinic, "discharge_signoff")).as("the same choice twice by two signers: one row").isOne();
        for (String role : READERS) {
            JsonNode s = sheetAs(clinic, role, id);
            assertThat(s.get("canSign").asBoolean()).as(role + " canSign").isEqualTo("DOCTOR".equals(role) || "OWNER".equals(role));
            assertThat(s.get("canDischarge").asBoolean()).as(role + " canDischarge")
                    .isEqualTo("OWNER".equals(role) || "FRONT_DESK".equals(role));
            assertThat(s.get("canRefund").asBoolean()).as(role + " canRefund").isEqualTo("OWNER".equals(role));
        }
    }

    // ---------------- invalid input ----------------

    @Test
    void invalidBodiesGet400WithProblemDetailsBeforeAnyLookupAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, id);
        Written before = written(clinic, id);
        UUID unknown = UUID.randomUUID();
        String[][] discharge = {
                {"{}", "type"}, {"{\"templateKey\":\"general\",\"expectedTotalPaise\":1}", "type"},
                {dischargeBody("Normal", "general", 1L, null), "type"}, {dischargeBody(null, "general", 1L, null), "type"},
                {dischargeBody("burial", "general", 1L, null), "type"},
                {"{\"type\":\"normal\",\"expectedTotalPaise\":1}", "templateKey"},
                {dischargeBody("normal", "General medicine", 1L, null), "templateKey"},
                {dischargeBody("normal", null, 1L, null), "templateKey"},
                {"{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedDepositsPaise\":1000000}", "expectedTotalPaise"},
                {dischargeBody("normal", "general", -1L, null), "expectedTotalPaise"},
                {dischargeBody("normal", "general", 10_000_000_001L, null), "expectedTotalPaise"},
                {"{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":null}", "expectedTotalPaise"},
                // the deposits the user saw: missing, null, negative and too large (AC7, F-27-2)
                {"{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":1}", "expectedDepositsPaise"},
                {dischargeBody("normal", "general", 1L, null, null), "expectedDepositsPaise"},
                {dischargeBody("normal", "general", 1L, -1L, null), "expectedDepositsPaise"},
                {dischargeBody("normal", "general", 1L, 10_000_000_001L, null), "expectedDepositsPaise"},
                // the mode is what fails here: total and deposits are valid
                {dischargeBody("normal", "general", 1L, DEPOSIT, "Bitcoin"), "mode"}};
        for (String[] bad : discharge) {
            JsonNode p = problem(postDischarge(clinic.owner(), unknown, bad[0]), 400);
            assertFieldError(p, bad[1]);
            JsonNode q = problem(postDischarge(clinic.owner(), id, bad[0]), 400);
            assertFieldError(q, bad[1]);
            if ("expectedDepositsPaise".equals(bad[1])) {
                assertThat(q.get("fieldErrors").get(0).get("message").asText()).as(bad[0])
                        .isEqualTo("Review the final bill and try again");
            }
        }
        // the deposits as text, as an object or as a fraction are not numbers: 400 and nothing is written
        for (String malformed : new String[] {"{", "[]", "\"x\"",
                "{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":\"abc\"}",
                "{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":1,\"expectedDepositsPaise\":\"abc\"}",
                "{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":1,\"expectedDepositsPaise\":{}}"}) {
            problem(postDischarge(clinic.owner(), id, malformed), 400);
        }
        // a valid total with the deposits missing: the field error is on the deposits
        assertFieldError(problem(postDischarge(clinic.owner(), id,
                "{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":" + DAY + ",\"mode\":\"UPI\"}"), 400),
                "expectedDepositsPaise");
        problem(call(HttpMethod.POST, dischargeUrl(id), clinic.owner(), null), 400);
        problem(postDischarge(clinic.owner(), id, "null"), 400);
        problem(call(HttpMethod.POST, ADMISSIONS + "/not-a-uuid/discharge", clinic.owner(), "{}"), 400);

        String[][] sign = {{"{}", "type"}, {signBody("Normal", "general"), "type"}, {signBody(null, "general"), "type"},
                {"{\"type\":\"normal\"}", "templateKey"}, {signBody("normal", "Surgical"), "templateKey"}};
        for (String[] bad : sign) {
            assertFieldError(problem(call(HttpMethod.PUT, signoffUrl(unknown), clinic.as("DOCTOR"), bad[0]), 400), bad[1]);
        }
        problem(call(HttpMethod.PUT, signoffUrl(id), clinic.as("DOCTOR"), "{"), 400);
        problem(call(HttpMethod.PUT, signoffUrl(id), clinic.as("DOCTOR"), null), 400);
        assertThat(written(clinic, id)).isEqualTo(before);
    }

    @Test
    void theSignoffIgnoresExtraFieldsAndTheSignerIsTheCallerNotTheBody() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        String body = "{\"type\":\"normal\",\"templateKey\":\"general\",\"signedBy\":\"" + UUID.randomUUID()
                + "\",\"signedAt\":\"2020-01-01T00:00:00Z\",\"tenantId\":\"" + UUID.randomUUID() + "\"}";
        ok(HttpMethod.PUT, signoffUrl(id), clinic.as("DOCTOR"), body, 200);
        assertThat(values(clinic, "select signed_by::text, (signed_at > now() - interval '1 minute')::text "
                + "from ipd.discharge_signoff")).containsExactly(clinic.ownerUserId().toString(), "true");
    }

    // ---------------- module state ----------------

    @Test
    void ipdOffTheSheetTheSignoffAndTheDischargeAre403ModuleOffAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic();
        String id = UUID.randomUUID().toString();
        for (String role : READERS) {
            assertType(problem(call(HttpMethod.GET, dischargeUrl(id), clinic.as(role), null), 403), "module-off");
        }
        for (String role : SIGNERS) {
            assertType(problem(signAs(clinic, clinic.as(role), id, "normal", "general"), 403), "module-off");
        }
        for (String role : DISCHARGERS) {
            assertType(problem(postDischarge(clinic.as(role), id, dischargeBody("normal", "general", 1L, "UPI")), 403), "module-off");
        }
        assertThat(rows(clinic, "discharge_signoff")).isZero();
        assertThat(rows(clinic, "discharge")).isZero();
        assertThat(rows(clinic, "final_bill")).isZero();
        assertThat(ipdAudits(clinic)).isZero();
    }

    // ---------------- a discharged admission refuses everything ----------------

    @Test
    void aDepositAChargeATransferASignoffAndASecondDischargeOnADischargedAdmissionAre409NotAdmitted() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        UUID spare = newBed(clinic, "gen");
        signAndDischarge(clinic, id);
        Written before = written(clinic, id);

        for (String role : DISCHARGERS) {
            assertType(problem(call(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", clinic.as(role),
                    "{\"amountPaise\":100000,\"mode\":\"Cash\"}"), 409), "not-admitted");
        }
        for (String role : CHARGERS) {
            assertType(problem(call(HttpMethod.POST, ADMISSIONS + "/" + id + "/charges", clinic.as(role),
                    "{\"serviceKey\":\"cbc\"}"), 409), "not-admitted");
            assertType(problem(moveAs(clinic, role, id, spare), 409), "not-admitted");
        }
        JsonNode second = problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, "UPI")), 409);
        assertType(second, "not-admitted");
        assertThat(second.get("detail").asText()).isEqualTo(NOT_ADMITTED_DISCHARGE);
        JsonNode signAgain = problem(signAs(clinic, clinic.as("DOCTOR"), id, "lama", "surgical"), 409);
        assertType(signAgain, "not-admitted");
        assertThat(signAgain.get("detail").asText()).isEqualTo(NOT_ADMITTED_SIGN);
        JsonNode sheet = problem(call(HttpMethod.GET, dischargeUrl(id), clinic.owner(), null), 409);
        assertType(sheet, "not-admitted");
        assertThat(sheet.get("detail").asText()).isEqualTo(NOT_ADMITTED_SHEET);

        assertThat(written(clinic, id)).as("nothing doubled, nothing added").isEqualTo(before);
        assertThat(bedStatus(clinic, spare)).isEqualTo("free");
        // and the spare bed is free for the next patient
        quickAdmitTo(clinic, spare);
    }

    private void quickAdmitTo(Clinic clinic, UUID bed) throws Exception {
        admitOk(clinic, body(newPatient(clinic), clinic.doctorMembershipId(), bed));
        assertThat(bedStatus(clinic, bed)).isEqualTo("occ");
    }

    // ---------------- tenant isolation ----------------

    @Test
    void anotherClinicCannotSeeSignOrDischargeThisClinicsAdmissionAndSeesNoneOfItsRows() throws Exception {
        Clinic a = hospital();
        Clinic b = hospital();
        String idA = quickAdmit(a).get("id").asText();
        String idB = quickAdmit(b).get("id").asText();
        signNormal(a, idA);
        Written before = written(a, idA);

        problem(call(HttpMethod.GET, dischargeUrl(idA), b.owner(), null), 404);
        problem(signAs(b, b.as("DOCTOR"), idA, "normal", "general"), 404);
        problem(postDischarge(b.owner(), idA, dischargeBody("normal", "general", DAY, "UPI")), 404);
        assertThat(written(a, idA)).isEqualTo(before);
        assertThat(rows(b, "discharge_signoff")).isZero();

        signAndDischarge(b, idB);
        dischargeNow(a, idA, "normal", "general", "UPI");
        assertThat(rows(a, "final_bill")).isOne();
        assertThat(rows(b, "final_bill")).isOne();
        assertThat(count(b, "select count(*) from ipd.final_bill where admission_id = ?::uuid", idA)).isZero();
        assertThat(count(b, "select count(*) from ipd.discharge where admission_id = ?::uuid", idA)).isZero();
        assertThat(count(b, "select count(*) from ipd.final_bill_line l join ipd.final_bill f on f.id = l.final_bill_id "
                + "where f.admission_id = ?::uuid", idA)).isZero();
        assertThat(count(b, "select count(*) from opd.follow_up where source_admission_id = ?::uuid", UUID.fromString(idA)))
                .isZero();
        assertThat(followUpOf(followUps(b, "UPCOMING"), idA)).isNull();
        assertThat(followUpOf(followUps(a, "UPCOMING"), idA)).isNotNull();
        assertThat(count(b, "select count(*) from platform.audit_event where action in ('IPD_DISCHARGED', 'FOLLOW_UP_CREATED') "
                + "and after_json::text like ?", "%" + idA + "%")).isZero();
        // no tenant bound: the new tables show no rows at all
        for (String table : new String[] {"discharge_signoff", "discharge", "final_bill", "final_bill_line"}) {
            long unbound = db().asApp(c -> DbAccess.count(c, "select count(*) from ipd." + table));
            assertThat(unbound).as(table).isZero();
        }
        // A's setup is not B's
        putSettings(a, "surgical", false, 20);
        assertThat(ok(HttpMethod.GET, DISCHARGE_SETTINGS, b.owner(), null, 200).get("followUpDays").asInt()).isEqualTo(7);
    }
}
