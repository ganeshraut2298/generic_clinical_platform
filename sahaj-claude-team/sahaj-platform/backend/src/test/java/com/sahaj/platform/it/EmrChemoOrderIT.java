package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The chemotherapy order sheet and the independent second check by the checker's OWN SIGN-IN (SAH-65, SAH-66, SAH-70; ADR-023 D5,
 * D6; docs/api/opd.yaml 0.5.1), through the real endpoints on {@code sahaj_test}: the sheet before the first save, the server's
 * recomputation (BSA, calculated, rounded and final doses, the 5 percent flag, the lifetime doxorubicin dose, the nine or ten
 * checks), validation, naming the checker (never the prescriber), the checker's own list, summary and confirm, send, hold, the
 * 409 answers, "the order changed after the second check" clearing the confirmation, SENT and HELD being read-only, roles,
 * another clinic's data and the audit. The colleague-PIN way and its wrong-PIN limits are in {@link EmrChemoPinIT}; the database
 * rules are in {@link ChemoOrderTableIT}. The rule values are "value from the EMR mockup, accepted by the human in DEC-039, not
 * clinically reviewed"; the expected numbers below are worked by hand from the contract (BSA = sqrt(height x weight / 3600),
 * calculated = dose per m2 x BSA x level / 100 to one decimal, rounded to the vial step). Test plan: docs/test-plans/SAH-49-71.md.
 */
@OnTestDatabase
class EmrChemoOrderIT extends EmrSpecialtySupport {

