package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.platform.support.LogCapture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The IPD go-live journey (docs/reviews/GO-LIVE-ipd-test-gaps.md section 3.1, plan docs/test-plans/IPD-go-live.md): ONE admission
 * of a hospital with two wards and ward scoping on, taken from setup to discharge through the real endpoints on the real
 * {@code sahaj_test} database, with a second clinic probing row level security after every step, the application log captured for
 * the whole run, and one audit row checked per write. SQL is used only to read state back and to move a time stamp back to the
 * fixed clock of the journey (order version and head together, {@link IpdJourneySupport}).
 *
 * <p>The clock. Scheduled doses need a known time of day, so the dose, order and board reads use the clock seam of
 * {@link MarSupport}, fixed on a day two days back (8:05 AM, later 8:10 PM), and every time the database stamps with its own clock
 * is moved to that clock right after the write; nothing here depends on the time of day the test runs. STAT, PRN, vitals,
 * escalation, break-glass and money steps run on the real clock (no slot).
 *
 * <p>The steps are run one after the other; a failed step is reported (with its line) and the journey goes on, so one run shows
 * every defect that stands in the way, and the test fails at the end. The numbers are those of section 3.1.
 *
 * <p>Left out on purpose, until contract 0.4.3 is approved: the PRN start and end window (DEC-057 item 3).
 */
@OnTestDatabase
@WithPinPepper
class IpdJourneyIT extends IpdJourneySupport {

    private static final String PLAN = "Oxygen up to 6 L, repeat vitals in 30 minutes " + MARK;
    private static final long NURSING_PAISE = 40_000L;
    /** The extra services are priced at the base price times the multiplier of the bed category (Semi-private 1.25). */
    private static final long XRAY = 56_250L;
    private static final long DRESSING_PAISE = 50_000L;
    private static final long FIRST_DEPOSIT = 1_200_000L;

    private Jy j;
    private Ward w;

    private static L nursingJ(int days) {
        return new L("Nursing care", NURSING_PAISE, days, false);
    }

    private static String wrongOf(String pin) {
        return "9306".equals(pin) ? "9307" : "9306";
    }

    private String statusOf(String orderId) throws Exception {
        return values(j.clinic, "select status from ipd.medicine_order where id = ?::uuid", orderId).get(0);
    }

    private long dosesOfAdmission() throws Exception {
        return doseRows(j.clinic, j.adm.id());
    }

    private MarReader nurseMar() {
        return () -> mar(j.nurseA.auth(), j.adm.id());
    }

    @Test
    void anAdmissionIsTakenFromSetupToDischargeAndEveryStepHoldsItsRules() throws Exception {
        // the fixed clock is on the real IST day (the database refuses "recorded in error" for another day), see IpdJourneySupport.waitOutTheIstMidnight
        try (LogCapture logs = new LogCapture()) {
            step("1 setup", this::step1Setup);
            step("2 admit and deposit", this::step2AdmitAndDeposit);
            after("2");
            step("3 orders", this::step3Orders);
            after("3");
            step("4 pharmacist", this::step4Pharmacist);
            after("4");
            step("5 give", this::step5Give);
            after("5");
            step("6 correct (undo, attempt 2, recorded in error, not given)", this::step6Correct);
            after("6");
            step("7 hold, resume, change", this::step7HoldResumeChange);
            after("7");
            step("8 vitals", this::step8Vitals);
            after("8");
            step("9 safety", this::step9Safety);
            after("9");
            step("10 break-glass", this::step10BreakGlass);
            after("10");
            step("11 transfer", this::step11Transfer);
            after("11");
            step("12 charge, deposits, totals", this::step12Money);
            after("12");
            step("13 discharge", this::step13Discharge);
            after("13");
            step("14 after discharge", this::step14AfterDischarge);
            after("14");
            step("15 privacy and audit of the whole journey", () -> step15Privacy(logs.allText()));
        }
        failIfAny();
    }

    /** Step 15 at every step: the second clinic sees nothing. */
    private void after(String step) {
        step("15 second clinic sees nothing after step " + step, () -> otherClinicSeesNothing(j, step));
    }

    // ================= 1 setup =================

    private void step1Setup() throws Exception {
        j = people("Penicillin " + MARK + " rash");
        Clinic c = j.clinic;
        w = null;
        // two wards, a nurse in each, then the clinic switch
        assertThat(j.wardA).isNotEqualTo(j.wardB);
        assign(c, j.wardA, j.nurseA, j.nurseA2);
        assign(c, j.wardB, j.nurseB);
        // rates: the bed day is cut after 24 hours (so the days of the stay never depend on the hour the test runs), nursing 400, visit 500
        setCharges(c, "24h", NURSING_PAISE, 50_000L);
        setCategory(c, "semi", 150_000L, "1.25");
        // minimum deposit of a planned admission: Rs 12,000
        JsonNode rules = ok(HttpMethod.PUT, RULES, c.owner(), "{\"depositPaise\":{\"planned\":1200000,\"emergency\":500000,"
                + "\"daycare\":300000,\"maternity\":1500000},\"categoryAddPaise\":{\"gen\":0,\"semi\":200000,\"pvt\":500000,"
                + "\"icu\":1000000},\"emergencyWithoutDeposit\":true,\"alertPercent\":80}", 200);
        assertThat(rules.toString()).contains("1200000");
        // the pharmacist checks every order before the first dose
        ok(HttpMethod.PUT, SETTINGS, c.owner(), "{\"medVerificationRequired\":true,\"doseWindowBeforeMinutes\":60,"
                + "\"doseWindowAfterMinutes\":60,\"statWindowMinutes\":15,\"unackFlagMinutes\":30,\"unackFlagStatMinutes\":5,"
                + "\"undoSeconds\":10}", 200);
        assertThat(ok(HttpMethod.GET, SETTINGS, c.owner(), null, 200).toString()).contains("\"medVerificationRequired\":true");
        // nurses see only their own wards
        assertThat(switchScope(c, true)).isNotNull();
        assertThat(ok(HttpMethod.GET, ADMISSIONS, j.nurseA.auth(), null, 200).get("scope").get("kind").asText()).isNotEqualTo("ALL");
        // a nurse cannot change the setup
        refused(call(HttpMethod.PUT, SETTINGS, j.nurseA.auth(), "{\"medVerificationRequired\":false}"), 403, "forbidden", ONLY_OWNER_SETTINGS);
    }

    // ================= 2 admit and deposit =================

