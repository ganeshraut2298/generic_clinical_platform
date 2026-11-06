package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-27 what people see: the Discharge sheet (preview, bill, flags, a pure read), the discharged admission read view
 * (frozen, read-only, also in Read-only state, and rows marked DISCHARGED before this story), the counters and flags
 * "Going home today", and how the list, the board and the deposit alerts follow a discharge. Test plan:
 * docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeViewsIT extends DischargeSupport {

    // ---------------- the sheet ----------------

    @Test
    void theSheetHoldsTheTypesTheTemplatesThePreviewAndTheBillExactly() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitWithReason(clinic, "Community acquired pneumonia").get("id").asText();
        JsonNode s = sheet(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        String bed = values(clinic, "select b.code from ipd.bed b join ipd.admission a on a.bed_id = b.id where a.id = ?::uuid",
                id).get(0);

        assertThat(s.get("admissionId").asText()).isEqualTo(id);
        assertThat(s.get("ipNumber").asText()).isEqualTo(detail(clinic, id).get("ipNumber").asText());
        assertThat(s.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(s.get("bedCode").asText()).isEqualTo(bed);
        assertThat(s.get("dayNumber").asInt()).isOne();
        assertThat(s.get("approvalChain").asBoolean()).isTrue();
        assertThat(sheetOrder(s.get("types"))).isEqualTo("normal,lama,referred,death");
        assertThat(s.get("types").get(1).get("label").asText()).isEqualTo("On request (LAMA)");
        assertThat(s.get("types").get(0).get("label").asText()).isEqualTo("Normal");
        assertThat(s.get("types").get(2).get("label").asText()).isEqualTo("Referred");
        assertThat(s.get("types").get(3).get("label").asText()).isEqualTo("Death");
        assertThat(sheetOrder(s.get("templates"))).isEqualTo("general,surgical,maternity,daycare");
        assertThat(s.get("templates").get(0).get("label").asText()).isEqualTo("General medicine");
        assertThat(s.get("templates").get(1).get("label").asText()).isEqualTo("Surgical");
        assertThat(s.get("templates").get(2).get("label").asText()).isEqualTo("Maternity");
        assertThat(s.get("templates").get(3).get("label").asText()).isEqualTo("Day-care");
        assertThat(s.get("defaultType").asText()).isEqualTo("normal");
        assertThat(s.get("defaultTemplateKey").asText()).isEqualTo("general");
        assertThat(s.get("signature").isNull()).isTrue();

        JsonNode p = s.get("preview");
        assertThat(p.get("diagnosis").asText()).isEqualTo("Community acquired pneumonia");
        assertThat(p.get("stayDays").asInt()).isOne();
        assertThat(p.get("bedCode").asText()).isEqualTo(bed);
        assertThat(p.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(p.get("doctorShortName").asText()).isEqualTo("Dr Asha");
        assertThat(p.get("doctorSpecialty").asText()).isEqualTo("General physician");
        assertThat(s.get("bill").get("totalPaise").asLong()).isEqualTo(DAY);
        assertThat(s.get("bill").get("depositsPaise").asLong()).isEqualTo(DEPOSIT);
        assertThat(s.get("bill").get("balancePaise").asLong()).isEqualTo(DAY - DEPOSIT);
        assertThat(s.get("followUpDays").asInt()).isEqualTo(7);
        assertThat(s.get("followUpDate").asText()).isEqualTo(day.plusDays(7).toString());
    }

    @Test
    void theStayCountsTheBillDaysAndTheTotalFollowsExtrasAndTheSheetReadsTheBillAsItIsNow() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 4).get("id").asText();
        JsonNode s = sheet(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(s.get("dayNumber").asInt()).isEqualTo(4);
        assertThat(s.get("preview").get("stayDays").asInt()).isEqualTo(4);
        assertThat(totalOf(s)).isEqualTo(4 * DAY);
        charge(clinic, id, "ecg");
        assertThat(totalOf(sheet(clinic, id))).isEqualTo(4 * DAY + 30_000L);
        deposit(clinic, id, 250_000L);
        JsonNode after = sheet(clinic, id);
        assertThat(after.get("bill").get("depositsPaise").asLong()).isEqualTo(DEPOSIT + 250_000L);
        assertThat(after.get("bill").get("balancePaise").asLong()).isEqualTo(4 * DAY + 30_000L - DEPOSIT - 250_000L);
    }

    @Test
    void openingTheSheetChangesNothingButTheAccessLogAndWritesNoSettingsRowOrAuditRow() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        long config = rows(clinic, "config");
        Written before = written(clinic, id);
        for (String role : READERS) {
            sheetAs(clinic, role, id);
        }
        assertThat(rows(clinic, "config")).isEqualTo(config);
        // security review M3: each open of the sheet writes exactly one patient access row, nothing else changes
        assertThat(written(clinic, id)).usingRecursiveComparison().ignoringFields("access").isEqualTo(before);
        assertThat(written(clinic, id).access()).isEqualTo(before.access() + READERS.length);
        // reading the setup creates no row either
        for (String role : READERS) {
            ok(HttpMethod.GET, DISCHARGE_SETTINGS, clinic.as(role), null, 200);
        }
        assertThat(rows(clinic, "config")).isEqualTo(config);
    }

    @Test
    void theAdmissionSheetOffersStartDischargeToTheFourReaderRolesWhileAdmittedAndNotInReadOnly() throws Exception {
        Clinic clinic = hospital();
        String live = quickAdmit(clinic).get("id").asText();
        for (String role : READERS) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + live, clinic.as(role), null, 200).get("canStartDischarge").asBoolean())
                    .as(role).isTrue();
        }
        JsonNode d = detail(clinic, live);
        assertThat(d.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(d.get("discharge").isNull()).isTrue();
        assertThat(d.get("finalBill").isNull()).isTrue();
        assertThat(d.get("dischargedAt").isNull()).isTrue();
        expireTrial(clinic);
        for (String role : READERS) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + live, clinic.as(role), null, 200).get("canStartDischarge").asBoolean())
                    .as(role + " read-only").isFalse();
        }
    }

    // ---------------- the discharged admission ----------------

    @Test
    void aDischargedAdmissionIsReadableFrozenAndHasNothingLeftToDoForEveryReaderRole() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 3).get("id").asText();
        charge(clinic, id, "xray");
        deposit(clinic, id, 100_000L);
        UUID bed = bedOf(clinic, id);
        String mobile = null;
        JsonNode done = signAndDischarge(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(done.get("status").asText()).isEqualTo("DISCHARGED");

        for (String role : READERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null, 200);
            assertThat(d.get("status").asText()).as(role).isEqualTo("DISCHARGED");
            assertThat(d.get("dischargedAt").asText()).isEqualTo(done.get("dischargedAt").asText());
            for (String flag : new String[] {"canCollectDeposit", "canAddCharge", "canTransfer", "canStartDischarge", "depositLow",
                    "goingHomeToday"}) {
                assertThat(d.get(flag).asBoolean()).as(role + " " + flag).isFalse();
            }
            assertThat(d.get("dayNumber").asInt()).isEqualTo(3);
            assertThat(d.get("runningBillPaise").asLong()).isEqualTo(d.get("finalBill").get("totalPaise").asLong());
            assertThat(d.get("finalBill").get("totalPaise").asLong()).isEqualTo(3 * DAY + 45_000L);
            assertThat(d.get("finalBill").get("depositsPaise").asLong()).isEqualTo(DEPOSIT + 100_000L);
            assertThat(d.get("deposits")).hasSize(2);
            assertThat(d.get("discharge").get("type").asText()).isEqualTo("normal");
            assertThat(d.get("discharge").get("templateKey").asText()).isEqualTo("general");
            assertThat(d.get("discharge").get("signedByName").asText()).isNotBlank();
            assertThat(d.get("discharge").get("dischargedByName").asText()).isNotBlank();
            assertThat(d.get("finalBill").get("settledByName").asText()).isNotBlank();
            assertThat(d.get("finalBill").get("settledAt").asText()).isNotBlank();
            // the mobile number is for the owner and front desk only
            assertThat(d.hasNonNull("mobile")).as(role + " mobile").isEqualTo("OWNER".equals(role) || "FRONT_DESK".equals(role));
            assertBill(d.get("finalBill"), current("gen", 3), nursing(3), new L("Chest X-ray", 45_000, 1, false));
        }
        for (String role : NON_READERS) {
            problem(call(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null), 403);
        }

        // the bed it left is reused: the discharged sheet still names the bed the patient was in
        String code = detail(clinic, id).get("bedCode").asText();
        ok(HttpMethod.POST, BEDS + "/" + bed + "/ready", clinic.owner(), null, 200);
        admitOk(clinic, body(newPatient(clinic), clinic.doctorMembershipId(), bed));
        assertThat(detail(clinic, id).get("bedCode").asText()).isEqualTo(code);
        assertThat(detail(clinic, id).get("categoryName").asText()).isEqualTo("General");
        assertThat(mobile).isNull();
    }

    @Test
    void aDischargedAdmissionStaysReadableInReadOnlyStateWithEverythingFrozen() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        JsonNode done = signAndDischarge(clinic, id);
        expireTrial(clinic);
        for (String role : READERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + id, clinic.as(role), null, 200);
            assertThat(d.get("status").asText()).isEqualTo("DISCHARGED");
            assertThat(lineKeys(d.get("finalBill"))).isEqualTo(lineKeys(done.get("finalBill")));
            assertThat(d.get("canStartDischarge").asBoolean()).isFalse();
        }
    }

    @Test
    void anAdmissionMarkedDischargedBeforeThisStoryHasNoFinalBillAndStillOpensWithNothingToDo() throws Exception {
        Clinic clinic = hospital();
        String legacy = quickAdmit(clinic).get("id").asText();
        String live = quickAdmit(clinic).get("id").asText();
        discharge(clinic, legacy);            // status DISCHARGED by SQL: no sign-off, no final bill, no time
        JsonNode d = detail(clinic, legacy);
        assertThat(d.get("status").asText()).isEqualTo("DISCHARGED");
        assertThat(d.get("finalBill").isNull()).isTrue();
        assertThat(d.get("discharge").isNull()).isTrue();
        for (String flag : new String[] {"canCollectDeposit", "canAddCharge", "canTransfer", "canStartDischarge", "goingHomeToday"}) {
            assertThat(d.get(flag).asBoolean()).as(flag).isFalse();
        }
        assertThat(d.get("bill").get("lines")).isNotEmpty();
        assertType(problem(call(HttpMethod.GET, dischargeUrl(legacy), clinic.owner(), null), 409), "not-admitted");
        assertType(problem(postDischarge(clinic.owner(), legacy, dischargeBody("normal", "general", DAY, "UPI")), 409), "not-admitted");
        JsonNode list = list(clinic);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(live);
        assertThat(list.get("dischargedToday").asInt()).isZero();
    }

    // ---------------- list, board and tiles ----------------

    @Test
    void theListAndTheBoardFollowADischargeTheBedIsCleaningTheCountersMoveAndTheDepositAlertGoes() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String low = admitGeneral(clinic, 6).get("id").asText();         // 9,600 of 10,000: Deposit low
        String other = quickAdmit(clinic).get("id").asText();
        UUID bed = bedOf(clinic, low);
        newBed(clinic, "gen");                                            // one more free bed
        JsonNode before = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(before.get("depositAlerts").asInt()).isOne();
        assertThat(row(before, low).get("depositLow").asBoolean()).isTrue();
        assertThat(before.get("bedsOccupied").asInt()).isEqualTo(2);
        assertThat(before.get("bedsAvailable").asInt()).isOne();
        assertThat(before.get("dischargedToday").asInt()).isZero();
        assertThat(before.get("goingHomeToday").asInt()).isZero();
        JsonNode boardBefore = board(clinic);
        assertThat(boardBefore.get("counts").get("occ").asInt()).isEqualTo(2);
        assertThat(boardBefore.get("counts").get("clean").asInt()).isZero();

        signAndDischarge(clinic, low);
        JsonNode after = list(clinic);
        assertThat(after.get("items")).hasSize(1);
        assertThat(after.get("items").get(0).get("id").asText()).isEqualTo(other);
        assertThat(after.get("depositAlerts").asInt()).as("the discharged patient left the alert count").isZero();
        assertThat(after.get("bedsOccupied").asInt()).isOne();
        assertThat(after.get("bedsAvailable").asInt()).as("the bed is Cleaning, not available").isOne();
        assertThat(after.get("dischargedToday").asInt()).isOne();
        assertThat(after.get("goingHomeToday").asInt()).isZero();
        JsonNode boardAfter = board(clinic);
        assertThat(boardAfter.get("counts").get("occ").asInt()).isOne();
        assertThat(boardAfter.get("counts").get("clean").asInt()).isOne();
        JsonNode tile = tile(boardAfter, bed);
        assertThat(tile.get("status").asText()).isEqualTo("clean");
        assertThat(tile.hasNonNull("patientName")).isFalse();
        assertThat(tile.path("goingHomeToday").asBoolean(false)).isFalse();

        // "Cleaned · ready" makes the bed available again
        ok(HttpMethod.POST, BEDS + "/" + bed + "/ready", clinic.owner(), null, 200);
        assertThat(list(clinic).get("bedsAvailable").asInt()).isEqualTo(2);
        // only the Admitted list exists (OQ-27-6)
        problem(call(HttpMethod.GET, ADMISSIONS + "?status=DISCHARGED", clinic.owner(), null), 400);
    }

    @Test
    void aSignatureMadeTodayMakesTheAdmittedPatientGoingHomeInTheRowTheTileTheSheetAndTheCounters() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String signed = quickAdmit(clinic).get("id").asText();
        String unsigned = quickAdmit(clinic).get("id").asText();
        String done = quickAdmit(clinic).get("id").asText();
        UUID signedBed = bedOf(clinic, signed);
        UUID unsignedBed = bedOf(clinic, unsigned);
        signNormal(clinic, signed);
        signAndDischarge(clinic, done);
        JsonNode list = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

        assertThat(row(list, signed).get("goingHomeToday").asBoolean()).isTrue();
        assertThat(row(list, unsigned).get("goingHomeToday").asBoolean()).isFalse();
        assertThat(list.get("goingHomeToday").asInt()).isOne();
        assertThat(list.get("dischargedToday").asInt()).isOne();
        JsonNode board = board(clinic);
        assertThat(tile(board, signedBed).get("goingHomeToday").asBoolean()).isTrue();
        assertThat(tile(board, unsignedBed).get("goingHomeToday").asBoolean()).isFalse();
        assertThat(detail(clinic, signed).get("goingHomeToday").asBoolean()).isTrue();
        assertThat(detail(clinic, unsigned).get("goingHomeToday").asBoolean()).isFalse();
        // every reader sees the same flag
        for (String role : READERS) {
            assertThat(ok(HttpMethod.GET, ADMISSIONS, clinic.as(role), null, 200).get("goingHomeToday").asInt()).as(role).isOne();
        }
        // a second signature (other type) keeps the patient planned; discharging with it moves M to N
        sign(clinic, signed, "referred", "surgical");
        assertThat(list(clinic).get("goingHomeToday").asInt()).isOne();
        dischargeNow(clinic, signed, "referred", "surgical", "UPI");
        JsonNode end = list(clinic);
        assertThat(end.get("goingHomeToday").asInt()).isZero();
        assertThat(end.get("dischargedToday").asInt()).isEqualTo(2);
        assertThat(tile(board(clinic), signedBed).path("goingHomeToday").asBoolean(false)).isFalse();
    }

    @Test
    void aSignatureFromYesterdayDoesNotMakeAPatientGoingHomeTodayAndANewOneDoes() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = quickAdmit(clinic).get("id").asText();
        insertSignoffAt(clinic, id, "normal", "general", istMidnight(day).minusSeconds(3600));
        JsonNode list = list(clinic);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(row(list, id).get("goingHomeToday").asBoolean()).isFalse();
        assertThat(list.get("goingHomeToday").asInt()).isZero();
        assertThat(detail(clinic, id).get("goingHomeToday").asBoolean()).isFalse();
        JsonNode s = sheet(clinic, id);
        assertThat(s.get("signature").get("type").asText()).as("the old signature still applies to a discharge").isEqualTo("normal");
        // today a doctor signs again (a different choice, so it is a new row)
        sign(clinic, id, "lama", "general");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(row(list(clinic), id).get("goingHomeToday").asBoolean()).isTrue();
        assertThat(list(clinic).get("goingHomeToday").asInt()).isOne();
    }

    @Test
    void aSignatureFromYesterdayStillAllowsTheDischargeAndCountsItAsDischargedToday() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = quickAdmit(clinic).get("id").asText();
        insertSignoffAt(clinic, id, "normal", "general", istMidnight(day).minusSeconds(3600));
        JsonNode d = dischargeNow(clinic, id, "normal", "general", "UPI");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(d.get("discharge").get("signedAt").asText()).startsWith(
                istMidnight(day).minusSeconds(3600).toString().substring(0, 19));
        assertThat(list(clinic).get("dischargedToday").asInt()).isOne();
    }
}
