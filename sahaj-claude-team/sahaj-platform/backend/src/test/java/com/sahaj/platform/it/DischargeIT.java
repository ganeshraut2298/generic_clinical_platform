package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-27 what a discharge does, on the real {@code sahaj_test} database: the balance in its three cases with exact
 * paise, the final bill frozen identical to the running bill and unchanged after a rate change, the signature rules,
 * everything one discharge writes in one transaction, the follow-up date and reason, Death without a follow-up and the
 * default template. Check order and roles are in {@link DischargeChecksIT}, screens in {@link DischargeViewsIT}.
 * Test plan: docs/test-plans/SAH-27.md.
 */
@OnTestDatabase
class DischargeIT extends DischargeSupport {

    private static final String BILL_SQL = "select total_paise, deposits_paise, balance_paise, coalesce(settle_mode, 'null'), "
            + "bill_days, bed_day_cut, category_key, multiplier::text, created_by::text from ipd.final_bill "
            + "where admission_id = ?::uuid";

    // ---------------- the balance: collect, refund, settled ----------------

    @Test
    void aBalanceToCollectIsTheServersTotalMinusDepositsAndIsStoredWithTheModeAndWhoSettled() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 10).get("id").asText();
        JsonNode running = detail(clinic, id).get("bill");
        assertBill(running, generalStay(10));
        signNormal(clinic, id);
        JsonNode s = sheet(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(s.get("bill").get("totalPaise").asLong()).isEqualTo(1_600_000L);
        assertThat(s.get("bill").get("depositsPaise").asLong()).isEqualTo(1_000_000L);
        assertThat(s.get("bill").get("balancePaise").asLong()).isEqualTo(600_000L);

        // extra JSON that tries to choose the amount, the clinic or the actor is ignored
        String json = "{\"type\":\"normal\",\"templateKey\":\"general\",\"expectedTotalPaise\":1600000,\"expectedDepositsPaise\":1000000,\"mode\":\"Cash\","
                + "\"balancePaise\":1,\"amountPaise\":5,\"collectedPaise\":7,\"tenantId\":\"" + UUID.randomUUID()
                + "\",\"completedBy\":\"" + UUID.randomUUID() + "\"}";
        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), json, 200);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

        JsonNode fb = d.get("finalBill");
        assertThat(fb.get("totalPaise").asLong()).isEqualTo(1_600_000L);
        assertThat(fb.get("depositsPaise").asLong()).isEqualTo(1_000_000L);
        assertThat(fb.get("balancePaise").asLong()).isEqualTo(600_000L);
        assertThat(fb.get("outcome").asText()).isEqualTo("collected");
        assertThat(fb.get("mode").asText()).isEqualTo("Cash");
        assertThat(fb.get("billDays").asInt()).isEqualTo(10);
        assertBill(fb, generalStay(10));
        assertThat(values(clinic, BILL_SQL, id)).containsExactly("1600000", "1000000", "600000", "Cash", "10", "midnight",
                "gen", "1.00", clinic.ownerUserId().toString());
        assertThat(storedLines(clinic, id)).containsExactly("Bed · General|80000|10|800000|false",
                "Doctor visits · Dr Asha|50000|10|500000|false", "Nursing care|30000|10|300000|false");
    }

    @Test
    void aBalanceToRefundIsPaidBackByTheOwnerWithTheChosenModeAndStoredNegative() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, id);
        JsonNode s = sheet(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(s.get("bill").get("balancePaise").asLong()).isEqualTo(DAY - DEPOSIT);   // -840000
        assertThat(s.get("canRefund").asBoolean()).isTrue();

        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), dischargeBody("normal", "general", DAY, "Cash"), 200);
        JsonNode fb = d.get("finalBill");
        assertThat(fb.get("balancePaise").asLong()).isEqualTo(-840_000L);
        assertThat(fb.get("outcome").asText()).isEqualTo("refunded");
        assertThat(fb.get("mode").asText()).isEqualTo("Cash");
        assertThat(fb.get("totalPaise").asLong()).isEqualTo(160_000L);
        assertThat(fb.get("depositsPaise").asLong()).isEqualTo(1_000_000L);
        assertBill(fb, generalStay(1));
        assertThat(values(clinic, BILL_SQL, id)).containsExactly("160000", "1000000", "-840000", "Cash", "1", "midnight",
                "gen", "1.00", clinic.ownerUserId().toString());
    }

    @Test
    void aBalanceOfZeroIsSettledWithNoModeEvenWhenOneIsSent() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 6).get("id").asText();
        charge(clinic, id, "dressing");        // 6 x 1600 + 400 = 10000 = the deposit
        signNormal(clinic, id);
        JsonNode s = sheet(clinic, id);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(s.get("bill").get("totalPaise").asLong()).isEqualTo(1_000_000L);
        assertThat(s.get("bill").get("balancePaise").asLong()).isZero();

        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), dischargeBody("normal", "general", 1_000_000L, "Card"), 200);
        JsonNode fb = d.get("finalBill");
        assertThat(fb.get("outcome").asText()).isEqualTo("settled");
        assertThat(fb.get("balancePaise").asLong()).isZero();
        assertThat(fb.get("mode").isNull()).isTrue();
        assertBill(fb, current("gen", 6), nursing(6), DRESSING);
        assertThat(values(clinic, BILL_SQL, id)).containsExactly("1000000", "1000000", "0", "null", "6", "midnight", "gen",
                "1.00", clinic.ownerUserId().toString());
    }

    @Test
    void aSettledBillNeedsNoModeAtAll() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 6).get("id").asText();
        charge(clinic, id, "dressing");
        signNormal(clinic, id);
        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), dischargeBody("normal", "general", 1_000_000L, null), 200);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(d.get("finalBill").get("outcome").asText()).isEqualTo("settled");
        assertThat(values(clinic, "select coalesce(settle_mode, 'null') from ipd.final_bill where admission_id = ?::uuid", id))
                .containsExactly("null");
    }

    @Test
    void aDepositAddedBetweenTheSheetAndTheDischargeIsBillChangedAndTheRetryWithTheNewDepositsSettlesTheNewBalance()
            throws Exception {
        // F-27-2 closed: the request carries the deposits the user saw; a deposit that arrived since moves the balance
        // to collect or refund, so the old figures are refused (409 bill-changed) and nothing is frozen.
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 10).get("id").asText();     // total 16000, deposit 10000, collect 6000
        signNormal(clinic, id);
        JsonNode s = sheet(clinic, id);
        assertThat(s.get("bill").get("balancePaise").asLong()).isEqualTo(600_000L);
        deposit(clinic, id, 200_000L);                                // the family pays 2000 more at the counter
        Written before = written(clinic, id);
        JsonNode stale = problem(postDischarge(clinic.owner(), id,
                dischargeBody("normal", "general", totalOf(s), depositsOf(s), "UPI")), 409);
        assertType(stale, "bill-changed");
        assertThat(stale.get("detail").asText()).isEqualTo(BILL_CHANGED);
        assertThat(written(clinic, id)).as("nothing frozen by the refused call").isEqualTo(before);
        assertThat(rows(clinic, "final_bill")).isZero();
        assertThat(rows(clinic, "discharge")).isZero();

        JsonNode fresh = sheet(clinic, id);                           // the reloaded sheet shows the new balance
        assertThat(depositsOf(fresh)).isEqualTo(1_200_000L);
        assertThat(fresh.get("bill").get("balancePaise").asLong()).isEqualTo(400_000L);
        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(),
                dischargeBody("normal", "general", totalOf(fresh), depositsOf(fresh), "UPI"), 200);
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(d.get("finalBill").get("depositsPaise").asLong()).isEqualTo(1_200_000L);
        assertThat(d.get("finalBill").get("balancePaise").asLong()).as("total minus the new deposits")
                .isEqualTo(400_000L);
        assertThat(values(clinic, "select balance_paise::text from ipd.final_bill where admission_id = ?::uuid", id))
                .containsExactly("400000");
    }

    // ---------------- the frozen bill ----------------

    @Test
    void theFrozenFinalBillIsTheRunningBillAtThatMomentAndNoRateChangeEverMovesIt() throws Exception {
        Clinic clinic = hospital();
        setRules(clinic, false, "same");
        LocalDate day = istToday();
        String id = admitGeneral(clinic, 3).get("id").asText();
        charge(clinic, id, "xray");
        charge(clinic, id, "xray");
        moveOk(clinic, id, newBed(clinic, "pvt"));                    // so that segment lines exist
        JsonNode running = detail(clinic, id).get("bill");
        List<String> runningLines = lineKeys(running);
        assertThat(runningLines).hasSizeGreaterThan(5);
        signNormal(clinic, id);

        JsonNode d = dischargeNow(clinic, id, "normal", "general", "UPI");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(lineKeys(d.get("finalBill"))).as("final bill lines = running bill lines").isEqualTo(runningLines);
        assertThat(d.get("finalBill").get("totalPaise").asLong()).isEqualTo(running.get("totalPaise").asLong());
        assertThat(lineKeys(d.get("bill"))).as("the sheet of a discharged admission shows the frozen bill").isEqualTo(runningLines);
        assertThat(d.get("finalBill").get("lines").get(0).get("segment").asBoolean()).isTrue();
        assertThat(storedLines(clinic, id)).hasSameSizeAs(runningLines);

        // rates change in Clinic setup: nothing on the discharged admission moves
        setCharges(clinic, "24h", 99_000L, 77_000L);
        setCategory(clinic, "gen", 123_400L, "1.40");
        setCategory(clinic, "pvt", 444_400L, "2.00");
        JsonNode after = detail(clinic, id);
        assertThat(lineKeys(after.get("finalBill"))).isEqualTo(runningLines);
        assertThat(lineKeys(after.get("bill"))).isEqualTo(runningLines);
        assertThat(after.get("finalBill").get("totalPaise").asLong()).isEqualTo(running.get("totalPaise").asLong());
        assertThat(after.get("runningBillPaise").asLong()).isEqualTo(running.get("totalPaise").asLong());
        assertThat(after.get("dayNumber").asInt()).isEqualTo(3);
        assertThat(storedLines(clinic, id)).isEqualTo(runningLines.stream().map(k -> k).toList());
        assertThat(values(clinic, "select bed_day_cut, category_key, multiplier::text from ipd.final_bill "
                + "where admission_id = ?::uuid", id)).containsExactly("midnight", "pvt", "1.50");
    }

    // ---------------- signature rules ----------------

    @Test
    void aSignedSummaryAppliesOnlyToTheTypeAndTemplateItWasSignedForAndTheLatestSignatureWins() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        assertThat(sheet(clinic, id).get("signature").isNull()).isTrue();
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, "UPI")), 409);

        JsonNode signed = sign(clinic, id, "normal", "general");
        assertThat(signed.get("signature").get("type").asText()).isEqualTo("normal");
        assertThat(signed.get("signature").get("templateKey").asText()).isEqualTo("general");
        assertThat(signed.get("signature").get("signedByName").asText()).isNotBlank();
        assertThat(rows(clinic, "discharge_signoff")).isOne();

        // another type, another template: not signed for that choice
        for (String[] choice : new String[][] {{"lama", "general"}, {"normal", "surgical"}, {"death", "daycare"}}) {
            JsonNode p = problem(postDischarge(clinic.owner(), id, dischargeBody(choice[0], choice[1], DAY, "UPI")), 409);
            assertType(p, "summary-not-signed");
            assertThat(p.get("detail").asText()).isEqualTo(SIGN_FIRST);
        }
        assertThat(rows(clinic, "discharge")).isZero();

        // signing the other type makes it the latest: the first choice no longer applies
        sign(clinic, id, "lama", "general");
        assertThat(rows(clinic, "discharge_signoff")).isEqualTo(2);
        assertThat(sheet(clinic, id).get("signature").get("type").asText()).isEqualTo("lama");
        assertType(problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, "UPI")), 409),
                "summary-not-signed");
        // signing the first choice again is a new signature (the latest differs)
        sign(clinic, id, "normal", "general");
        assertThat(rows(clinic, "discharge_signoff")).isEqualTo(3);
        dischargeNow(clinic, id, "normal", "general", "UPI");
        assertThat(values(clinic, "select discharge_type, template_key from ipd.discharge where admission_id = ?::uuid", id))
                .containsExactly("normal", "general");
    }

    @Test
    void signingTheSameTypeAndTemplateAgainWritesNothingAndKeepsTheFirstSignature() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        JsonNode first = sign(clinic, id, "referred", "surgical");
        long audits = audits(clinic, "IPD_DISCHARGE_SIGNED");
        assertThat(audits).isOne();
        for (int i = 0; i < 3; i++) {
            JsonNode again = sign(clinic, id, "referred", "surgical");
            assertThat(again.get("signature").get("signedAt").asText()).isEqualTo(first.get("signature").get("signedAt").asText());
        }
        assertThat(rows(clinic, "discharge_signoff")).isOne();
        assertThat(audits(clinic, "IPD_DISCHARGE_SIGNED")).isOne();
    }

    @Test
    void theDischargeKeepsTheSigningDoctorsNameAndTheSignatureCopiedFromTheSignoffRow() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        RequestPostProcessor doctor = otherPerson(clinic, "DOCTOR");
        JsonNode signed = ok(HttpMethod.PUT, signoffUrl(id), doctor, signBody("normal", "general"), 200);
        assertThat(signed.get("signature").get("signedByName").asText()).isEqualTo("Member DOCTOR");
        JsonNode d = ok(HttpMethod.POST, dischargeUrl(id), clinic.owner(), dischargeBody("normal", "general", DAY, "UPI"), 200);
        assertThat(d.get("discharge").get("signedByName").asText()).isEqualTo("Member DOCTOR");
        assertThat(d.get("discharge").get("signedAt").asText()).isEqualTo(signed.get("signature").get("signedAt").asText());
        assertThat(d.get("discharge").get("dischargedByName").asText()).isNotEqualTo("Member DOCTOR");
        assertThat(values(clinic, "select (d.signed_by = s.signed_by and d.signed_at = s.signed_at and d.signoff_id = s.id "
                + "and d.discharge_type = s.discharge_type and d.template_key = s.template_key "
                + "and d.signed_by <> d.completed_by and d.completed_by = ?::uuid)::text "
                + "from ipd.discharge d join ipd.discharge_signoff s on s.id = d.signoff_id where d.admission_id = ?::uuid",
                clinic.ownerUserId().toString(), id)).containsExactly("true");
    }

    @Test
    void withTheApprovalChainOffTheSummaryMustStillBeSigned() throws Exception {
        Clinic clinic = hospital();
        putSettings(clinic, null, false, 7);
        String id = quickAdmit(clinic).get("id").asText();
        assertThat(sheet(clinic, id).get("approvalChain").asBoolean()).isFalse();
        assertType(problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, "UPI")), 409),
                "summary-not-signed");
        signNormal(clinic, id);
        dischargeNow(clinic, id, "normal", "general", "UPI");
    }

    // ---------------- one transaction ----------------

    @Test
    void aDischargeIsOneStepAndLeavesTheSameStateInEveryTable() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        String id = quickAdmit(clinic).get("id").asText();
        UUID bed = bedOf(clinic, id);
        String patient = patientOf(clinic, id);
        long version = admissionVersion(clinic, id);
        long bedVersion = bedVersion(clinic, bed);
        sign(clinic, id, "referred", "general");
        assertThat(bedStatus(clinic, bed)).isEqualTo("occ");
        Instant before = Instant.now();

        JsonNode d = dischargeNow(clinic, id, "referred", "general", "UPI");
        Instant after = Instant.now();
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");

        assertThat(d.get("status").asText()).isEqualTo("DISCHARGED");
        Instant at = Instant.parse(d.get("dischargedAt").asText());
        assertThat(at).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        assertThat(d.get("discharge").get("dischargedAt").asText()).isEqualTo(d.get("dischargedAt").asText());
        assertThat(values(clinic, "select status, (discharged_at = ?::timestamptz)::text from ipd.admission where id = ?::uuid",
                d.get("dischargedAt").asText(), id)).containsExactly("DISCHARGED", "true");
        assertThat(admissionVersion(clinic, id)).isEqualTo(version + 1);
        assertThat(bedStatus(clinic, bed)).as("bed sent for cleaning").isEqualTo("clean");
        assertThat(bedVersion(clinic, bed)).isEqualTo(bedVersion + 1);
        assertThat(bedOf(clinic, id)).as("the admission keeps the bed it left").isEqualTo(bed);

        assertThat(rows(clinic, "final_bill")).isOne();
        assertThat(rows(clinic, "final_bill_line")).isEqualTo(3);
        assertThat(rows(clinic, "discharge")).isOne();
        assertThat(values(clinic, "select discharge_type, template_key, followup_days::text, followup_due::text, "
                + "(followup_due = (completed_at at time zone 'Asia/Kolkata')::date + followup_days)::text "
                + "from ipd.discharge where admission_id = ?::uuid", id))
                .containsExactly("referred", "general", "7", day.plusDays(7).toString(), "true");
        assertThat(d.get("discharge").get("followUpDate").asText()).isEqualTo(day.plusDays(7).toString());
        assertThat(d.get("discharge").get("followUpDays").asInt()).isEqualTo(7);

        // the follow-up: the admitting doctor, due on the discharge day + 7, the reason of the admission
        assertThat(values(clinic, "select patient_id::text, doctor_membership_id::text, due_date::text, reason, "
                + "coalesce(source_visit_id::text, 'null') from opd.follow_up where source_admission_id = ?::uuid", id))
                .containsExactly(patient, clinic.doctorMembershipId().toString(), day.plusDays(7).toString(),
                        "Review after discharge · Fever", "null");
        assertThat(audits(clinic, "FOLLOW_UP_CREATED")).isOne();
        assertThat(audits(clinic, "IPD_DISCHARGED")).isOne();
        assertThat(audit(clinic, "IPD_DISCHARGED").get(0)[1]).isEqualTo(id);
    }

    @Test
    void aFailedDischargeLeavesNothingBehindNotEvenAFollowUpOrABillRow() throws Exception {
        Clinic clinic = hospital();
        String id = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, id);
        // a real front desk member, added before the snapshot (adding a member writes an audit row)
        org.springframework.test.web.servlet.request.RequestPostProcessor frontDesk = otherPerson(clinic, "FRONT_DESK");
        Written before = written(clinic, id);
        // wrong total, wrong mode, wrong type and a refund by the wrong role: each refused, nothing written
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY + 1, "UPI")), 409);
        problem(postDischarge(clinic.owner(), id, dischargeBody("normal", "general", DAY, null)), 400);
        problem(postDischarge(clinic.owner(), id, dischargeBody("lama", "general", DAY, "UPI")), 409);
        // a real front desk member: the refund rule reads the role from the membership (ADR-007 R3), not from the token
        problem(postDischarge(frontDesk, id, dischargeBody("normal", "general", DAY, "UPI")), 403);
        assertThat(written(clinic, id)).isEqualTo(before);
        assertThat(bedStatus(clinic, bedOf(clinic, id))).isEqualTo("occ");
    }

    // ---------------- follow-up date and reason ----------------

    @Test
    void theFollowUpIsDueOnTheDischargeDayPlusTheDaysOfDischargeSetupAndAnEarlierChangeIsKeptOnTheRecord() throws Exception {
        Clinic clinic = hospital();
        LocalDate day = istToday();
        putSettings(clinic, null, true, 12);
        String id = quickAdmit(clinic).get("id").asText();
        String other = quickAdmit(clinic).get("id").asText();
        assertThat(sheet(clinic, id).get("followUpDays").asInt()).isEqualTo(12);
        signNormal(clinic, id);
        JsonNode d = dischargeNow(clinic, id, "normal", "general", "UPI");
        assumeTrue(day.equals(istToday()), "IST midnight passed while the test ran");
        assertThat(d.get("discharge").get("followUpDays").asInt()).isEqualTo(12);
        assertThat(d.get("discharge").get("followUpDate").asText()).isEqualTo(day.plusDays(12).toString());
        assertThat(values(clinic, "select due_date::text from opd.follow_up where source_admission_id = ?::uuid", id))
                .containsExactly(day.plusDays(12).toString());

        putSettings(clinic, null, true, 1);
        assertThat(detail(clinic, id).get("discharge").get("followUpDays").asInt()).as("the record keeps its own days")
                .isEqualTo(12);
        signNormal(clinic, other);
        JsonNode e = dischargeNow(clinic, other, "normal", "general", "UPI");
        assertThat(e.get("discharge").get("followUpDate").asText()).isEqualTo(day.plusDays(1).toString());
        putSettings(clinic, null, true, 30);
        String third = quickAdmit(clinic).get("id").asText();
        signNormal(clinic, third);
        assertThat(dischargeNow(clinic, third, "normal", "general", "UPI").get("discharge").get("followUpDate").asText())
                .isEqualTo(day.plusDays(30).toString());
    }

    @Test
    void theFollowUpReasonIsReviewAfterDischargeAndTheReasonCutAt200CodePointsNotUtf16Units() throws Exception {
        Clinic clinic = hospital();
        String prefix = "Review after discharge · ";
        String plain = "a".repeat(200);                                  // 25 + 200 code points: cut to 200
        String emoji = "😀".repeat(10) + "x".repeat(180);      // 200 units, 190 code points: 215 in all
        String pairsOnly = "😀".repeat(100);                   // 200 units, 100 code points: 125, no cut
        String[][] cases = {{"Pneumonia", prefix + "Pneumonia"}, {plain, prefix + "a".repeat(175)},
                {emoji, prefix + "😀".repeat(10) + "x".repeat(165)}, {pairsOnly, prefix + pairsOnly}};
        for (String[] c : cases) {
            String id = admitWithReason(clinic, c[0]).get("id").asText();
            signNormal(clinic, id);
            dischargeNow(clinic, id, "normal", "general", "UPI");
            String stored = values(clinic, "select reason from opd.follow_up where source_admission_id = ?::uuid", id).get(0);
            assertThat(stored).isEqualTo(c[1]);
            assertThat(stored.codePointCount(0, stored.length())).isLessThanOrEqualTo(200);
            assertThat(values(clinic, "select char_length(reason)::text from opd.follow_up where source_admission_id = ?::uuid",
                    id)).containsExactly(String.valueOf(stored.codePointCount(0, stored.length())));
        }
        // an admission saved with no reason keeps the default text
        String blank = admitWithReason(clinic, "   ").get("id").asText();
        signNormal(clinic, blank);
        dischargeNow(clinic, blank, "normal", "general", "UPI");
        assertThat(values(clinic, "select reason from opd.follow_up where source_admission_id = ?::uuid", blank))
                .containsExactly(prefix + "Under evaluation");
    }

    @Test
    void deathCreatesNoFollowUpAndStoresNoFollowUpDateAndLamaAndReferredDo() throws Exception {
        Clinic clinic = hospital();
        String death = quickAdmit(clinic).get("id").asText();
        sign(clinic, death, "death", "general");
        JsonNode d = dischargeNow(clinic, death, "death", "general", "UPI");
        assertThat(d.get("discharge").get("type").asText()).isEqualTo("death");
        assertThat(d.get("discharge").get("followUpDate").isNull()).isTrue();
        assertThat(d.get("discharge").get("followUpDays").asInt()).as("the setup value is kept").isEqualTo(7);
        assertThat(values(clinic, "select coalesce(followup_due::text, 'null') from ipd.discharge where admission_id = ?::uuid",
                death)).containsExactly("null");
        assertThat(count(clinic, "select count(*) from opd.follow_up where source_admission_id = ?::uuid", UUID.fromString(death)))
                .isZero();
        assertThat(audits(clinic, "FOLLOW_UP_CREATED")).isZero();
        assertThat(audits(clinic, "IPD_DISCHARGED")).isOne();
        assertThat(followUps(clinic, "UPCOMING").get("items")).isEmpty();

        for (String type : new String[] {"lama", "referred", "normal"}) {
            String id = quickAdmit(clinic).get("id").asText();
            sign(clinic, id, type, "general");
            JsonNode e = dischargeNow(clinic, id, type, "general", "UPI");
            assertThat(e.get("discharge").get("followUpDate").isNull()).as(type).isFalse();
            assertThat(count(clinic, "select count(*) from opd.follow_up where source_admission_id = ?::uuid",
                    UUID.fromString(id))).as(type).isOne();
        }
        assertThat(audits(clinic, "FOLLOW_UP_CREATED")).isEqualTo(3);
    }

    // ---------------- the default template ----------------

    @Test
    void theSheetStartsWithTheSetupTemplateOtherwiseMaternityDaycareOrGeneral() throws Exception {
        Clinic clinic = hospital();
        String planned = quickAdmit(clinic).get("id").asText();
        String maternity = admitOfType(clinic, "maternity", 1_500_000L).get("id").asText();
        String daycare = admitOfType(clinic, "daycare", 300_000L).get("id").asText();
        assertThat(sheet(clinic, planned).get("defaultTemplateKey").asText()).isEqualTo("general");
        assertThat(sheet(clinic, maternity).get("defaultTemplateKey").asText()).isEqualTo("maternity");
        assertThat(sheet(clinic, daycare).get("defaultTemplateKey").asText()).isEqualTo("daycare");
        assertThat(sheet(clinic, planned).get("defaultType").asText()).isEqualTo("normal");

        putSettings(clinic, "surgical", true, 7);
        for (String id : new String[] {planned, maternity, daycare}) {
            assertThat(sheet(clinic, id).get("defaultTemplateKey").asText()).as("setup template wins").isEqualTo("surgical");
        }
        putSettings(clinic, null, true, 7);
        assertThat(sheet(clinic, maternity).get("defaultTemplateKey").asText()).isEqualTo("maternity");
    }
}
