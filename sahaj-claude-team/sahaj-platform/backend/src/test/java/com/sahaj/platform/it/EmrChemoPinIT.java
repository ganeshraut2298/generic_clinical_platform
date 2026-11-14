package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The second check at the prescriber's device with the named checker's own colleague PIN (SAH-66-3 way b, SAH-70; ADR-023 D6,
 * ADR-010, ADR-014; docs/api/opd.yaml 0.5.1 {@code POST /emr/records/{recordId}/chemo-order/second-check}), through the real
 * endpoints on {@code sahaj_test} with a random PIN pepper ({@link WithPinPepper}): the real two-step flow (opd checks the order
 * with no lock, the platform checks the PIN in its own transaction, opd then records the confirmation under the order row lock),
 * so that a wrong try counts although nothing else is written; the SAH-38 caps (pair cap 429 {@code colleague-pin-limit} with no
 * retry hint and the neutral checker text); the 409 answers that must come BEFORE any PIN is checked (a changed order, failing
 * checks, a checker that is not the named one, no PIN set); and the rule that the PIN is never stored, logged, audited or
 * echoed. The fail-closed 503 {@code pin-check-failed} answer is the platform's and is proved with the colleague check itself in
 * {@link StaffPinColleagueHttpIT}. Test plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
@WithPinPepper
class EmrChemoPinIT extends EmrSpecialtySupport {

    private record Setup(Sheet named, Staff checker, String pin) {

        Rec rec() {
            return named.rec();
        }

        Clinic clinic() {
            return named.rec().clinic();
        }
    }

    /** A passing order with a nurse who has set a PIN, named as the checker. */
    private Setup named() throws Exception {
        Sheet sheet = passingSheet();
        Staff nurse = addNonDoctor(sheet.rec().clinic(), "NURSE");
        String pin = setPin(sheet.rec().clinic(), nurse);
        return new Setup(nameChecker(sheet, nurse), nurse, pin);
    }

    private String failedCount(Setup s) throws SQLException {
        return row(s.clinic(), "select failed_count::text from platform.staff_pin where membership_id = ?::uuid", s.checker().membershipId().toString()).get(0);
    }

    private MockHttpServletResponse secondCheck(Setup s, String pin) throws Exception {
        return call(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(), pinBody(s.named(), s.checker(), pin)).andReturn().getResponse();
    }

    // ================= the right PIN =================

    @Test
    void theCheckersRightPinConfirmsTheSecondCheckAtThePrescribersDeviceAndTheOrderIsThenSentAndNothingOfThePinIsKept() throws Exception {
        Setup s = named();
        assertThat(get(orderUrl(s.rec()) + "/checker-options", s.rec().token()).toString()).as("the options say who has a PIN").contains("\"hasPin\":true");
        String body;
        try (LogCapture logs = new LogCapture()) {
            MockHttpServletResponse response = secondCheck(s, s.pin());
            assertThat(response.getStatus()).isEqualTo(200);
            body = response.getContentAsString();
            assertThat(hasBarePin(logs.allText(), s.pin())).as("the PIN is not in any log line").isFalse();
        }
        JsonNode sheet = objectMapper.readTree(body);
        assertThat(sheet.get("secondCheck").get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(sheet.get("secondCheck").get("via").asText()).isEqualTo("PIN");
        assertThat(sheet.get("secondCheck").get("confirmedRole").asText()).isEqualTo("NURSE");
        assertThat(sheet.get("stages").get("secondCheck").asBoolean()).isTrue();
        assertThat(hasBarePin(body, s.pin())).as("never echoed").isFalse();
        assertThat(orderRow(s.clinic(), orderId(s.named()))).containsExactly("DRAFT", s.checker().membershipId().toString(), "PIN", "true", "true", "false", s.checker().userId().toString());
        assertThat(failedCount(s)).isEqualTo("0");
        // a retry of the same request: 200 and nothing written
        MockHttpServletResponse again = secondCheck(s, wrongPin(s.pin()));
        assertThat(again.getStatus()).as("already confirmed by this checker with this fingerprint: no PIN is checked again").isEqualTo(200);
        assertThat(auditRows(s.clinic(), "CHEMO_SECOND_CHECK_CONFIRMED")).hasSize(1);
        assertThat(auditRows(s.clinic(), "CHEMO_SECOND_CHECK_CONFIRMED").get(0).get(2)).contains("PIN").contains(s.checker().userId().toString());
        assertThat(allAuditText(s.clinic())).as("never audited").doesNotContain(s.pin());
        assertThat(row(s.clinic(), "select row_to_json(o)::text from opd.chemo_order o").get(0)).as("never stored in the order").doesNotContain(s.pin());
        // send what was confirmed
        Sheet confirmed = reread(s.named());
        JsonNode sent = post(orderUrl(s.rec()) + "/send", s.rec().token(), sendBody(confirmed), 200);
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(auditRows(s.clinic(), "CHEMO_ORDER_SENT").get(0).get(2)).contains("PIN");
    }

    // ================= a wrong PIN =================

    @Test
    void aWrongPinIs403WrongPinWithNoTriesLeftShownTheTryIsCountedAlthoughNothingElseIsWrittenAndTheOrderStaysUnconfirmed() throws Exception {
        Setup s = named();
        assertThat(failedCount(s)).isEqualTo("0");
        String wrong = wrongPin(s.pin());
        MockHttpServletResponse response;
        try (LogCapture logs = new LogCapture()) {
            response = secondCheck(s, wrong);
            assertThat(hasBarePin(logs.allText(), wrong)).isFalse();
            assertThat(hasBarePin(logs.allText(), s.pin())).isFalse();
        }
        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode problem = objectMapper.readTree(response.getContentAsString());
        assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "wrong-pin");
        assertThat(problem.get("detail").asText()).isEqualTo("That PIN is not right.");
        assertThat(problem.toString()).doesNotContain("left").doesNotContain("remaining").doesNotContain("attempt");
        assertThat(hasBarePin(problem.toString(), wrong)).isFalse();
        assertThat(failedCount(s)).as("the wrong try counts even though the request failed").isEqualTo("1");
        assertThat(orderRow(s.clinic(), orderId(s.named())).get(3)).isEqualTo("false");
        assertThat(auditCount(s.clinic(), "CHEMO_SECOND_CHECK_CONFIRMED")).isZero();
        // a malformed PIN is a wrong try too (ADR-010)
        MockHttpServletResponse malformed = secondCheck(s, "12ab");
        assertThat(malformed.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(malformed.getContentAsString()).get("type").asText()).isEqualTo(PROBLEM_BASE + "wrong-pin");
        assertThat(failedCount(s)).isEqualTo("2");
        // the right PIN still works and clears the count
        assertThat(secondCheck(s, s.pin()).getStatus()).isEqualTo(200);
        assertThat(failedCount(s)).isEqualTo("0");
    }