    private void step2AdmitAndDeposit() throws Exception {
        Clinic c = j.clinic;
        String name = "Meera Kulkarni " + uniqueMobile().substring(5);
        String mobile = uniqueMobile();
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("name", name);
        patient.put("age", 56);
        patient.put("sex", "F");
        patient.put("mobile", mobile);
        patient.put("area", "Shivaji Nagar");
        patient.put("language", "mr");
        patient.put("allergy", j.allergy);
        UUID patientId = UUID.fromString(ok(HttpMethod.POST, PATIENTS, c.owner(), js(patient), 201).get("id").asText());
        j.bedA = bedIn(c, j.wardA);

        Map<String, Object> low = body(patientId, c.doctorMembershipId(), j.bedA);
        low.put("depositPaise", 1_100_000);
        JsonNode below = problem(call(HttpMethod.POST, ADMISSIONS, c.owner(), js(low)), 400);
        assertThat(below.get("detail").asText())
                .isEqualTo("The deposit is below the minimum of ₹12,000. Collect at least that, or choose Insurance or Corporate.");
        assertThat(rows(c, "admission")).as("a refused admission writes nothing").isZero();

        Map<String, Object> good = body(patientId, c.doctorMembershipId(), j.bedA);
        good.put("depositPaise", FIRST_DEPOSIT);
        JsonNode a = audited(c, "IPD_ADMITTED", 1, () -> admitOk(c, good));
        String id = a.get("id").asText();
        j.adm = new Adm(id, patientId, name, mobile, a.get("bedCode").asText());
        j.uhid = values(c, "select uhid from patient.patient where id = ?::uuid", patientId).get(0);
        w = wardOf(j);
        // three bill days, so that a bed move splits the bill
        backdateToBillDays(c, id, 3, "24h");

        assertThat(bedStatus(c, j.bedA)).isEqualTo("occ");
        JsonNode d = detail(c, id);
        assertThat(d.get("depositPaise").asLong()).isEqualTo(FIRST_DEPOSIT);
        assertThat(d.get("deposits")).hasSize(1);
        assertThat(d.get("depositState").asText()).isEqualTo("OK");
        assertBill(d.get("bill"), current("gen", 3), nursingJ(3));
        assertThat(d.get("bill").get("billDays").asInt()).isEqualTo(3);
        assertThat(d.get("runningBillPaise").asLong()).isEqualTo(3 * (80_000 + 50_000) + 3 * NURSING_PAISE);
        // the board and the list show the same one bill
        assertThat(row(list(c), id).get("runningBillPaise").asLong()).isEqualTo(d.get("runningBillPaise").asLong());

        // the nurse of ward A reads it, the nurse of ward B does not, and the other clinic gets 404 (checked after every step)
        ok(HttpMethod.GET, ADMISSIONS + "/" + id, j.nurseA.auth(), null, 200);
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + id, j.nurseB.auth(), null));
        outside(call(HttpMethod.GET, marUrl(id), j.nurseB.auth(), null));
        assertThat(idsOf(listOf(j.nurseA.auth()))).contains(id);
        assertThat(idsOf(listOf(j.nurseB.auth()))).doesNotContain(id);
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + id, j.other.owner(), null), 404);
    }

    // ================= 3 orders =================

    private void step3Orders() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        j.hep = audited(c, "IPD_MED_ORDERED", 1, () -> placeOrder(j.doctor.auth(), adm, sched("hep", "bd", "IU", "SC")));
        Map<String, Object> prn = prnOrder("pcm", "TABLET", "ORAL", 360, 4);
        prn.put("indication", "Fever above 38 C " + MARK);
        j.pcm = audited(c, "IPD_MED_ORDERED", 1, () -> placeOrder(j.doctor.auth(), adm, prn));
        j.panto = audited(c, "IPD_MED_ORDERED", 1, () -> placeOrder(j.doctor.auth(), adm, sched("panto", "bd", "MG", "IV")));
        j.stat = audited(c, "IPD_MED_ORDERED", 1, () -> placeOrder(j.doctor.auth(), adm, statOrder("ondan", "MG", "IV")));
        j.orderIds.addAll(List.of(j.hep, j.pcm, j.panto, j.stat));
        for (String id : j.orderIds) {
            assertThat(statusOf(id)).as("a new order waits for the pharmacist").isEqualTo("PENDING");
            assertThat(values(c, "select current_version::text from ipd.medicine_order where id = ?::uuid", id)).containsExactly("1");
        }
        assertThat(values(c, "select kind || '/' || priority from ipd.medicine_order_version where order_id = ?::uuid", j.stat))
                .containsExactly("ONCE/STAT");
        assertThat(values(c, "select kind from ipd.medicine_order_version where order_id = ?::uuid", j.pcm)).containsExactly("PRN");
        // a STAT order tells the nurses at once; a routine one only after the pharmacist
        assertThat(count(c, "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid and kind = 'NEW'", j.stat)).isOne();
        assertThat(count(c, "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid", j.hep)).isZero();

        // hard stops: the same medicine twice, and the documented allergy
        long orders = orderCount(c, adm);
        JsonNode dup = audited(c, "IPD_MED_ORDER_BLOCKED", 1,
                () -> ok(HttpMethod.POST, ordersUrl(adm), j.doctor.auth(), js(orderBody(newKey(), "hep", "od", null, null)), 200));
        assertThat(dup.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(dup.get("check").get("hardStops").get(0).get("code").asText()).isEqualTo("DUPLICATE");
        JsonNode allergy = audited(c, "IPD_MED_ORDER_BLOCKED", 1,
                () -> ok(HttpMethod.POST, ordersUrl(adm), j.doctor.auth(), js(orderBody(newKey(), "amox", "bd", null, null)), 200));
        assertThat(allergy.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(allergy.get("check").get("hardStops").get(0).get("code").asText()).isEqualTo("ALLERGY_PENICILLIN");
        assertThat(orderCount(c, adm)).as("a hard stop places nothing").isEqualTo(orders);

        // a nurse, the pharmacist and a nurse of another ward cannot order
        for (Pn who : new Pn[] {j.nurseA, j.pharmacist, j.nurseB}) {
            refused(orderCall(who.auth(), adm, "ceft"), 403, "forbidden", ONLY_DOCTOR);
        }
        assertThat(orderCount(c, adm)).isEqualTo(orders);
        // a body with every field wrong is 400 with the problem details, nothing saved
        JsonNode bad = problem(call(HttpMethod.POST, ordersUrl(adm), j.doctor.auth(), "{}"), 400);
        assertThat(bad.get("fieldErrors")).isNotEmpty();
        assertThat(orderCount(c, adm)).isEqualTo(orders);
    }

    // ================= 4 pharmacist =================

    private void step4Pharmacist() throws Exception {
        Clinic c = j.clinic;
        Pn ph = j.pharmacist;
        String adm = j.adm.id();
        JsonNode q = queue(ph.auth(), "limit=100");
        for (String id : j.orderIds) {
            JsonNode r = queueRow(q, id);
            assertThat(r).as("order " + id + " waits in the pharmacist queue").isNotNull();
            assertThat(r.get("orderVersion").asInt()).isEqualTo(1);
        }
        // a wrong PIN once: refused with the tries left, nothing verified
        JsonNode wrong = problem(verifyWith(ph, j.hep, wrongOf(j.pharmacistPin), 1), 403);
        assertType(wrong, "wrong-pin");
        assertThat(wrong.get("detail").asText()).isEqualTo("That PIN is not right. 4 tries left.");
        assertThat(statusOf(j.hep)).isEqualTo("PENDING");

        // the nurse gives the STAT dose before the pharmacist check: one review row
        realClock();
        Instant statSlot = slotOf(mar(j.nurseA.auth(), adm), j.stat, 0);
        JsonNode given = audited(c, "IPD_DOSE_GIVEN", 1, () -> doseOk(j.nurseA.auth(), j.stat, given(w, statSlot)));
        assertThat(given.get("dose").get("beforeVerification").asBoolean()).isTrue();
        JsonNode reviews = ok(HttpMethod.GET, REVIEWS, ph.auth(), null, 200);
        JsonNode review = null;
        for (JsonNode r : reviews.get("items")) {
            if (j.stat.equals(r.get("orderId").asText())) {
                review = r;
            }
        }
        assertThat(review).as("the STAT dose given before the check is listed for review").isNotNull();
        assertThat(review.get("canMarkReviewed").asBoolean()).isTrue();

        // verify with the order version, then the same pharmacist again (200 replay), then a second verifier (409)
        JsonNode v = audited(c, "IPD_MED_VERIFIED", 1, () -> verifyVersionOk(ph, j.pharmacistPin, j.hep, 1));
        assertThat(v.get("result").get("outcome").asText()).isEqualTo("VERIFIED");
        assertThat(v.get("result").get("replayed").asBoolean()).isFalse();
        assertThat(statusOf(j.hep)).isEqualTo("ACTIVE");
        long audits = allAudits(c);
        JsonNode again = ok(HttpMethod.POST, verifyUrl(j.hep), ph.auth(), verifyJson(j.pharmacistPin, 1), 200, ph);
        assertThat(again.get("result").get("replayed").asBoolean()).isTrue();
        assertThat(allAudits(c)).as("a replayed verify writes no audit row").isEqualTo(audits);
        JsonNode second = problem(verifyDummy(j.owner, j.hep, j.ownerPin, "AAAAAAAAAAAAAAAAAAAAAA"), 409);
        assertType(second, "order-state");
        assertThat(second.get("detail").asText()).isEqualTo(displayName(ph) + " already verified this order.");
        // the orderer cannot verify his own order; a nurse cannot verify
        refused(call(HttpMethod.POST, verifyUrl(j.pcm), j.nurseA.auth(), verifyJson(j.pharmacistPin, 1)), 403, "forbidden", ONLY_PHARMACIST);

        for (String id : List.of(j.pcm, j.panto, j.stat)) {
            verifyVersionOk(ph, j.pharmacistPin, id, 1);
            assertThat(statusOf(id)).isEqualTo("ACTIVE");
        }
        assertThat(values(c, "select outcome from ipd.pharmacist_review where order_id = ?::uuid", j.stat))
                .as("verifying the STAT order closes its review row").containsExactly("VERIFIED");
        assertThat(ok(HttpMethod.GET, REVIEWS, ph.auth(), null, 200).get("open").asInt()).isZero();
        // a notice NEW per routine order, written at the verify; the STAT one was acknowledged by its dose
        assertThat(count(c, "select count(*) from ipd.order_notice where medicine_order_id in (?::uuid, ?::uuid, ?::uuid) and kind = 'NEW' "
                + "and acknowledged_at is null", j.hep, j.pcm, j.panto)).isEqualTo(3);
        // the scheduled orders were placed and verified "yesterday" of the fixed clock, so that today's slots do not depend on the hour
        for (String id : List.of(j.hep, j.panto)) {
            backdate(c, id, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0));
        }
    }

    private String verifyJson(String pin, int version) {
        return "{\"pin\":\"" + pin + "\",\"checkToken\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"orderVersion\":" + version + "}";
    }

    private org.springframework.test.web.servlet.ResultActions verifyDummy(Pn who, String orderId, String pin, String token) throws Exception {
        freshPinAllowance(who);
        return call(HttpMethod.POST, verifyUrl(orderId), who.auth(),
                "{\"pin\":\"" + pin + "\",\"checkToken\":\"" + token + "\",\"orderVersion\":1}");
    }

    private JsonNode ok(HttpMethod m, String url, org.springframework.test.web.servlet.request.RequestPostProcessor auth, String body,
                        int status, Pn pinOwner) throws Exception {
        freshPinAllowance(pinOwner);
        return ok(m, url, auth, body, status);
    }

    // ================= 5 give =================

    private void step5Give() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        clockAt(8, 5);
        JsonNode before = mar(j.nurseA.auth(), adm);
        assertThat(states(before, j.hep)).containsExactly("DUE", "LATER");
        assertThat(states(before, j.panto)).containsExactly("DUE", "LATER");
        assertThat(before.get("counters").get("dueNow").asInt()).isEqualTo(2);
        assertThat(marOrder(before, j.hep).get("highAlert").asText()).isEqualTo("Anticoagulant");

        // a wrong wristband: stopped, no dose, a deny entry and an audit row
        DSnap snap = dsnap(w);
        long denies = denyEvents(c, adm); // the two blocked orders of step 3 left a deny entry each
        refused(doseCall(j.nurseA.auth(), j.hep, given(ist(8, 0), "PT-00000")), 409, "wristband-mismatch", MISMATCH_TEXT);
        assertThat(doseRowsOfOrder(c, j.hep)).isZero();
        assertThat(dsnap(w).doses()).isEqualTo(snap.doses());
        assertThat(denyEvents(c, adm)).isEqualTo(denies + 1);
        assertThat(audit(c, "IPD_DOSE_WRISTBAND_MISMATCH")).hasSize(1);
        // a high-alert medicine without its second nurse is refused before anything is written
        invalid(doseCall(j.nurseA.auth(), j.hep, given(w, ist(8, 0))), "secondNurseId", HIGH_ALERT_TEXT);
        assertThat(doseRowsOfOrder(c, j.hep)).isZero();

        // the dose with the second nurse's PIN
        Map<String, Object> body = givenHighAlert(w, ist(8, 0), j.nurseA2, j.nurseA2Pin);
        freshPinAllowance(j.nurseA);
        JsonNode a = audited(c, "IPD_DOSE_GIVEN", 1, () -> doseOk(j.nurseA.auth(), j.hep, body));
        String firstDose = a.get("dose").get("id").asText();
        assertThat(a.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(a.get("dose").get("secondCheckByName").asText()).isEqualTo(displayName(j.nurseA2));
        assertThat(states(a.get("mar"), j.hep)).containsExactly("GIVEN", "LATER");
        assertThat(eventLines(c, adm).get(0)).contains("second check by " + displayName(j.nurseA2));
        assertThat(values(c, "select acknowledged_via from ipd.order_notice where medicine_order_id = ?::uuid and kind = 'NEW'", j.hep))
                .containsExactly("DOSE");
        // a replay of the same request (a double tap): 200, nothing written, the PIN is not checked again
        DSnap afterGive = dsnap(w);
        JsonNode replay = ok(HttpMethod.POST, dosesUrl(j.hep), j.nurseA.auth(), js(body), 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("dose").get("id").asText()).isEqualTo(firstDose);
        DSnap afterReplay = dsnap(w);
        assertThat(afterReplay.doses()).isEqualTo(afterGive.doses());
        assertThat(afterReplay.audits()).isEqualTo(afterGive.audits());
        assertThat(afterReplay.pins()).isEqualTo(afterGive.pins());

        // two nurses record the same slot at the same moment: one 201, one 409 dose-recorded, no server error
        List<Resp> race = parallel(
                new Req(HttpMethod.POST, dosesUrl(j.panto), j.nurseA.auth(), js(given(w, ist(8, 0)))),
                new Req(HttpMethod.POST, dosesUrl(j.panto), j.nurseA2.auth(), js(given(w, ist(8, 0)))));
        List<Integer> codes = new ArrayList<>();
        race.forEach(r -> codes.add(r.status()));
        assertThat(codes).as("one dose is recorded, the other nurse is told").containsExactlyInAnyOrder(201, 409);
        for (Resp r : race) {
            if (r.status() == 409) {
                assertThat(r.type(objectMapper)).isEqualTo("dose-recorded");
                assertThat(r.detail(objectMapper)).isEqualTo(DOSE_RECORDED);
            }
        }
        assertThat(doseRowsOfOrder(c, j.panto)).isOne();
        assertThat(audit(c, "IPD_DOSE_GIVEN")).as("the stat dose, the heparin dose and one panto dose").hasSize(3);

        // a PRN dose (real clock: it has no slot), then too soon
        realClock();
        Map<String, Object> prn = without(given(w, Instant.now()), "slotAt");
        JsonNode p = audited(c, "IPD_DOSE_GIVEN", 1, () -> doseOk(j.nurseA.auth(), j.pcm, prn));
        assertThat(p.get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        Map<String, Object> soon = without(given(w, Instant.now()), "slotAt");
        JsonNode tooSoon = audited(c, "IPD_DOSE_REFUSED", 1, () -> problem(doseCall(j.nurseA.auth(), j.pcm, soon), 409));
        assertType(tooSoon, "prn-too-soon");
        assertThat(tooSoon.get("detail").asText()).startsWith("Too soon. Next dose allowed at ");
        assertThat(doseRowsOfOrder(c, j.pcm)).isOne();
        JsonNode prnLine = marOrder(mar(j.nurseA.auth(), adm), j.pcm).get("prn");
        assertThat(prnLine.get("lockReason").asText()).isEqualTo("TOO_SOON");
        assertThat(prnLine.get("givenLast24h").asInt()).isOne();
        // the PRN start and end window: not asserted until contract 0.4.3 (DEC-057 item 3) is approved

        // a nurse of ward B cannot give to the patient of ward A (scope), and nothing is written
        clockAt(8, 5);
        String written = written(c);
        outside(doseCall(j.nurseB.auth(), j.panto, given(w, ist(20, 0))));
        assertThat(written(c)).isEqualTo(written);
    }

    // ================= 6 correct a dose =================

    private void step6Correct() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        clockAt(8, 5);
        List<Dose> rows = dosesOf(c, j.hep);
        assertThat(rows).hasSize(1);
        String dose1 = rows.get(0).id();
        assertEmrEqualsMar(w, nurseMar(), j.hep, "after the dose");

        // undo inside the window (the window is moved to the fixed clock: it is stamped by the real one)
        extendUndo(c, dose1); // the database guard reads the real clock: the window stays open for an hour
        JsonNode marNow = mar(j.nurseA.auth(), adm);
        assertThat(slot(marNow, j.hep, 0).get("dose").get("canUndo").asBoolean()).isTrue();
        assertThat(slot(marNow, j.hep, 0).get("dose").get("undoSecondsLeft").asInt()).isPositive();
        JsonNode undone = audited(c, "IPD_DOSE_UNDONE", 1, () -> undoOk(j.nurseA.auth(), dose1));
        assertThat(slot(undone.get("mar"), j.hep, 0).get("state").asText()).as("the slot is due again").isEqualTo("DUE");
        assertThat(slot(undone.get("mar"), j.hep, 0).get("dose").isNull()).isTrue();
        assertThat(correctionRows(c, dose1)).isOne();
        assertEmrEqualsMar(w, nurseMar(), j.hep, "after undo");

        // attempt 2: a new record linked to the undone one
        freshPinAllowance(j.nurseA);
        JsonNode second = audited(c, "IPD_DOSE_GIVEN", 1,
                () -> doseOk(j.nurseA.auth(), j.hep, givenHighAlert(w, ist(8, 0), j.nurseA2, j.nurseA2Pin)));
        String dose2 = second.get("dose").get("id").asText();
        rows = dosesOf(c, j.hep);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).attempt()).isEqualTo(2);
        assertThat(rows.get(1).supersedes()).isEqualTo(dose1);
        assertEmrEqualsMar(w, nurseMar(), j.hep, "after attempt 2");

        // recorded in error by the other nurse of the ward: the original is kept, struck through, the slot is due again
        JsonNode inError = audited(c, "IPD_DOSE_RECORDED_IN_ERROR", 1, () -> errorOk(j.nurseA2.auth(), dose2, "WRONG_TIME"));
        JsonNode slotNow = slot(inError.get("mar"), j.hep, 0);
        assertThat(slotNow.get("state").asText()).isEqualTo("DUE");
        assertThat(slotNow.get("history")).hasSize(1);
        assertThat(slotNow.get("history").get(0).get("correction").get("kind").asText()).isEqualTo("IN_ERROR");
        assertThat(dosesOf(c, j.hep)).as("nothing is deleted").hasSize(2);
        assertEmrEqualsMar(w, nurseMar(), j.hep, "after recorded in error");

        // attempt 3 stands; the dose counts once
        freshPinAllowance(j.nurseA);
        JsonNode third = doseOk(j.nurseA.auth(), j.hep, givenHighAlert(w, ist(8, 0), j.nurseA2, j.nurseA2Pin));
        rows = dosesOf(c, j.hep);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2).attempt()).isEqualTo(3);
        assertThat(rows.get(2).supersedes()).isEqualTo(dose2);
        assertThat(states(third.get("mar"), j.hep).get(0)).isEqualTo("GIVEN");
        assertThat(count(c, "select count(*) from ipd.dose_administration d where d.order_id = ?::uuid and d.outcome = 'GIVEN' "
                + "and not exists (select 1 from ipd.dose_correction x where x.dose_id = d.id)", j.hep)).as("one live dose").isOne();
        assertEmrEqualsMar(w, nurseMar(), j.hep, "after attempt 3");

        // the evening dose is not given: the patient refused (the clock is 8:05 PM now)
        clockAt(20, 5);
        JsonNode refused = audited(c, "IPD_DOSE_NOT_GIVEN", 1, () -> doseOk(j.nurseA.auth(), j.hep, notGiven(ist(20, 0), "PATIENT_REFUSED")));
        assertThat(slot(refused.get("mar"), j.hep, 1).get("state").asText()).isEqualTo("REFUSED");
        assertEmrEqualsMar(w, nurseMar(), j.hep, "after not given");
        assertThat(executionOf(w, j.hep).get("notGivenToday").asInt()).isOne();
        assertThat(eventLines(c, adm)).anyMatch(l -> l.startsWith("give|Did not give Inj. Heparin 5,000 IU (8 PM dose) · Patient refused"));
    }

    // ================= 7 hold, resume, change =================

    private void step7HoldResumeChange() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        clockAt(20, 5);
        // hold: the nurse is told, the dose is refused with nothing written
        audited(c, "IPD_MED_HELD", 1, () -> holdOk(j.doctor, j.panto, 1, "Nil by mouth for the scan " + MARK));
        assertThat(statusOf(j.panto)).isEqualTo("ON_HOLD");
        assertThat(count(c, "select count(*) from ipd.order_notice where medicine_order_id = ?::uuid and kind = 'HELD'", j.panto)).isOne();
        long doses = doseRowsOfOrder(c, j.panto);
        long gives = giveEvents(c, adm);
        JsonNode onHold = audited(c, "IPD_DOSE_REFUSED", 1, () -> problem(doseCall(j.nurseA.auth(), j.panto, given(w, ist(20, 0))), 409));
        assertType(onHold, "order-on-hold");
        assertThat(onHold.get("detail").asText()).isEqualTo(ORDER_ON_HOLD);
        assertThat(doseRowsOfOrder(c, j.panto)).isEqualTo(doses);
        assertThat(giveEvents(c, adm)).as("a refused dose leaves no give entry").isEqualTo(gives);
        // resume (the hold is moved to the fixed clock: before the 8 PM dose), then the dose can be given
        audited(c, "IPD_MED_RESUMED", 1, () -> resumeOk(j.doctor, j.panto, 1));
        assertThat(statusOf(j.panto)).isEqualTo("ACTIVE");
        stampHold(c, j.panto, ist(19, 55), ist(19, 58));
        assertThat(slot(mar(j.nurseA.auth(), adm), j.panto, 1).get("state").asText()).isEqualTo("DUE");
        JsonNode after = audited(c, "IPD_DOSE_GIVEN", 1, () -> doseOk(j.nurseA.auth(), j.panto, given(w, ist(20, 0))));
        assertThat(slot(after.get("mar"), j.panto, 1).get("state").asText()).isEqualTo("GIVEN");
        assertEmrEqualsMar(w, nurseMar(), j.panto, "after hold, resume and a dose");

        // change: version 2 goes back to the pharmacist; the old version is refused; the new one waits for the check
        JsonNode sheet = audited(c, "IPD_MED_CHANGED", 1,
                () -> changeOk(j.doctor, j.panto, changeBodyFor(1, "Dose raised after the review " + MARK, 2, "MG", "IV", "bd")));
        assertThat(sheetRow(sheet, j.panto).get("orderVersion").asInt()).isEqualTo(2);
        assertThat(statusOf(j.panto)).isEqualTo("PENDING");
        assertThat(versionsOf(j.panto)).isEqualTo(2);
        refused(doseCall(j.nurseA.auth(), j.panto, versioned(given(w, ist(20, 0)), 2)), 409, "order-not-verified", NOT_VERIFIED);
        verifyVersionOk(j.pharmacist, j.pharmacistPin, j.panto, 2);
        assertThat(statusOf(j.panto)).isEqualTo("ACTIVE");
        // the database stamped the change and the verify with the real clock: move them to the fixed one
        stampVersion(c, j.panto, 1, istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 6, 0), istOn(day.minusDays(1), 7, 0), ist(20, 5));
        stampVersion(c, j.panto, 2, ist(20, 5), ist(20, 5), ist(20, 5), null);
        JsonNode stale = audited(c, "IPD_DOSE_REFUSED", 1,
                () -> problem(doseCall(j.nurseA.auth(), j.panto, given(w, ist(20, 0))), 409));
        assertType(stale, "order-changed");
        assertThat(stale.get("detail").asText()).isEqualTo(ORDER_CHANGED);

        // the CHANGED notice: acknowledged once, repeated quietly, a second nurse is told who did it
        JsonNode notices = ok(HttpMethod.GET, Sah76Support.noticesUrl(adm), j.nurseA.auth(), null, 200);
        JsonNode changed = null;
        for (JsonNode n : notices.get("items")) {
            if ("CHANGED".equals(n.get("kind").asText()) && n.get("acknowledgedAt").isNull()) {
                changed = n;
            }
        }
        assertThat(changed).as("an open CHANGED notice").isNotNull();
        assertThat(changed.get("changes").toString()).contains("Dose");
        String noticeId = changed.get("id").asText();
        audited(c, "IPD_ORDER_NOTICE_ACKNOWLEDGED", 1, () -> ok(HttpMethod.POST, Sah76Support.noticeAckUrl(noticeId), j.nurseA.auth(), null, 200));
        long audits = allAudits(c);
        ok(HttpMethod.POST, Sah76Support.noticeAckUrl(noticeId), j.nurseA.auth(), null, 200);
        assertThat(allAudits(c)).as("a repeated acknowledge writes nothing").isEqualTo(audits);
        JsonNode other = problem(call(HttpMethod.POST, Sah76Support.noticeAckUrl(noticeId), j.nurseA2.auth(), null), 409);
        assertType(other, "order-state");
        assertThat(other.get("detail").asText()).isEqualTo(displayName(j.nurseA) + " already acknowledged this.");
        assertThat(count(c, "select count(*) from ipd.order_notice where id = ?::uuid and acknowledged_at is not null", noticeId)).isOne();
        // the MAR shows the replaced evening dose of version 1 as given, and the EMR line equals the MAR
        assertEmrEqualsMar(w, nurseMar(), j.panto, "after the change");
        assertEmrEqualsMar(w, nurseMar(), j.hep, "heparin after the evening");
    }

    private long versionsOf(String orderId) throws Exception {
        return count(j.clinic, "select count(*) from ipd.medicine_order_version where order_id = ?::uuid", orderId);
    }

    // ================= 8 vitals =================

    private void step8Vitals() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        realClock();
        audited(c, "IPD_VITALS_RECORDED", 1, () -> ok(HttpMethod.POST, vitalsUrl(adm), j.nurseA.auth(), js(low()), 201));
        assertThat(escalationCount(c, adm)).as("a Low score raises nothing").isZero();
        audited(c, "IPD_VITALS_RECORDED", 1, () -> ok(HttpMethod.POST, vitalsUrl(adm), j.nurseA.auth(), js(medium3()), 201));
        j.escalationId = openEscalationId(c, adm);
        assertThat(j.escalationId).isNotNull();
        assertThat(openEscalation(c, adm)).isEqualTo("3|MEDIUM|60|OPEN|0");
        // a nurse of the other ward cannot save vitals for this patient
        String written = written(c);
        outside(call(HttpMethod.POST, vitalsUrl(adm), j.nurseB.auth(), js(low())));
        assertThat(written(c)).isEqualTo(written);
        // a body that is not five vitals is 400 with the problem details and writes nothing
        JsonNode bad = problem(call(HttpMethod.POST, vitalsUrl(adm), j.nurseA.auth(), "{\"clientKey\":\"" + UUID.randomUUID() + "\"}"), 400);
        assertThat(bad.get("fieldErrors")).isNotEmpty();
        assertThat(written(c)).isEqualTo(written);
    }

    // ================= 9 safety =================

    private void step9Safety() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        String esc = j.escalationId;
        // the doctor acknowledges; a High reading after that reopens it
        JsonNode acked = audited(c, "IPD_ESCALATION_ACKNOWLEDGED", 1, () -> ackOk(j.doctor.auth(), esc, 0));
        assertThat(acked.get("escalation").get("status").asText()).isEqualTo("ACK");
        audited(c, "IPD_VITALS_RECORDED", 1, () -> ok(HttpMethod.POST, vitalsUrl(adm), j.nurseA.auth(), js(high7()), 201));
        assertThat(openEscalation(c, adm)).as("the risk rose from Medium to High: reopened, acknowledgement cleared").isEqualTo("7|HIGH|15|OPEN|2");
        assertThat(esc(c, esc).hasReopenedAt()).isTrue();
        // the nurse of the other ward is refused by scope; a nurse may not acknowledge (doctor or owner only)
        outside(callSenior(j.nurseB.auth(), esc, 2, 0));
        refused(ack(j.nurseB.auth(), esc, 2), 403, "forbidden", null);
        refused(ack(j.nurseA.auth(), esc, 2), 403, "forbidden", null);
        assertThat(esc(c, esc).seniorCalls()).isZero();
        // the nurse of the ward calls the senior doctor: the count moves, the version does not
        JsonNode called = audited(c, "IPD_ESCALATION_SENIOR_CALLED", 1, () -> callOk(j.nurseA.auth(), esc, 2, 0));
        assertThat(called.get("escalation").get("seniorCalls").asInt()).isOne();
        assertThat(called.get("escalation").get("version").asLong()).isEqualTo(2L);
        // the board lists it for the doctor and the nurse of the ward, not for the nurse of the other ward
        assertThat(rowIds(boardOf(j.doctor.auth()))).contains(esc);
        assertThat(rowIds(boardOf(j.nurseA.auth()))).contains(esc);
        assertThat(rowIds(boardOf(j.nurseB.auth()))).doesNotContain(esc);
    }

    // ================= 10 break-glass =================

    private void step10BreakGlass() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        realClock();
        // the nurse of ward B names a reason, the patient opens for 30 minutes
        JsonNode picker = ok(HttpMethod.GET, EA_PICKER, j.nurseB.auth(), null, 200);
        assertThat(picker.toString()).contains(adm);
        outside(call(HttpMethod.GET, marUrl(adm), j.nurseB.auth(), null));
        JsonNode grant = audited(c, "IPD_EMERGENCY_ACCESS_OPENED", 1, () -> openGrant(j.nurseB, adm, "DETERIORATING"));
        String grantId = grant.get("id").asText();
        ok(HttpMethod.GET, marUrl(adm), j.nurseB.auth(), null, 200);
        ok(HttpMethod.GET, ADMISSIONS + "/" + adm, j.nurseB.auth(), null, 200);
        // the owner reviews it once
        audited(c, "IPD_EMERGENCY_ACCESS_REVIEWED", 1, () -> ok(HttpMethod.POST, EA + "/" + grantId + "/review", c.owner(), null, 200));
        refused(call(HttpMethod.POST, EA + "/" + grantId + "/review", c.owner(), null), 409, "already-reviewed", ALREADY_REVIEWED_TEXT);
        // the window ends (moved back 31 minutes): the patient is outside the nurse's scope again
        ageGrant(c, grantId, 31);
        outside(call(HttpMethod.GET, marUrl(adm), j.nurseB.auth(), null));
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + adm, j.nurseB.auth(), null));
        assertThat(eventLines(c, adm)).anyMatch(l -> l.startsWith("bg|Emergency access opened"));
    }

    // ================= 11 transfer =================

    private void step11Transfer() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        clockAt(20, 10);
        setRules(c, true, "same");
        // the doctor holds the PRN order: an open notice for the nurses of the ward the patient is in
        holdOk(j.doctor, j.pcm, 1, "Hold the PRN dose for now " + MARK);
        assertThat(ok(HttpMethod.GET, Sah76Support.NOTICES_ALL, j.nurseA.auth(), null, 200).toString()).contains(adm);
        assertThat(ok(HttpMethod.GET, Sah76Support.NOTICES_ALL, j.nurseB.auth(), null, 200).toString()).doesNotContain(adm);
        JsonNode marBefore = mar(j.nurseA.auth(), adm);
        List<String> slotsBefore = slotLines(marBefore);
        assertThat(slotsBefore).isNotEmpty();
        j.bedB = bedIn(c, j.wardB);
        ok(HttpMethod.PUT, BEDS + "/" + j.bedB, c.owner(), "{\"categoryKey\":\"semi\"}", 200);
        JsonNode moved = audited(c, "IPD_BED_TRANSFER", 1, () -> moveOk(c, adm, j.bedB));
        // the bill is split at the move: General for two days, Semi-private for the day on the new bed, nursing for all three
        assertBill(moved.get("bill"), before("gen", 2), current("semi", 1), nursingJ(3));
        assertThat(bedStatus(c, j.bedA)).as("the old bed goes to cleaning").isEqualTo("clean");
        assertThat(bedStatus(c, j.bedB)).isEqualTo("occ");
        assertThat(bedOf(c, adm)).isEqualTo(j.bedB);
        // the nurse of ward A has lost the patient (reads and writes), the nurse of ward B has got it
        outside(call(HttpMethod.GET, marUrl(adm), j.nurseA.auth(), null));
        outside(call(HttpMethod.GET, Sah76Support.noticesUrl(adm), j.nurseA.auth(), null));
        String written = written(c);
        outside(call(HttpMethod.POST, vitalsUrl(adm), j.nurseA.auth(), js(low())));
        outside(doseCall(j.nurseA.auth(), j.hep, given(w, ist(8, 0))));
        assertThat(written(c)).as("a nurse who lost the patient writes nothing").isEqualTo(written);
        assertThat(idsOf(listOf(j.nurseA.auth()))).doesNotContain(adm);
        assertThat(idsOf(listOf(j.nurseB.auth()))).contains(adm);
        JsonNode marAfter = mar(j.nurseB.auth(), adm);
        assertThat(slotLines(marAfter)).as("no slot is lost or doubled by the move").isEqualTo(slotsBefore);
        JsonNode notices = ok(HttpMethod.GET, Sah76Support.noticesUrl(adm), j.nurseB.auth(), null, 200);
        assertThat(notices.get("items")).as("the nurse of the new ward reads the order notices").isNotEmpty();
        assertThat(ok(HttpMethod.GET, Sah76Support.NOTICES_ALL, j.nurseB.auth(), null, 200).toString()).contains(adm);
        assertThat(ok(HttpMethod.GET, Sah76Support.NOTICES_ALL, j.nurseA.auth(), null, 200).toString()).doesNotContain(adm);
        // the doctor's EMR line is the same before and after
        assertEmrEqualsMar(w, () -> mar(j.nurseB.auth(), adm), j.hep, "after the transfer");
    }

    // ================= 12 charges, deposits, totals =================

    private void step12Money() throws Exception {
        Clinic c = j.clinic;
        String id = j.adm.id();
        realClock();
        long base = detail(c, id).get("bill").get("totalPaise").asLong();
        assertThat(base).isEqualTo(total(before("gen", 2), current("semi", 1), nursingJ(3)));
        JsonNode withXray = audited(c, "IPD_CHARGE_ADDED", 1, () -> charge(c, id, "xray"));
        assertThat(withXray.get("bill").get("totalPaise").asLong()).isEqualTo(base + XRAY);
        assertThat(billLine(withXray.get("bill"), "Chest X-ray").get("amountPaise").asLong()).isEqualTo(XRAY);
        assertBillAddsUp(withXray.get("bill"));
        // two deposits at the same moment: both are kept, the total adds up
        List<Resp> deposits = parallel(
                new Req(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", c.owner(), "{\"amountPaise\":250000,\"mode\":\"UPI\"}"),
                new Req(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", c.as("FRONT_DESK"), "{\"amountPaise\":350000,\"mode\":\"Cash\"}"));
        for (Resp r : deposits) {
            assertThat(r.status()).as(r.body()).isEqualTo(200);
        }
        JsonNode d = detail(c, id);
        assertThat(d.get("deposits")).hasSize(3);
        assertThat(d.get("depositPaise").asLong()).isEqualTo(FIRST_DEPOSIT + 600_000L);
        assertThat(count(c, "select coalesce(sum(amount_paise), 0) from ipd.deposit where admission_id = ?::uuid", id))
                .isEqualTo(FIRST_DEPOSIT + 600_000L);
        assertThat(audit(c, "IPD_DEPOSIT_COLLECTED")).as("one audit row per deposit, the first at admission is IPD_ADMITTED").hasSize(2);
        // the running bill is the sum of its lines, and the list, the detail and the discharge sheet say the same total
        assertBillAddsUp(d.get("bill"));
        assertThat(d.get("bill").get("totalPaise").asLong()).isEqualTo(base + XRAY);
        assertThat(d.get("runningBillPaise").asLong()).isEqualTo(base + XRAY);
        assertThat(row(list(c), id).get("runningBillPaise").asLong()).isEqualTo(base + XRAY);
        // a deposit of a nurse of another ward... is allowed for the roles of the contract; a doctor's is not
        refused(call(HttpMethod.POST, ADMISSIONS + "/" + id + "/deposits", j.doctor.auth(), "{\"amountPaise\":100,\"mode\":\"Cash\"}"),
                403, "forbidden", null);
    }

    // ================= 13 discharge =================

    private void step13Discharge() throws Exception {
        Clinic c = j.clinic;
        String id = j.adm.id();
        realClock();
        // the doctor signs the summary; the open escalation still stops the discharge
        audited(c, "IPD_DISCHARGE_SIGNED", 1, () -> signNormal(c, id));
        JsonNode sheet = sheet(c, id);
        String body = dischargeBody("normal", "general", totalOf(sheet), depositsOf(sheet), "Cash");
        Written untouched = written(c, id);
        JsonNode open = problem(postDischarge(c.owner(), id, body), 409);
        assertType(open, "escalation-open");
        assertThat(open.get("detail").asText()).isEqualTo(ESCALATION_OPEN);
        assertThat(written(c, id)).as("a refused discharge writes nothing").isEqualTo(untouched);
        assertThat(values(c, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("ADMITTED");
        // the doctor writes the response; the escalation is closed
        long version = esc(c, j.escalationId).version();
        JsonNode closed = audited(c, "IPD_ESCALATION_CLOSED", 1, () -> respondOk(j.doctor.auth(), j.escalationId, version, "BEDSIDE_REVIEW", PLAN));
        assertThat(closed.get("escalation").get("status").asText()).isEqualTo("DONE");

        // the bill changes after the sheet was read: 409 bill-changed, nothing is frozen
        JsonNode oldSheet = sheet(c, id);
        String oldBody = dischargeBody("normal", "general", totalOf(oldSheet), depositsOf(oldSheet), "Cash");
        charge(c, id, "dressing");
        Written beforeChanged = written(c, id);
        JsonNode changed = problem(postDischarge(c.owner(), id, oldBody), 409);
        assertType(changed, "bill-changed");
        assertThat(changed.get("detail").asText()).isEqualTo(BILL_CHANGED);
        assertThat(written(c, id)).isEqualTo(beforeChanged);
        // a refund needs the owner
        JsonNode fresh = sheet(c, id);
        long total = totalOf(fresh);
        long deposits = depositsOf(fresh);
        assertThat(total).isEqualTo(total(before("gen", 2), current("semi", 1), nursingJ(3)) + XRAY + DRESSING_PAISE);
        assertThat(deposits).isEqualTo(FIRST_DEPOSIT + 600_000L);
        assertThat(fresh.get("bill").get("balancePaise").asLong()).isEqualTo(total - deposits);
        assertThat(total - deposits).as("this stay ends in a refund").isNegative();
        refused(postDischarge(staff(c, "FRONT_DESK").auth(), id, dischargeBody("normal", "general", total, deposits, "Cash")), 403, "refund-needs-owner",
                ONLY_OWNER_REFUNDS);
        List<String> running = lineKeys(detail(c, id).get("bill"));
        // the owner settles it
        JsonNode done = audited(c, "IPD_DISCHARGED", 1,
                () -> ok(HttpMethod.POST, dischargeUrl(id), c.owner(), dischargeBody("normal", "general", total, deposits, "Cash"), 200));
        JsonNode fb = done.get("finalBill");
        assertThat(fb.get("outcome").asText()).isEqualTo("refunded");
        assertThat(fb.get("balancePaise").asLong()).isEqualTo(total - deposits);
        assertThat(fb.get("totalPaise").asLong()).isEqualTo(total);
        assertThat(fb.get("depositsPaise").asLong()).isEqualTo(deposits);
        assertThat(lineKeys(fb)).as("the final bill is the running bill, frozen").isEqualTo(running);
        assertBillAddsUp(fb);
        assertThat(storedLines(c, id)).as("what is stored is what was shown").isEqualTo(running);
        long sum = 0;
        for (JsonNode l : fb.get("lines")) {
            sum += l.get("amountPaise").asLong();
        }
        assertThat(sum).as("frozen total is the sum of the lines (paise)").isEqualTo(total);
        assertThat(values(c, "select status from ipd.admission where id = ?::uuid", id)).containsExactly("DISCHARGED");
        assertThat(bedStatus(c, j.bedB)).as("the bed goes to cleaning").isEqualTo("clean");
    }

    // ================= 14 after discharge =================

    private void step14AfterDischarge() throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        clockAt(20, 15);
        UUID spareBed = bedIn(c, j.wardB);
        String someDose = dosesOf(c, j.hep).get(0).id();
        String written = written(c);
        // every write answers 409 not-admitted; nothing is written
        JsonNode dose = problem(doseCall(j.nurseB.auth(), j.hep, versioned(given(w, ist(20, 0)), 1)), 409);
        assertType(dose, "not-admitted");
        assertThat(dose.get("detail").asText()).isEqualTo(DOSE_NOT_ADMITTED);
        assertType(problem(undoCall(j.nurseB.auth(), someDose, newKey()), 409), "not-admitted");
        assertType(problem(errorCall(j.nurseB.auth(), someDose, newKey(), "WRONG_TIME"), 409), "not-admitted");
        JsonNode vitals = problem(call(HttpMethod.POST, vitalsUrl(adm), j.nurseB.auth(), js(low())), 409);
        assertType(vitals, "not-admitted");
        assertThat(vitals.get("detail").asText()).isEqualTo(VITALS_NOT_ADMITTED);
        JsonNode order = problem(orderCall(j.doctor.auth(), adm, "ceft"), 409);
        assertType(order, "not-admitted");
        assertThat(order.get("detail").asText()).isEqualTo(NOT_ADMITTED_TEXT);
        assertType(problem(call(HttpMethod.POST, Sah76Support.holdUrl(j.hep), j.doctor.auth(), js(Sah76Support.actBody(1, "reason", "Hold it now"))), 409),
                "not-admitted");
        assertType(problem(call(HttpMethod.POST, Sah76Support.changeUrl(j.hep), j.doctor.auth(),
                js(changeBodyFor(1, "Change it now", 2, "IU", "SC", "bd"))), 409), "not-admitted");
        assertType(problem(call(HttpMethod.POST, stopUrl(j.hep), j.doctor.auth(), stopBody(1)), 409), "not-admitted");
        assertType(problem(verifyDummy(j.pharmacist, j.hep, j.pharmacistPin, "AAAAAAAAAAAAAAAAAAAAAA"), 409), "not-admitted");
        assertType(problem(call(HttpMethod.POST, ADMISSIONS + "/" + adm + "/deposits", c.owner(), "{\"amountPaise\":1000,\"mode\":\"Cash\"}"), 409),
                "not-admitted");
        assertType(problem(call(HttpMethod.POST, ADMISSIONS + "/" + adm + "/charges", c.owner(), "{\"serviceKey\":\"xray\"}"), 409), "not-admitted");
        JsonNode move = problem(call(HttpMethod.POST, transferUrl(adm), c.owner(), moveBody(spareBed, null)), 409);
        assertType(move, "not-admitted");
        assertThat(move.get("detail").asText()).isEqualTo(NOT_ADMITTED);
        assertThat(problem(postDischarge(c.owner(), adm, dischargeBody("normal", "general", 1L, 1L, "Cash")), 409).get("detail").asText())
                .isEqualTo(NOT_ADMITTED_DISCHARGE);
        assertThat(problem(call(HttpMethod.PUT, signoffUrl(adm), c.as("DOCTOR"), signBody("normal", "general")), 409).get("detail").asText())
                .isEqualTo(NOT_ADMITTED_SIGN);
        assertThat(written(c)).as("fourteen refused writes wrote nothing (no dose, vitals, order, deposit, charge, move)").isEqualTo(written);

        // the lists are empty of this patient
        assertThat(idsOf(listOf(c.owner()))).doesNotContain(adm);
        assertThat(idsOf(listOf(j.nurseB.auth()))).doesNotContain(adm);
        for (JsonNode r : queue(j.pharmacist.auth(), "limit=100").get("items")) {
            assertThat(j.orderIds).doesNotContain(r.get("orderId").asText());
        }
        assertThat(ok(HttpMethod.GET, Sah76Support.NOTICES_ALL, j.nurseB.auth(), null, 200).toString()).doesNotContain(adm);
        JsonNode safety = boardOf(j.doctor.auth());
        for (JsonNode e : safety.get("escalations")) {
            assertThat(e.get("admissionId").asText()).isNotEqualTo(adm);
        }
        for (JsonNode r : safety.get("medicineRound").get("rows")) {
            assertThat(j.orderIds).doesNotContain(r.get("orderId").asText());
        }
        // the MAR is read-only; the doctor's EMR line is empty
        JsonNode marNow = mar(j.nurseB.auth(), adm);
        assertThat(marNow.get("canGive").asBoolean()).isFalse();
        assertThat(marNow.get("giveLockReason").asText()).isEqualTo("NOT_ADMITTED");
        assertThat(marNow.get("doseCount").asInt()).isZero();
        JsonNode line = executionOf(w, j.hep);
        assertThat(line.get("lateCount").asInt()).as("EMR line of a discharged patient: no late dose").isZero();
        assertThat(line.get("nextDueAt").isNull()).as("EMR line of a discharged patient: nothing due").isTrue();
    }

    // ================= 15 privacy and audit =================

    private void step15Privacy(String logText) throws Exception {
        Clinic c = j.clinic;
        String adm = j.adm.id();
        String auditText = allAuditText(c);
        String events = eventText(c, adm);
        String[] never = {j.adm.name(), j.adm.mobile(), MARK, j.adm.name().split(" ")[0] + " " + j.adm.name().split(" ")[1]};
        for (String text : never) {
            assertThat(logText).as("the log never holds: " + text).doesNotContain(text);
            assertThat(auditText).as("the audit rows never hold: " + text).doesNotContain(text);
            assertThat(events).as("the clinical events never hold: " + text).doesNotContain(text);
        }
        assertThat(logText).as("the log never holds the UHID").doesNotContain(j.uhid);
        assertThat(auditText).as("the audit rows never hold the UHID").doesNotContain(j.uhid);
        for (String pin : new String[] {j.nurseA2Pin, j.nurseBPin, j.pharmacistPin, j.ownerPin, wrongOf(j.pharmacistPin)}) {
            assertThat(hasBarePin(logText, pin)).as("the log never holds the PIN " + pin).isFalse();
            assertThat(hasBarePin(auditText, pin)).as("the audit rows never hold the PIN " + pin).isFalse();
            assertThat(hasBarePin(events, pin)).as("the events never hold the PIN " + pin).isFalse();
        }
        // the UHID is in the log of the clinical events only where the design puts it: the identity-check stop
        for (String line : eventLines(c, adm)) {
            if (line.contains(j.uhid)) {
                assertThat(line).startsWith("deny|Stopped at the identity check");
            }
        }
        // one audit row per write: every action of the journey, with its user, and no row without an actor
        for (String action : new String[] {"IPD_ADMITTED", "IPD_MED_ORDERED", "IPD_MED_VERIFIED", "IPD_DOSE_GIVEN", "IPD_DOSE_NOT_GIVEN",
            "IPD_DOSE_UNDONE", "IPD_DOSE_RECORDED_IN_ERROR", "IPD_MED_HELD", "IPD_MED_RESUMED", "IPD_MED_CHANGED", "IPD_VITALS_RECORDED",
            "IPD_ESCALATION_ACKNOWLEDGED", "IPD_ESCALATION_SENIOR_CALLED", "IPD_ESCALATION_CLOSED", "IPD_EMERGENCY_ACCESS_OPENED",
            "IPD_BED_TRANSFER", "IPD_CHARGE_ADDED", "IPD_DEPOSIT_COLLECTED", "IPD_DISCHARGE_SIGNED", "IPD_DISCHARGED"}) {
            assertThat(audit(c, action)).as("audit rows of " + action).isNotEmpty();
            for (String[] row : audit(c, action)) {
                assertThat(row[4]).as(action + " has an actor").isNotNull();
            }
        }
        assertThat(count(c, "select count(*) from platform.audit_event where actor_user_id is null and action like 'IPD\\_%'"))
                .as("no IPD audit row without a user").isZero();
        // the counts of the journey's writes
        assertThat(audit(c, "IPD_MED_ORDERED")).hasSize(4);
        assertThat(audit(c, "IPD_MED_ORDER_BLOCKED")).hasSize(2);
        assertThat(audit(c, "IPD_DOSE_REFUSED")).as("too soon, on hold, not verified, stale version").hasSize(4);
    }
}