    private static JsonNode check(JsonNode sheet, String key) {
        for (JsonNode c : sheet.get("checks")) {
            if (key.equals(c.get("key").asText())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key + " in " + sheet.get("checks"));
    }

    private static JsonNode drug(JsonNode sheet, String drugKey) {
        for (JsonNode d : sheet.get("drugs")) {
            if (drugKey.equals(d.get("drugKey").asText())) {
                return d;
            }
        }
        throw new AssertionError("no drug " + drugKey);
    }

    private static List<String> failing(JsonNode sheet) {
        List<String> keys = new ArrayList<>();
        for (JsonNode c : sheet.get("checks")) {
            if (!c.get("passed").asBoolean()) {
                keys.add(c.get("key").asText());
            }
        }
        return keys;
    }

    private Staff nurse(Clinic clinic) throws Exception {
        return addNonDoctor(clinic, "NURSE");
    }

    // ================= the sheet before the first save =================

    @Test
    void theSheetBeforeTheFirstSaveHasStatusNoneNoProtocolAndTheMissingDecisionTextAndCreatesNoRow() throws Exception {
        Rec rec = newOnco();
        JsonNode sheet = getOrder(rec);
        assertThat(sheet.get("status").asText()).isEqualTo("NONE");
        assertThat(sheet.get("id").isNull()).isTrue();
        assertThat(sheet.get("protocolKey").isNull()).isTrue();
        assertThat(sheet.get("decisionRecorded").asBoolean()).isFalse();
        assertThat(check(sheet, "DECISION_RECORDED").get("passed").asBoolean()).isFalse();
        assertThat(check(sheet, "DECISION_RECORDED").get("detail").asText()).isEqualTo("No decision yet. Finish the assessment and confirm today's decision.");
        assertThat(sheet.get("fingerprint").asText()).matches("^[0-9a-f]{64}$");
        assertThat(sheet.get("stages").get("chair").asText()).as("the chair is Later (OQ-45-28)").isEqualTo("LATER");
        assertThat(sheet.get("stages").get("pharmacy").asText()).isEqualTo("NOT_SENT");
        assertThat(count(rec.clinic(), "select count(*) from opd.chemo_order")).as("a read creates nothing").isZero();
        assertThat(auditCount(rec.clinic(), "CHEMO_ORDER_SAVED")).isZero();
    }

    @Test
    void theOrderSheetIsOnlyForAnOncologyVisitRecord() throws Exception {
        Rec gm = newRec();
        problemOf(HttpMethod.GET, orderUrl(gm), gm.token(), null, 400, "not-applicable");
        problemOf(HttpMethod.PUT, orderUrl(gm), gm.token(), passingOrder(0), 400, "not-applicable");
        problemOf(HttpMethod.GET, orderUrl(gm) + "/checker-options", gm.token(), null, 400, "not-applicable");
        Rec dental = specialtyRec("dental");
        problemOf(HttpMethod.GET, orderUrl(dental), dental.token(), null, 400, "not-applicable");
    }

    // ================= the server's recomputation =================

    @Test
    void aSavedOrderIsWorkedOutByTheServerBsaCalculatedRoundedAndFinalDosesAndEveryCheckPassesForAFullDoseNonVesicantProtocol() throws Exception {
        Sheet sheet = passingSheet();
        JsonNode order = sheet.order();
        assertThat(order.get("revision").asLong()).isZero();
        assertThat(order.get("totalCount").asInt()).as("nine checks, no vesicant in paclitaxel").isEqualTo(9);
        assertThat(failing(order)).isEmpty();
        assertThat(order.get("bsa").get("raw").asDouble()).as("sqrt(160 x 60 / 3600) = 1.63").isEqualTo(1.63);
        assertThat(order.get("bsa").get("used").asDouble()).isEqualTo(1.63);
        assertThat(order.get("bsa").get("capped").asBoolean()).isFalse();
        assertThat(order.get("bsa").get("source").asText()).isEqualTo("BSA from today's weight");
        JsonNode paclitaxel = drug(order, "paclitaxel");
        assertThat(paclitaxel.get("dosePerM2").asDouble()).isEqualTo(175.0);
        assertThat(paclitaxel.get("calculatedMg").asDouble()).as("175 x 1.63").isEqualTo(285.3);
        assertThat(paclitaxel.get("roundedMg").asDouble()).as("to the 10 mg vial step").isEqualTo(290.0);
        assertThat(paclitaxel.get("finalMg").asDouble()).isEqualTo(290.0);
        assertThat(paclitaxel.get("finalEdited").asBoolean()).isFalse();
        assertThat(paclitaxel.get("moreThan5Pct").asBoolean()).isFalse();
        assertThat(paclitaxel.get("lifetime").isNull()).as("only an anthracycline has a lifetime limit").isTrue();
        assertThat(order.get("weightChangePct").asDouble()).as("no earlier SENT order: cycle 1 weight is today's").isZero();
        assertThat(order.get("recalculated").asBoolean()).isFalse();
        assertThat(order.get("secondCheck").get("state").asText()).isEqualTo("NOT_NAMED");
        assertThat(order.get("stages").get("decided").asBoolean()).isTrue();
        assertThat(order.get("stages").get("dosesChecked").asBoolean()).isTrue();
        assertThat(order.get("stages").get("secondCheck").asBoolean()).isFalse();
        assertThat(order.get("send").get("ready").asBoolean()).as("not ready before the second check").isFalse();
        assertThat(order.get("send").get("note").asText()).isEqualTo("Waiting for the second check");
        assertThat(order.get("premeds").size()).isEqualTo(3);
        assertThat(order.get("emeticRisk").asText()).isEqualTo("LOW");
        assertThat(getOrder(sheet.rec()).get("fingerprint").asText()).as("a read answers the same fingerprint").isEqualTo(sheet.fingerprint());
        assertThat(count(sheet.rec().clinic(), "select count(*) from opd.chemo_order")).isOne();
        assertThat(auditCount(sheet.rec().clinic(), "CHEMO_ORDER_SAVED")).isOne();
    }

    @Test
    void aReducedLevelRecomputesTheDosesAndNeedsAReasonOrTheCheckFailsButTheOrderIsStoredAndAnEditMoreThanFivePercentFromCalculatedIsFlagged() throws Exception {
        Sheet sheet = passingSheet();
        String more = "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\"";
        JsonNode ninety = saveOrder(sheet.rec(), orderBody(sheet.revision(), "PACLI_3W", 90, more));
        assertThat(drug(ninety, "paclitaxel").get("calculatedMg").asDouble()).as("175 x 1.63 x 90 %").isEqualTo(256.7);
        assertThat(drug(ninety, "paclitaxel").get("roundedMg").asDouble()).isEqualTo(260.0);
        assertThat(failing(ninety)).as("level below 100 without a reason: stored, but the check fails").containsExactly("REDUCTION_REASON");
        assertThat(check(ninety, "REDUCTION_REASON").get("detail").asText()).isEqualTo("Pick a reason above");
        assertThat(ninety.get("revision").asLong()).isEqualTo(1);
        JsonNode reasoned = saveOrder(sheet.rec(), orderBody(ninety.get("revision").asLong(), "PACLI_3W", 90, more + ",\"reductionReasonKey\":\"NEUROPATHY\""));
        assertThat(failing(reasoned)).isEmpty();
        assertThat(reasoned.get("reductionReason").asText()).isEqualTo("Neuropathy");
        JsonNode typed = saveOrder(sheet.rec(), orderBody(reasoned.get("revision").asLong(), "PACLI_3W", 80, more + ",\"reductionReasonText\":\"Frail, walks with a stick\""));
        assertThat(failing(typed)).isEmpty();
        assertThat(typed.get("reductionReason").asText()).isEqualTo("Frail, walks with a stick");
        // an edited final dose: 300 is 14.7 mg (5.2 %) from 285.3, 295 is 9.7 mg (3.4 %) from it
        JsonNode full = saveOrder(sheet.rec(), orderBody(typed.get("revision").asLong(), "PACLI_3W", 100, more + ",\"finalDoses\":[{\"drugKey\":\"paclitaxel\",\"finalMg\":300}]"));
        assertThat(drug(full, "paclitaxel").get("finalMg").asDouble()).isEqualTo(300.0);
        assertThat(drug(full, "paclitaxel").get("finalEdited").asBoolean()).isTrue();
        assertThat(drug(full, "paclitaxel").get("moreThan5Pct").asBoolean()).isTrue();
        JsonNode near = saveOrder(sheet.rec(), orderBody(full.get("revision").asLong(), "PACLI_3W", 100, more + ",\"finalDoses\":[{\"drugKey\":\"paclitaxel\",\"finalMg\":295}]"));
        assertThat(drug(near, "paclitaxel").get("finalEdited").asBoolean()).isTrue();
        assertThat(drug(near, "paclitaxel").get("moreThan5Pct").asBoolean()).isFalse();
        assertThat(near.get("fingerprint").asText()).as("a different final dose is a different fingerprint").isNotEqualTo(full.get("fingerprint").asText());
        assertThat(count(sheet.rec().clinic(), "select count(*) from opd.chemo_order")).as("still one order").isOne();
    }

    @Test
    void anAnthracyclineProtocolAddsTheVesicantLineCheckAndTheLifetimeDoxorubicinDoseIsRedAbove400AndFailsAbove450() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        String more = "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\"";
        JsonNode ac = saveOrder(rec, orderBody(0, "AC", 100, more));
        assertThat(ac.get("totalCount").asInt()).as("ten checks with a vesicant drug (H4)").isEqualTo(10);
        assertThat(check(ac, "LINE_VESICANT").get("passed").asBoolean()).as("port, flushes freely, blood return, no redness").isTrue();
        assertThat(drug(ac, "doxorubicin").get("calculatedMg").asDouble()).as("60 x 1.63").isEqualTo(97.8);
        assertThat(drug(ac, "doxorubicin").get("roundedMg").asDouble()).as("5 mg vial step").isEqualTo(100.0);
        assertThat(drug(ac, "cyclophosphamide").get("roundedMg").asDouble()).as("600 x 1.63 = 978.0, 20 mg step").isEqualTo(980.0);
        assertThat(drug(ac, "doxorubicin").get("vesicant").asBoolean()).isTrue();
        JsonNode life = drug(ac, "doxorubicin").get("lifetime");
        assertThat(life.get("earlierMgM2").asDouble()).isZero();
        assertThat(life.get("thisCycleMgM2").asDouble()).isEqualTo(60.0);
        assertThat(life.get("limitMgM2").asInt()).isEqualTo(450);
        assertThat(life.get("red").asBoolean()).isFalse();
        assertThat(drug(ac, "cyclophosphamide").get("lifetime").isNull()).as("No lifetime limit").isTrue();
        // 380 earlier + 60 = 440: red, still within the limit of 450
        JsonNode red = saveOrder(rec, orderBody(ac.get("revision").asLong(), "AC", 100, more + ",\"priorOutsideDoxorubicinMgM2\":380"));
        assertThat(drug(red, "doxorubicin").get("lifetime").get("totalMgM2").asDouble()).isEqualTo(440.0);
        assertThat(drug(red, "doxorubicin").get("lifetime").get("red").asBoolean()).isTrue();
        assertThat(check(red, "CUMULATIVE_DOSE").get("passed").asBoolean()).isTrue();
        assertThat(red.get("priorOutsideDoxorubicinMgM2").asDouble()).isEqualTo(380.0);
        // 391 + 60 = 451: above the limit
        JsonNode over = saveOrder(rec, orderBody(red.get("revision").asLong(), "AC", 100, more + ",\"priorOutsideDoxorubicinMgM2\":391"));
        assertThat(check(over, "CUMULATIVE_DOSE").get("passed").asBoolean()).isFalse();
        assertThat(check(over, "CUMULATIVE_DOSE").get("detail").asText()).isEqualTo("Doxorubicin above 450 mg/m²");
        // a vesicant line that is not ready fails the tenth check
        Rec noBloodReturn = saveScreen(rec, "{\"oncDecision\":{\"choice\":\"GIVE_AS_PLANNED\",\"reasonKey\":null}}",
                spSection("onc_bsa", oncExam(160, 60.0, "PORT", "FLUSHES_FREELY", "NO_REDNESS_SWELLING")));
        JsonNode line = getOrder(noBloodReturn);
        assertThat(check(line, "LINE_VESICANT").get("passed").asBoolean()).isFalse();
        assertThat(check(line, "LINE_VESICANT").get("detail").asText()).isEqualTo("Port: no blood return yet");
    }