    @Test
    void afterFiveWrongTriesTheCallerAndCheckerPairIsCappedWith429ColleaguePinLimitNoRetryHintAndTheNeutralCheckerTextAndAnotherCheckerStillWorks() throws Exception {
        Setup s = named();
        Staff caller = s.rec().doctor();
        String wrong = wrongPin(s.pin());
        for (int i = 1; i <= 4; i++) {
            MockHttpServletResponse response = secondCheck(s, wrong);
            assertThat(response.getStatus()).as("wrong try " + i).isEqualTo(403);
            assertThat(objectMapper.readTree(response.getContentAsString()).get("type").asText()).isEqualTo(PROBLEM_BASE + "wrong-pin");
        }
        MockHttpServletResponse fifth = secondCheck(s, wrong);
        assertThat(fifth.getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(fifth.getContentAsString()).get("type").asText()).as("the fifth wrong try locks the PIN or is wrong").isIn(PROBLEM_BASE + "wrong-pin", PROBLEM_BASE + "pin-locked");
        freshPinAllowance(s.clinic(), caller);
        for (String pin : new String[] {s.pin(), wrong}) {
            MockHttpServletResponse capped = secondCheck(s, pin);
            assertThat(capped.getStatus()).as("the right PIN is refused too once the pair is capped").isEqualTo(429);
            assertThat(capped.getHeader("Retry-After")).as("no retry hint: the wait is days").isNull();
            JsonNode problem = objectMapper.readTree(capped.getContentAsString());
            assertThat(problem.get("type").asText()).isEqualTo(PROBLEM_BASE + "colleague-pin-limit");
            assertThat(problem.get("detail").asText()).isEqualTo("Too many wrong PIN tries for this person. Choose another checker.");
            assertThat(problem.has("retryAfterSeconds")).isFalse();
            assertThat(problem.toString()).doesNotContain("nurse").doesNotContain(s.checker().membershipId().toString());
        }
        assertThat(orderRow(s.clinic(), orderId(s.named())).get(3)).as("never confirmed").isEqualTo("false");
        // another checker still works for the same caller
        Staff pharmacist = addNonDoctor(s.clinic(), "PHARMACIST");
        String pharmacistPin = setPin(s.clinic(), pharmacist);
        Sheet renamed = nameChecker(reread(s.named()), pharmacist);
        freshPinAllowance(s.clinic(), caller);
        JsonNode confirmed = post(orderUrl(s.rec()) + "/second-check", s.rec().token(), pinBody(renamed, pharmacist, pharmacistPin), 200);
        assertThat(confirmed.get("secondCheck").get("confirmedRole").asText()).isEqualTo("PHARMACIST");
        assertThat(confirmed.get("secondCheck").get("via").asText()).isEqualTo("PIN");
    }

    // ================= the answers that come before any PIN is checked =================

    @Test
    void aCheckerWithNoPinSetIs409PinNotSetAndNoOneIsLockedOrCounted() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = addNonDoctor(clinic, "NURSE");
        Sheet named = nameChecker(sheet, nurse);
        assertThat(get(orderUrl(sheet.rec()) + "/checker-options", sheet.rec().token()).toString()).contains("\"hasPin\":false");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/second-check", sheet.rec().token(), pinBody(named, nurse, "4827"), 409, "pin-not-set");
        assertThat(orderRow(clinic, orderId(named)).get(3)).isEqualTo("false");
        // the other way is open
        confirmBySignIn(named, nurse);
        assertThat(orderRow(clinic, orderId(named)).get(2)).isEqualTo("SIGN_IN");
    }

    @Test
    void aStaleFingerprintFailingChecksOrAnotherCheckerAreRefusedWith409BeforeAnyPinIsCheckedSoNoWrongTryIsCounted() throws Exception {
        Setup s = named();
        String wrong = wrongPin(s.pin());
        // a fingerprint the screen did not hold
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(),
                "{\"orderRevision\":" + s.named().revision() + ",\"fingerprint\":\"" + "0".repeat(64) + "\",\"checkerMembershipId\":\"" + s.checker().membershipId() + "\",\"pin\":\"" + wrong + "\"}",
                409, "chemo-order-changed");
        // a stale revision with the right fingerprint
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(),
                "{\"orderRevision\":" + (s.named().revision() + 9) + ",\"fingerprint\":\"" + s.named().fingerprint() + "\",\"checkerMembershipId\":\"" + s.checker().membershipId() + "\",\"pin\":\"" + wrong + "\"}",
                409, "chemo-order-changed");
        // a person who is not the named checker
        Staff other = addNonDoctor(s.clinic(), "PHARMACIST");
        setPin(s.clinic(), other);
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(), pinBody(s.named(), other, wrong), 409, "chemo-order-changed");
        assertThat(failedCount(s)).as("no wrong try was counted for the checker").isEqualTo("0");
        // the checks fail after the checker was named
        Rec worse = saveScreen(s.rec(), "{}", spSection("onc_labs", oncLabs(1, "{\"hb\":7.0,\"anc\":3000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":60}")));
        JsonNode sheet = getOrder(worse);
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(), pinBody(new Sheet(worse, sheet), s.checker(), wrong), 409, "checks-failing");
        assertThat(failedCount(s)).isEqualTo("0");
        // validation: no checker id, a bad fingerprint
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(), "{\"orderRevision\":0,\"fingerprint\":\"nope\",\"checkerMembershipId\":\"" + uuid() + "\",\"pin\":\"1234\"}", 400, "validation");
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(), "{\"orderRevision\":0,\"fingerprint\":\"" + "0".repeat(64) + "\",\"pin\":\"1234\"}", 400, "validation");
    }

    @Test
    void theSecondCheckByPinNeedsANamedCheckerAndAnOrderAndASentOrderIsRefused() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        problemOf(HttpMethod.POST, orderUrl(rec) + "/second-check", rec.token(),
                "{\"orderRevision\":0,\"fingerprint\":\"" + "0".repeat(64) + "\",\"checkerMembershipId\":\"" + uuid() + "\",\"pin\":\"4827\"}", 404, "order-not-found");
        Sheet sheet = new Sheet(rec, saveOrder(rec, passingOrder(0)));
        problemOf(HttpMethod.POST, orderUrl(rec) + "/second-check", rec.token(), pinBody(sheet, addNonDoctor(rec.clinic(), "NURSE"), "4827"), 409, "chemo-order-changed");
        Setup s = named();
        assertThat(secondCheck(s, s.pin()).getStatus()).isEqualTo(200);
        Sheet confirmed = reread(s.named());
        post(orderUrl(s.rec()) + "/send", s.rec().token(), sendBody(confirmed), 200);
        MockHttpServletResponse sent = secondCheck(new Setup(reread(s.named()), s.checker(), s.pin()), s.pin());
        assertThat(sent.getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(sent.getContentAsString()).get("type").asText()).isEqualTo(PROBLEM_BASE + "chemo-order-sent");
    }

    // ================= another clinic =================

    @Test
    void anotherClinicsPrescriberCannotUseTheSecondCheckAndANurseOfAnotherClinicCannotBeNamed() throws Exception {
        Setup s = named();
        Clinic other = newClinic();
        Staff foreignDoctor = addDoctor(other, "General physician");
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", foreignDoctor.token(other), pinBody(s.named(), s.checker(), s.pin()), 404, "record-not-found");
        Staff foreignNurse = addNonDoctor(other, "NURSE");
        String foreignPin = setPin(other, foreignNurse);
        problemOf(HttpMethod.POST, orderUrl(s.rec()) + "/second-check", s.rec().token(), pinBody(s.named(), foreignNurse, foreignPin), 409, "chemo-order-changed");
        assertThat(orderRow(s.clinic(), orderId(s.named())).get(3)).isEqualTo("false");
        assertThat(failedCount(s)).isEqualTo("0");
    }
}