    @Test
    void theOrderIsRecomputedWhenTheRecordIsSavedSoANewWeightChangesTheDosesAndALabOutOfRangeFailsTheFitCheck() throws Exception {
        Sheet sheet = passingSheet();
        Rec heavier = saveScreen(sheet.rec(), "{}", spSection("onc_bsa", oncExam(160, 70.0, "PORT", "FLUSHES_FREELY", "BLOOD_RETURN", "NO_REDNESS_SWELLING")));
        JsonNode after = getOrder(heavier);
        assertThat(after.get("bsa").get("used").asDouble()).as("sqrt(160 x 70 / 3600) = 1.76").isEqualTo(1.76);
        assertThat(drug(after, "paclitaxel").get("calculatedMg").asDouble()).as("175 x 1.76").isEqualTo(308.0);
        assertThat(after.get("fingerprint").asText()).isNotEqualTo(sheet.fingerprint());
        Rec lowCounts = saveScreen(heavier, "{}", spSection("onc_labs", oncLabs(1, "{\"hb\":12.0,\"anc\":1000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":60}")));
        JsonNode fit = getOrder(lowCounts);
        assertThat(check(fit, "FIT_TO_TREAT").get("passed").asBoolean()).as("ANC 1,000 is below 1,500").isFalse();
        assertThat(check(fit, "FIT_TO_TREAT").get("detail").asText()).isEqualTo("A count or organ test is out of range");
        Rec old = saveScreen(lowCounts, "{}", spSection("onc_labs", oncLabs(80, NORMAL_LABS)));
        JsonNode stale = getOrder(old);
        assertThat(check(stale, "LABS_72H").get("passed").asBoolean()).as("drawn 80 hours ago").isFalse();
        assertThat(check(stale, "LABS_72H").get("detail").asText()).isEqualTo("Labs older than 72 hours");
        Rec fresh = saveScreen(old, "{}", spSection("onc_labs", oncLabs(71, NORMAL_LABS)));
        assertThat(check(getOrder(fresh), "LABS_72H").get("passed").asBoolean()).as("71 hours is inside the 72-hour rule").isTrue();
    }

    @Test
    void theChecksForAllergyConsentSchemeAndDecisionFailUntilTheDoctorSaysSoAndSelfPayPassesTheSchemeCheck() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode bare = saveOrder(rec, orderBody(0, "PACLI_3W", 100, ""));
        assertThat(failing(bare)).containsExactlyInAnyOrder("ALLERGIES_REVIEWED", "CONSENT_REGIMEN", "SCHEME_APPROVAL");
        assertThat(check(bare, "ALLERGIES_REVIEWED").get("detail").asText()).isEqualTo("Confirm the drug allergies");
        assertThat(check(bare, "CONSENT_REGIMEN").get("detail").asText()).isEqualTo("New consent needed for this regimen");
        assertThat(check(bare, "SCHEME_APPROVAL").get("detail").asText()).isEqualTo("Choose the scheme state");
        long rev = bare.get("revision").asLong();
        JsonNode notInPackage = saveOrder(rec, orderBody(rev, "PACLI_3W", 100, "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"NOT_IN_PACKAGE\""));
        assertThat(failing(notInPackage)).containsExactly("SCHEME_APPROVAL");
        for (String scheme : new String[] {"APPROVED", "PREAUTH_RAISED", "SELF_PAY"}) {
            JsonNode passes = saveOrder(rec, orderBody(rev + 1, "PACLI_3W", 100, "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"" + scheme + "\""));
            assertThat(failing(passes)).as(scheme).isEmpty();
            rev = passes.get("revision").asLong() - 1;
        }
    }

    // ================= validation =================

    @Test
    void anInvalidOrderIsRefusedWith400AndAProblemBodyAndNothingIsStored() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        String[] bad = {
                orderBody(0, "NO_SUCH", 100, ""),
                orderBody(0, "AC", 85, ""),
                orderBody(0, "AC", 100, "\"reductionReasonKey\":\"BECAUSE\""),
                orderBody(0, "AC", 100, "\"reductionReasonText\":\"<b>x</b>\""),
                orderBody(0, "AC", 100, "\"reductionReasonText\":\"" + "x".repeat(201) + "\""),
                orderBody(0, "AC", 100, "\"scheme\":\"MAYBE\""),
                orderBody(0, "AC", 100, "\"priorOutsideDoxorubicinMgM2\":2001"),
                orderBody(0, "AC", 100, "\"priorOutsideDoxorubicinMgM2\":-1"),
                orderBody(0, "AC", 100, "\"finalDoses\":[{\"drugKey\":\"paclitaxel\",\"finalMg\":100}]"),
                orderBody(0, "AC", 100, "\"finalDoses\":[{\"drugKey\":\"doxorubicin\",\"finalMg\":10001}]"),
                orderBody(0, "AC", 100, "\"premedsNotGiven\":[\"no_such_premed\"]"),
                orderBody(-1, "AC", 100, ""),
                "{\"protocolKey\":\"AC\",\"levelPct\":100}"};
        for (String body : bad) {
            JsonNode problem = problemOf(HttpMethod.PUT, orderUrl(rec), rec.token(), body, 400, "validation");
            assertThat(problem.get("title").asText()).isNotBlank();
        }
        assertThat(call(HttpMethod.PUT, orderUrl(rec), rec.token(), orderBody(0, "AC", 100, "\"unknownProperty\":1")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(count(rec.clinic(), "select count(*) from opd.chemo_order")).isZero();
        assertThat(auditCount(rec.clinic(), "CHEMO_ORDER_SAVED")).isZero();
    }

    @Test
    void aStaleOrderRevisionIs409ChemoOrderChangedAndASecondFirstSaveToo() throws Exception {
        Sheet sheet = passingSheet();
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), sheet.rec().token(), passingOrder(7), 409, "chemo-order-changed");
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), sheet.rec().token(), passingOrder(0), 409, "chemo-order-changed");
        saveOrder(sheet.rec(), passingOrder(sheet.revision()));
        assertThat(count(sheet.rec().clinic(), "select count(*) from opd.chemo_order")).as("one order per record").isOne();
    }

    @Test
    void aSignedRecordRefusesASaveWith409EmrRecordSigned() throws Exception {
        Sheet sheet = passingSheet();
        signRecord(sheet.rec().clinic(), sheet.rec().id());
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), sheet.rec().token(), passingOrder(sheet.revision()), 409, "emr-record-signed");
    }

    // ================= naming the checker =================

    @Test
    void theCheckerCanBeNamedOnlyWhileEveryCheckPassesAndNeverThePrescriberOrAPersonOfAnotherClinicOrARoleThatCannotCheck() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        Clinic clinic = rec.clinic();
        Staff nurse = nurse(clinic);
        JsonNode failingSheet = saveOrder(rec, orderBody(0, "PACLI_3W", 100, ""));
        JsonNode refused = problemOf(HttpMethod.PUT, orderUrl(rec) + "/checker", rec.token(),
                "{\"revision\":" + failingSheet.get("revision").asLong() + ",\"checkerMembershipId\":\"" + nurse.membershipId() + "\"}", 409, "checks-failing");
        assertThat(refused.get("detail").asText()).isEqualTo("Clear the red checks before the second check.");
        JsonNode passing = saveOrder(rec, passingOrder(failingSheet.get("revision").asLong()));
        long rev = passing.get("revision").asLong();
        String body = "{\"revision\":" + rev + ",\"checkerMembershipId\":\"%s\"}";
        JsonNode self = problemOf(HttpMethod.PUT, orderUrl(rec) + "/checker", rec.token(), String.format(body, rec.doctor().membershipId()), 400, "validation");
        assertThat(self.toString()).contains("The second check must be done by someone other than the prescriber.");
        Clinic other = newClinic();
        Staff foreign = nurse(other);
        JsonNode foreignProblem = problemOf(HttpMethod.PUT, orderUrl(rec) + "/checker", rec.token(), String.format(body, foreign.membershipId()), 400, "validation");
        assertThat(foreignProblem.toString()).contains("Choose a checker from the list.");
        Staff frontDesk = addNonDoctor(clinic, "FRONT_DESK");
        assertThat(problemOf(HttpMethod.PUT, orderUrl(rec) + "/checker", rec.token(), String.format(body, frontDesk.membershipId()), 400, "validation").toString())
                .contains("Choose a checker from the list.");
        problemOf(HttpMethod.PUT, orderUrl(rec) + "/checker", rec.token(), String.format(body, uuid()), 400, "validation");
        assertThat(row(clinic, "select checker_membership_id::text from opd.chemo_order").get(0)).as("nothing was named").isNull();
        JsonNode named = put(orderUrl(rec) + "/checker", rec.token(), String.format(body, nurse.membershipId()), 200);
        assertThat(named.get("checker").get("membershipId").asText()).isEqualTo(nurse.membershipId().toString());
        assertThat(named.get("checker").get("role").asText()).isEqualTo("NURSE");
        assertThat(named.get("secondCheck").get("state").asText()).isEqualTo("WAITING");
        assertThat(named.get("stages").get("secondCheck").asBoolean()).as("naming does not complete the check").isFalse();
        assertThat(named.get("fingerprint").asText()).as("the named checker is part of what is fingerprinted").isNotEqualTo(passing.get("fingerprint").asText());
        assertThat(auditRows(clinic, "CHEMO_CHECKER_NAMED")).hasSize(1);
        assertThat(auditRows(clinic, "CHEMO_CHECKER_NAMED").get(0).get(2)).contains(nurse.membershipId().toString()).contains("NURSE");
        JsonNode removed = put(orderUrl(rec) + "/checker", rec.token(), "{\"revision\":" + named.get("revision").asLong() + ",\"checkerMembershipId\":null}", 200);
        assertThat(removed.get("checker").isNull()).isTrue();
        assertThat(removed.get("secondCheck").get("state").asText()).isEqualTo("NOT_NAMED");
    }

    @Test
    void theCheckerOptionsAreActiveNursesPharmacistsAndDoctorsWithDoctorDetailsButNeverThePrescriberFrontDeskBillingCounsellorOrAnInactivePerson() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Staff pharmacist = addNonDoctor(clinic, "PHARMACIST");
        Staff second = addDoctor(clinic, "Medical oncology");
        Staff frontDesk = addNonDoctor(clinic, "FRONT_DESK");
        Staff billing = addNonDoctor(clinic, "BILLING");
        Staff gone = nurse(clinic);
        setMembershipStatusOf(clinic, gone, "INACTIVE");
        JsonNode options = get(orderUrl(sheet.rec()) + "/checker-options", sheet.rec().token());
        List<String> ids = texts(options, "membershipId");
        assertThat(ids).contains(nurse.membershipId().toString(), pharmacist.membershipId().toString(), second.membershipId().toString());
        assertThat(ids).doesNotContain(sheet.rec().doctor().membershipId().toString(), frontDesk.membershipId().toString(), billing.membershipId().toString(),
                gone.membershipId().toString());
        for (JsonNode option : options) {
            assertThat(option.get("role").asText()).isIn("NURSE", "PHARMACIST", "DOCTOR", "VISITING_DOCTOR", "OWNER");
            assertThat(option.get("hasPin").asBoolean()).as("nobody has set a PIN yet").isFalse();
            assertThat(option.get("name").asText()).isNotBlank();
        }
        assertThat(ids).as("the owner has no doctor details: not an option").doesNotContain(owner(clinic).membershipId().toString());
        ownerWithDoctorDetails(clinic, "General physician", "MBBS", "REG-" + shortId());
        assertThat(texts(get(orderUrl(sheet.rec()) + "/checker-options", sheet.rec().token()), "membershipId")).as("the owner with doctor details is").contains(owner(clinic).membershipId().toString());
    }

    @Test
    void theCheckerOptionsNeedAnOrderAndAnotherClinicsRecordIsNotFound() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        problemOf(HttpMethod.GET, orderUrl(rec) + "/checker-options", rec.token(), null, 404, "order-not-found");
        Rec foreign = newOnco();
        problemOf(HttpMethod.GET, orderUrl(rec) + "/checker-options", foreign.token(), null, 404, "record-not-found");
    }

    // ================= the second check by the checker's own sign-in, then send =================

    @Test
    void theNamedCheckerListsReadsAndConfirmsOnTheirOwnSignInAndThePrescriberThenSendsAndARetryMakesOneOrder() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Sheet named = nameChecker(sheet, nurse);
        String orderId = orderId(named);
        // the list shows the order to the named checker only, with the patient's name for the screen
        JsonNode list = get(CHEMO_CHECKS, nurse.token(clinic));
        assertThat(list.get("total").asLong()).isOne();
        assertThat(list.get("pageSize").asInt()).isEqualTo(20);
        assertThat(list.get("items").get(0).get("orderId").asText()).isEqualTo(orderId);
        assertThat(list.get("items").get(0).get("patientName").asText()).isNotBlank();
        assertThat(list.get("items").get(0).get("protocolLabel").asText()).isEqualTo("Paclitaxel 3-weekly");
        Staff pharmacist = addNonDoctor(clinic, "PHARMACIST");
        assertThat(get(CHEMO_CHECKS, pharmacist.token(clinic)).get("total").asLong()).as("another checker role sees nothing").isZero();
        assertThat(get(CHEMO_CHECKS, sheet.rec().token()).get("total").asLong()).as("the prescriber sees nothing").isZero();
        // the summary is read-only and carries the fingerprint
        JsonNode summary = summary(clinic, nurse, orderId);
        assertThat(summary.get("canConfirm").asBoolean()).isTrue();
        assertThat(summary.get("fingerprint").asText()).isEqualTo(named.fingerprint());
        assertThat(summary.get("levelPct").asInt()).isEqualTo(100);
        assertThat(summary.get("decision").asText()).isEqualTo("GIVE_AS_PLANNED");
        assertThat(summary.get("drugs").get(0).get("finalMg").asDouble()).isEqualTo(290.0);
        assertThat(summary.get("secondCheck").get("state").asText()).isEqualTo("WAITING");
        // anyone else of the clinic is refused with the reason; the prescriber too
        problemOf(HttpMethod.GET, CHEMO_CHECKS + "/" + orderId, pharmacist.token(clinic), null, 403, "not-named-checker");
        problemOf(HttpMethod.GET, CHEMO_CHECKS + "/" + orderId, sheet.rec().token(), null, 403, "checker-is-prescriber");
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId + "/confirm", pharmacist.token(clinic), confirmBody(named.fingerprint()), 403, "not-named-checker");
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId + "/confirm", sheet.rec().token(), confirmBody(named.fingerprint()), 403, "checker-is-prescriber");
        // send before the second check
        JsonNode early = problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sheet.rec().token(), sendBody(named), 409, "second-check-not-confirmed");
        assertThat(early.get("detail").asText()).isEqualTo("The second check is not confirmed yet.");
        // a fingerprint the checker did not read
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId + "/confirm", nurse.token(clinic), confirmBody("0".repeat(64)), 409, "chemo-order-changed");
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId + "/confirm", nurse.token(clinic), "{\"fingerprint\":\"nope\"}", 400, "validation");
        // the checker confirms
        JsonNode confirmed = post(CHEMO_CHECKS + "/" + orderId + "/confirm", nurse.token(clinic), confirmBody(summary.get("fingerprint").asText()), 200);
        assertThat(confirmed.get("secondCheck").get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("secondCheck").get("via").asText()).isEqualTo("SIGN_IN");
        assertThat(confirmed.get("canConfirm").asBoolean()).isFalse();
        post(CHEMO_CHECKS + "/" + orderId + "/confirm", nurse.token(clinic), confirmBody(summary.get("fingerprint").asText()), 200);
        assertThat(auditRows(clinic, "CHEMO_SECOND_CHECK_CONFIRMED")).as("repeating writes nothing").hasSize(1);
        assertThat(get(CHEMO_CHECKS, nurse.token(clinic)).get("total").asLong()).as("no longer waiting").isZero();
        // what the prescriber sees
        Sheet afterConfirm = reread(named);
        assertThat(afterConfirm.order().get("secondCheck").get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(afterConfirm.order().get("secondCheck").get("via").asText()).isEqualTo("SIGN_IN");
        assertThat(afterConfirm.order().get("secondCheck").get("confirmedRole").asText()).isEqualTo("NURSE");
        assertThat(afterConfirm.order().get("secondCheck").get("confirmedByName").asText()).isNotBlank();
        assertThat(afterConfirm.order().get("stages").get("secondCheck").asBoolean()).isTrue();
        assertThat(afterConfirm.order().get("send").get("ready").asBoolean()).isTrue();
        assertThat(afterConfirm.order().get("send").get("note").asText()).isEqualTo("All checks passed. Ready to send.");
        List<String> row = orderRow(clinic, orderId);
        assertThat(row).containsExactly("DRAFT", nurse.membershipId().toString(), "SIGN_IN", "true", "true", "false", nurse.userId().toString());
        // send, then a retry with the same fingerprint answers 200 with the same order
        JsonNode sent = post(orderUrl(sheet.rec()) + "/send", sheet.rec().token(), sendBody(afterConfirm), 200);
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("stages").get("pharmacy").asText()).isEqualTo("SENT");
        assertThat(sent.get("sentAt").isNull()).isFalse();
        assertThat(sent.get("sentByName").asText()).isNotBlank();
        JsonNode retry = post(orderUrl(sheet.rec()) + "/send", sheet.rec().token(), sendBody(new Sheet(sheet.rec(), sent)), 200);
        assertThat(retry.get("status").asText()).isEqualTo("SENT");
        assertThat(retry.get("id").asText()).isEqualTo(orderId);
        assertThat(count(clinic, "select count(*) from opd.chemo_order")).as("a retry makes one order").isOne();
        assertThat(auditRows(clinic, "CHEMO_ORDER_SENT")).as("a retry writes no second audit").hasSize(1);
        assertThat(row(clinic, "select status, (confirmed_fingerprint = fingerprint)::text, (sent_by is not null)::text from opd.chemo_order")).containsExactly("SENT", "true", "true");
        // SENT is read-only
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), sheet.rec().token(), passingOrder(retry.get("revision").asLong()), 409, "chemo-order-sent");
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()) + "/checker", sheet.rec().token(), "{\"revision\":" + retry.get("revision").asLong() + ",\"checkerMembershipId\":null}", 409, "chemo-order-sent");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/hold", sheet.rec().token(), "{\"revision\":" + retry.get("revision").asLong() + "}", 409, "chemo-order-sent");
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId + "/confirm", nurse.token(clinic), confirmBody(summary.get("fingerprint").asText()), 409, "chemo-order-sent");
        assertThat(get(orderUrl(sheet.rec()), sheet.rec().token()).get("status").asText()).isEqualTo("SENT");
    }

    @Test
    void sendIsRefusedWhileAnyCheckFailsEvenWithAConfirmationAndForAStaleFingerprint() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Sheet named = nameChecker(sheet, nurse);
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sheet.rec().token(), "{\"revision\":" + named.revision() + ",\"fingerprint\":\"" + "0".repeat(64) + "\"}", 409, "chemo-order-changed");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sheet.rec().token(), "{\"revision\":" + (named.revision() + 5) + ",\"fingerprint\":\"" + named.fingerprint() + "\"}", 409, "chemo-order-changed");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sheet.rec().token(), "{\"revision\":" + named.revision() + ",\"fingerprint\":\"nope\"}", 400, "validation");
        // the labs go out of range after the checker was named: the checks fail and the send says so
        Rec worse = saveScreen(sheet.rec(), "{}", spSection("onc_labs", oncLabs(1, "{\"hb\":7.0,\"anc\":3000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":60}")));
        JsonNode failingSheet = getOrder(worse);
        assertThat(failing(failingSheet)).contains("FIT_TO_TREAT");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sheet.rec().token(), "{\"revision\":" + failingSheet.get("revision").asLong() + ",\"fingerprint\":\"" + failingSheet.get("fingerprint").asText() + "\"}", 409, "checks-failing");
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId(named) + "/confirm", nurse.token(clinic), confirmBody(failingSheet.get("fingerprint").asText()), 409, "checks-failing");
        assertThat(row(clinic, "select status, confirmed_at is not null from opd.chemo_order").get(0)).isEqualTo("DRAFT");
    }

    // ================= "Order changed after the second check" =================

    @Test
    void aChangeToTheOrderAfterTheSecondCheckClearsTheConfirmationAndSendIsRefusedUntilTheCheckerConfirmsAgain() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Sheet confirmed = confirmBySignIn(nameChecker(sheet, nurse), nurse);
        assertThat(confirmed.order().get("secondCheck").get("state").asText()).isEqualTo("CONFIRMED");
        // the doctor edits a dose: the fingerprint changes
        JsonNode changed = saveOrder(sheet.rec(), orderBody(confirmed.revision(), "PACLI_3W", 100,
                "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\",\"finalDoses\":[{\"drugKey\":\"paclitaxel\",\"finalMg\":300}]"));
        assertThat(changed.get("secondCheck").get("state").asText()).isEqualTo("CLEARED");
        assertThat(changed.get("stages").get("secondCheck").asBoolean()).isFalse();
        assertThat(changed.get("send").get("ready").asBoolean()).isFalse();
        assertThat(changed.get("checker").get("membershipId").asText()).as("the named checker stays").isEqualTo(nurse.membershipId().toString());
        assertThat(auditCount(clinic, "CHEMO_SECOND_CHECK_CLEARED")).isOne();
        assertThat(allAuditText(clinic)).doesNotContain("finalMg").doesNotContain("paclitaxel");
        List<String> row = orderRow(clinic, orderId(sheet));
        assertThat(row.get(3)).as("no confirmation any more").isEqualTo("false");
        assertThat(row.get(5)).as("the cleared stamp is set").isEqualTo("true");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sheet.rec().token(), sendBody(new Sheet(sheet.rec(), changed)), 409, "second-check-not-confirmed");
        // the checker sees it again and confirms the new fingerprint
        assertThat(get(CHEMO_CHECKS, nurse.token(clinic)).get("total").asLong()).isOne();
        JsonNode again = summary(clinic, nurse, orderId(sheet));
        assertThat(again.get("drugs").get(0).get("finalMg").asDouble()).isEqualTo(300.0);
        assertThat(again.get("drugs").get(0).get("moreThan5Pct").asBoolean()).isTrue();
        post(CHEMO_CHECKS + "/" + orderId(sheet) + "/confirm", nurse.token(clinic), confirmBody(again.get("fingerprint").asText()), 200);
        Sheet reconfirmed = reread(new Sheet(sheet.rec(), changed));
        assertThat(reconfirmed.order().get("secondCheck").get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(orderRow(clinic, orderId(sheet)).get(5)).as("a new confirmation removes the cleared stamp").isEqualTo("false");
        assertThat(post(orderUrl(sheet.rec()) + "/send", sheet.rec().token(), sendBody(reconfirmed), 200).get("status").asText()).isEqualTo("SENT");
    }

    @Test
    void aChangeInTheRecordsOwnValuesAfterTheSecondCheckAlsoClearsItAndNamingAnotherCheckerClearsItToo() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Staff pharmacist = addNonDoctor(clinic, "PHARMACIST");
        Sheet confirmed = confirmBySignIn(nameChecker(sheet, nurse), nurse);
        // a weight typed in the record (not on the order sheet)
        Rec heavier = saveScreen(sheet.rec(), "{}", spSection("onc_bsa", oncExam(160, 66.0, "PORT", "FLUSHES_FREELY", "BLOOD_RETURN", "NO_REDNESS_SWELLING")));
        JsonNode afterRecord = getOrder(heavier);
        assertThat(afterRecord.get("secondCheck").get("state").asText()).isEqualTo("CLEARED");
        assertThat(afterRecord.get("fingerprint").asText()).isNotEqualTo(confirmed.fingerprint());
        assertThat(auditCount(clinic, "CHEMO_SECOND_CHECK_CLEARED")).isOne();
        confirmBySignIn(new Sheet(heavier, afterRecord), nurse);
        // naming someone else removes the confirmation of the first
        JsonNode renamed = put(orderUrl(sheet.rec()) + "/checker", sheet.rec().token(),
                "{\"revision\":" + getOrder(heavier).get("revision").asLong() + ",\"checkerMembershipId\":\"" + pharmacist.membershipId() + "\"}", 200);
        assertThat(renamed.get("secondCheck").get("state").asText()).isEqualTo("CLEARED");
        assertThat(renamed.get("checker").get("role").asText()).isEqualTo("PHARMACIST");
        assertThat(auditCount(clinic, "CHEMO_SECOND_CHECK_CLEARED")).isEqualTo(2);
        assertThat(get(CHEMO_CHECKS, nurse.token(clinic)).get("total").asLong()).as("the nurse is no longer asked").isZero();
        assertThat(get(CHEMO_CHECKS, pharmacist.token(clinic)).get("total").asLong()).isOne();
        problemOf(HttpMethod.GET, CHEMO_CHECKS + "/" + orderId(sheet), nurse.token(clinic), null, 403, "not-named-checker");
    }

    // ================= hold =================

    @Test
    void aDelayDecisionRecordsTheHoldWithNoSecondCheckAndTheOrderIsThenReadOnly() throws Exception {
        Rec rec = specialtyRec("onco");
        Rec delayed = saveScreen(rec, "{\"oncDecision\":{\"choice\":\"DELAY_1_WEEK\",\"reasonKey\":null}}",
                spSection("onc_bsa", oncExam(160, 60.0, "PORT", "FLUSHES_FREELY", "BLOOD_RETURN", "NO_REDNESS_SWELLING")),
                spSection("onc_labs", oncLabs(1, "{\"hb\":12.0,\"anc\":1000,\"plt\":250,\"cr\":0.8,\"bili\":0.6,\"alt\":25,\"lvef\":60}")), spSection("onc_ecog", "ONC_ECOG", "{\"grade\":1}"));
        assertThat(specialtyOf(delayed).get("onco").get("suggestion").asText()).as("a count out of range suggests a delay").isEqualTo("DELAY_1_WEEK");
        JsonNode held = post(orderUrl(delayed) + "/hold", delayed.token(), "{\"revision\":0}", 200);
        assertThat(held.get("status").asText()).isEqualTo("HELD");
        assertThat(held.get("hold").get("kind").asText()).isEqualTo("DELAY");
        assertThat(held.get("hold").get("reason").asText()).as("the default is the first failed reason").isNotBlank();
        assertThat(held.get("hold").get("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(held.get("send").get("holdMode").asBoolean()).isTrue();
        assertThat(auditRows(delayed.clinic(), "CHEMO_HOLD_RECORDED")).hasSize(1);
        assertThat(row(delayed.clinic(), "select status, hold_kind, checker_membership_id::text, confirmed_at::text from opd.chemo_order")).containsExactly("HELD", "DELAY", null, null);
        assertThat(post(orderUrl(delayed) + "/hold", delayed.token(), "{\"revision\":" + held.get("revision").asLong() + "}", 200).get("status").asText()).as("repeating answers 200").isEqualTo("HELD");
        assertThat(auditRows(delayed.clinic(), "CHEMO_HOLD_RECORDED")).as("repeating writes nothing").hasSize(1);
        problemOf(HttpMethod.PUT, orderUrl(delayed), delayed.token(), passingOrder(held.get("revision").asLong()), 409, "chemo-order-sent");
        problemOf(HttpMethod.POST, orderUrl(delayed) + "/send", delayed.token(), "{\"revision\":" + held.get("revision").asLong() + ",\"fingerprint\":\"" + held.get("fingerprint").asText() + "\"}", 409, "chemo-order-sent");
    }

    @Test
    void aStopDecisionRecordsATypedReasonAndAGiveDecisionCannotBeHeldAndMarkupIsRefused() throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode refused = problemOf(HttpMethod.POST, orderUrl(rec) + "/hold", rec.token(), "{\"revision\":0}", 409, "checks-failing");
        assertThat(refused.get("detail").asText()).isEqualTo("Record the hold needs a Delay or Stop decision.");
        problemOf(HttpMethod.POST, orderUrl(rec) + "/hold", rec.token(), "{\"revision\":0,\"reasonText\":\"<b>x</b>\"}", 400, "validation");
        problemOf(HttpMethod.POST, orderUrl(rec) + "/hold", rec.token(), "{\"revision\":0,\"reasonText\":\"" + "r".repeat(301) + "\"}", 400, "validation");
        assertThat(count(rec.clinic(), "select count(*) from opd.chemo_order")).as("a refused hold writes nothing").isZero();
        Rec stopRec = specialtyRec("onco");
        Rec stop = saveScreen(stopRec, "{\"oncDecision\":{\"choice\":\"STOP\",\"reasonKey\":null}}", spSection("onc_ecog", "ONC_ECOG", "{\"grade\":3}"));
        assertThat(specialtyOf(stop).get("onco").get("suggestion").asText()).isEqualTo("STOP");
        JsonNode held = post(orderUrl(stop) + "/hold", stop.token(), "{\"revision\":0,\"reasonText\":\"Moving to palliative care\"}", 200);
        assertThat(held.get("hold").get("kind").asText()).isEqualTo("STOP");
        assertThat(held.get("hold").get("reason").asText()).isEqualTo("Moving to palliative care");
    }

    // ================= cycles, weight change and the lifetime dose across visits =================

    @Test
    void theSecondCycleKnowsTheFirstOnesSentDoxorubicinWeightAndConsentAndRecalculatesWhenTheWeightMovesTenPercent() throws Exception {
        Sheet first = passingSheetFor("AC");
        Clinic clinic = first.rec().clinic();
        Staff nurse = nurse(clinic);
        Sheet confirmed = confirmBySignIn(nameChecker(first, nurse), nurse);
        post(orderUrl(first.rec()) + "/send", first.rec().token(), sendBody(confirmed), 200);
        assertThat(row(clinic, "select cycle_no::text from opd.chemo_order")).containsExactly("1");
        // the second visit of the same patient, 7 kg heavier (11.7 %)
        Rec second = oncoAssessed(anotherVisit(first.rec(), "onco"), 160, 67.0);
        JsonNode sheet = saveOrder(second, orderBody(0, "AC", 100, "\"allergyConfirmed\":true,\"scheme\":\"APPROVED\""));
        assertThat(check(sheet, "CONSENT_REGIMEN").get("passed").asBoolean()).as("an earlier SENT order of this protocol is the consent").isTrue();
        assertThat(sheet.get("protocolTags").get("AC").asText()).isEqualTo("IN_PLAN");
        assertThat(sheet.get("protocolTags").get("PACLI_3W").asText()).isEqualTo("NEW_CONSENT");
        assertThat(drug(sheet, "doxorubicin").get("lifetime").get("earlierMgM2").asDouble()).as("the first cycle's 60 mg/m2").isEqualTo(60.0);
        assertThat(drug(sheet, "doxorubicin").get("lifetime").get("totalMgM2").asDouble()).isEqualTo(120.0);
        assertThat(sheet.get("weightChangePct").asDouble()).as("(67 - 60) / 60 = 11.7 %").isBetween(11.6, 11.7);
        assertThat(sheet.get("recalculated").asBoolean()).isTrue();
        assertThat(row(clinic, "select cycle_no::text from opd.chemo_order where record_id = ?::uuid", second.id())).containsExactly("2");
        JsonNode view = specialtyOf(get(RECORDS + "/" + second.id(), second.token())).get("onco");
        assertThat(view.get("cycle1WeightKg").asDouble()).isEqualTo(60.0);
        assertThat(view.get("cycleNo").asInt()).isEqualTo(2);
        JsonNode history = get(RECORDS + "/" + second.id() + "/specialty-history", second.token());
        assertThat(history.get("oncologyCycles")).as("read from SENT orders").hasSize(1);
        assertThat(history.get("oncologyCycles").get(0).get("cycleNo").asInt()).isOne();
        assertThat(history.get("oncologyCycles").get(0).get("protocolKey").asText()).isEqualTo("AC");
    }

    private Sheet passingSheetFor(String protocol) throws Exception {
        Rec rec = oncoAssessed(newOnco());
        JsonNode order = saveOrder(rec, orderBody(0, protocol, 100, "\"allergyConfirmed\":true,\"consentNewSigned\":true,\"scheme\":\"APPROVED\""));
        assertThat(failing(order)).isEmpty();
        return new Sheet(rec, order);
    }

    // ================= roles =================

    @Test
    void everyRoleWithoutTheConsultationPermissionIsRefusedOnEveryOrderEndpointAndOnlyTheRecordsOwnDoctorMayUseThem() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        for (String role : NOT_DOCTOR_ROLES) {
            Staff who = addNonDoctor(clinic, role);
            for (Object[] call : new Object[][] {{HttpMethod.GET, orderUrl(sheet.rec()), null}, {HttpMethod.PUT, orderUrl(sheet.rec()), passingOrder(0)},
                    {HttpMethod.GET, orderUrl(sheet.rec()) + "/checker-options", null},
                    {HttpMethod.PUT, orderUrl(sheet.rec()) + "/checker", "{\"revision\":0,\"checkerMembershipId\":null}"},
                    {HttpMethod.POST, orderUrl(sheet.rec()) + "/second-check", "{\"orderRevision\":0,\"fingerprint\":\"" + "0".repeat(64) + "\",\"checkerMembershipId\":\"" + uuid() + "\",\"pin\":\"1234\"}"},
                    {HttpMethod.POST, orderUrl(sheet.rec()) + "/send", sendBody(sheet)}, {HttpMethod.POST, orderUrl(sheet.rec()) + "/hold", "{\"revision\":0}"}}) {
                problemOf((HttpMethod) call[0], (String) call[1], who.token(clinic), (String) call[2], 403, "forbidden");
            }
        }
        Staff claimant = addNonDoctor(clinic, "NURSE");
        problemOf(HttpMethod.GET, orderUrl(sheet.rec()), claimant.tokenClaiming(clinic, "DOCTOR"), null, 403, "forbidden");
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), claimant.tokenClaiming(clinic, "DOCTOR"), passingOrder(0), 403, "forbidden");
        Staff otherDoctor = addDoctor(clinic, "General physician");
        problemOf(HttpMethod.GET, orderUrl(sheet.rec()), otherDoctor.token(clinic), null, 403, "not-own-patient");
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), otherDoctor.token(clinic), passingOrder(sheet.revision()), 403, "not-own-patient");
        problemOf(HttpMethod.GET, orderUrl(sheet.rec()), anonymous(), null, 401, "unauthorized");
        assertThat(count(clinic, "select count(*) from opd.chemo_order")).isOne();
    }

    @Test
    void theCheckerListIsForNursesPharmacistsAndDoctorsAndTheOwnerOnlyWithDoctorDetails() throws Exception {
        Clinic clinic = newClinic();
        for (String role : CHECKER_ROLES) {
            Staff who = "NURSE".equals(role) || "PHARMACIST".equals(role) ? addNonDoctor(clinic, role) : addStaff(clinic, role, "Dr Checker", "General physician", "MBBS", "REG-" + shortId());
            JsonNode page = get(CHEMO_CHECKS, who.token(clinic));
            assertThat(page.get("items")).as(role).isEmpty();
            assertThat(page.get("total").asLong()).isZero();
            assertThat(page.get("page").asInt()).isZero();
        }
        for (String role : NOT_CHECKER_ROLES) {
            Staff who = addNonDoctor(clinic, role);
            problemOf(HttpMethod.GET, CHEMO_CHECKS, who.token(clinic), null, 403, "forbidden");
            problemOf(HttpMethod.GET, CHEMO_CHECKS + "/" + uuid(), who.token(clinic), null, 403, "forbidden");
            problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + uuid() + "/confirm", who.token(clinic), confirmBody("0".repeat(64)), 403, "forbidden");
        }
        Clinic bare = bareOwnerClinic();
        assertThat(call(HttpMethod.GET, CHEMO_CHECKS, bare.owner(), null).andReturn().getResponse().getStatus()).as("an owner without doctor details").isEqualTo(403);
        ownerWithDoctorDetails(bare, "General physician", "MBBS", "REG-" + shortId());
        assertThat(get(CHEMO_CHECKS, bare.owner()).get("total").asLong()).as("an owner with doctor details may be a checker").isZero();
        for (String page : new String[] {"-1", "1001", "abc"}) {
            assertThat(call(HttpMethod.GET, CHEMO_CHECKS + "?page=" + page, nurse(clinic).token(clinic), null).andReturn().getResponse().getStatus()).as(page).isEqualTo(400);
        }
        problemOf(HttpMethod.GET, CHEMO_CHECKS, anonymous(), null, 401, "unauthorized");
    }

    // ================= another clinic =================

    @Test
    void anotherClinicNeverSeesAnOrderASummaryOrAListAndCannotConfirmOrUseTheRecord() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Sheet named = nameChecker(sheet, nurse);
        String orderId = orderId(named);
        Clinic other = newClinic();
        Staff foreignNurse = nurse(other);
        Staff foreignDoctor = addDoctor(other, "General physician");
        problemOf(HttpMethod.GET, CHEMO_CHECKS + "/" + orderId, foreignNurse.token(other), null, 404, "order-not-found");
        problemOf(HttpMethod.POST, CHEMO_CHECKS + "/" + orderId + "/confirm", foreignNurse.token(other), confirmBody(named.fingerprint()), 404, "order-not-found");
        assertThat(get(CHEMO_CHECKS, foreignNurse.token(other)).get("total").asLong()).isZero();
        problemOf(HttpMethod.GET, orderUrl(sheet.rec()), foreignDoctor.token(other), null, 404, "record-not-found");
        problemOf(HttpMethod.PUT, orderUrl(sheet.rec()), foreignDoctor.token(other), passingOrder(named.revision()), 404, "record-not-found");
        problemOf(HttpMethod.POST, orderUrl(sheet.rec()) + "/send", foreignDoctor.token(other), sendBody(named), 404, "record-not-found");
        assertThat(count(other, "select count(*) from opd.chemo_order")).isZero();
        assertThat(count(clinic, "select count(*) from opd.chemo_order")).isOne();
        assertThat(orderRow(clinic, orderId).get(0)).as("nothing changed").isEqualTo("DRAFT");
    }

    // ================= audit and privacy =================

    @Test
    void theAuditOfTheWholeFlowCarriesIdsAndKeysOnlyNeverADoseADrugTheLabsOrAPatientName() throws Exception {
        Sheet sheet = passingSheet();
        Clinic clinic = sheet.rec().clinic();
        Staff nurse = nurse(clinic);
        Sheet confirmed = confirmBySignIn(nameChecker(sheet, nurse), nurse);
        post(orderUrl(sheet.rec()) + "/send", sheet.rec().token(), sendBody(confirmed), 200);
        for (String action : new String[] {"CHEMO_ORDER_SAVED", "CHEMO_CHECKER_NAMED", "CHEMO_SECOND_CHECK_CONFIRMED", "CHEMO_ORDER_SENT"}) {
            assertThat(auditRows(clinic, action)).as(action).hasSize(1);
        }
        String all = allAuditText(clinic);
        assertThat(all).doesNotContain("paclitaxel").doesNotContain("Paclitaxel").doesNotContain("finalMg").doesNotContain("dosePerM2").doesNotContain("Ramesh").doesNotContain("Kulkarni")
                .doesNotContain("fingerprint");
        assertThat(auditRows(clinic, "CHEMO_ORDER_SENT").get(0).get(2)).contains("PACLI_3W").contains("SIGN_IN").contains(nurse.userId().toString());
    }
}
