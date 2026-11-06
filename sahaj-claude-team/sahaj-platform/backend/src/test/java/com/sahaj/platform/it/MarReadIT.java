package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-32 reading the MAR ({@code GET /api/v1/ipd/admissions/{id}/mar}, AC1, AC2, AC3, AC9, AC10, AC11) on the real
 * {@code sahaj_test} database: the shape of the answer, the slots of each frequency, every state of a slot at the edges of
 * the 60-minute windows (with the clock seam of {@link MarSupport}), an order verified in the middle of the day, the IST
 * midnight, the count that is also on the Nursing sheet, who may read, token role against membership role, the
 * record-access row (exactly one per open; none on a refusal), another clinic, IPD Off and Read-only, a discharged patient,
 * Stopped and Rejected orders, and the people offered as second nurse. Test plan: docs/test-plans/SAH-32.md.
 */
@OnTestDatabase
@WithPinPepper
class MarReadIT extends MarSupport {

    // ---------------- the shape ----------------

    @Test
    void theMarHasExactlyTheKeysOfTheContractAndAsOfIsTheDatabaseClock() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(keys(m)).containsExactlyInAnyOrder("admissionId", "status", "state", "asOf", "patient", "doseCount", "canGive",
                "giveLockReason", "orders", "secondNurses", "notGivenReasons");
        assertThat(m.get("admissionId").asText()).isEqualTo(w.adm().id());
        assertThat(m.get("status").asText()).isEqualTo("ADMITTED");
        assertThat(m.get("state").asText()).isIn("trial", "on");
        assertThat(Instant.parse(m.get("asOf").asText())).isEqualTo(ist(8, 5));
        assertThat(keys(m.get("patient"))).containsExactlyInAnyOrder("patientId", "name", "uhid", "age", "sex", "bedCode",
                "allergyText", "allergyStatus");
        assertThat(m.get("patient").get("name").asText()).isEqualTo(w.adm().name());
        assertThat(m.get("patient").get("uhid").asText()).isEqualTo(w.uhid());
        assertThat(m.get("patient").get("bedCode").asText()).isEqualTo(w.adm().bedCode());
        assertThat(m.get("patient").get("allergyStatus").asText()).isEqualTo("NONE_KNOWN");
        JsonNode o = marOrder(m, order);
        assertThat(keys(o)).containsExactlyInAnyOrder("orderId", "drugKey", "drugName", "route", "highAlert", "frequencyKey",
                "frequencyLabel", "orderedByName", "status", "slots");
        assertThat(o.get("drugKey").asText()).isEqualTo("pcm");
        assertThat(o.get("drugName").asText()).isEqualTo("Tab. Paracetamol 650 mg");
        assertThat(o.get("route").asText()).isEqualTo("Oral");
        assertThat(o.get("highAlert").isNull()).isTrue();
        assertThat(o.get("frequencyKey").asText()).isEqualTo("od");
        assertThat(o.get("frequencyLabel").asText()).isEqualTo("Once daily");
        assertThat(o.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(o.get("orderedByName").asText()).isEqualTo(w.doctor().name());
        assertThat(keys(o.get("slots").get(0))).containsExactlyInAnyOrder("slotAt", "state", "lateHours", "canGive", "dose");
        assertThat(keys(m.get("secondNurses").get(0))).containsExactlyInAnyOrder("membershipId", "name");
        assertThat(keys(m.get("notGivenReasons").get(0))).containsExactlyInAnyOrder("key", "label");
    }

    @Test
    void aHighAlertMedicineShowsItsClassAndTheFiveNotGivenReasonsComeInSelectOrder() throws Exception {
        Ward w = ward();
        String hep = activeOrder(w, "hep", "bd");
        String ins = activeOrder(w, "ins", "od");
        String kcl = activeOrder(w, "kcl", "od");
        String pcm = activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(marOrder(m, hep).get("highAlert").asText()).isEqualTo("Anticoagulant");
        assertThat(marOrder(m, ins).get("highAlert").asText()).isEqualTo("Insulin");
        assertThat(marOrder(m, kcl).get("highAlert").asText()).isEqualTo("Concentrated electrolyte");
        assertThat(marOrder(m, pcm).get("highAlert").isNull()).isTrue();
        assertThat(marOrder(m, kcl).get("route").asText()).isEqualTo("IV infusion");
        List<String> reasons = new ArrayList<>();
        for (JsonNode r : m.get("notGivenReasons")) {
            reasons.add(r.get("key").asText() + "=" + r.get("label").asText());
        }
        assertThat(reasons).containsExactly("PATIENT_REFUSED=Patient refused", "HELD_DOCTOR=Held on doctor's advice",
                "NIL_BY_MOUTH=Nil by mouth", "NOT_AVAILABLE=Medicine not available", "AWAY_FROM_WARD=Patient away from the ward");
    }

    @Test
    void withNoOrderTheListIsEmptyAndRejectedAndStoppedOrdersAreNotListed() throws Exception {
        Ward w = ward();
        clockAt(8, 5);
        JsonNode empty = mar(w.nurse().auth(), w.adm().id());
        assertThat(empty.get("orders")).isEmpty();
        assertThat(empty.get("doseCount").asInt()).isZero();
        assertThat(empty.get("notGivenReasons")).hasSize(5);

        String pending = pendingOrder(w, "panto", "od");
        String toReject = pendingOrder(w, "ondan", "od");
        String toStop = activeOrder(w, "pcm", "od");
        String toStopPending = pendingOrder(w, "ceft", "od");
        rejectOk(w.pharmacist().auth(), toReject, "WRONG_DOSE");
        stopOk(w.doctor().auth(), toStop);
        stopOk(w.doctor().auth(), toStopPending);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(m.get("orders")).hasSize(1);
        assertThat(marOrder(m, pending)).isNotNull();
        assertThat(marOrder(m, toReject)).isNull();
        assertThat(marOrder(m, toStop)).isNull();
        assertThat(marOrder(m, toStopPending)).isNull();
    }

    @Test
    void theOrdersComeInChartOrderOldestFirstAndEachHasTheSlotsOfItsFrequencyInIstTimeOrder() throws Exception {
        Ward w = ward();
        String q8 = freshActiveOrder(w, "ceft", "q8");
        String tds = freshActiveOrder(w, "ondan", "tds");
        String bd = freshActiveOrder(w, "panto", "bd");
        String od = freshActiveOrder(w, "pcm", "od");
        // ordered in the reverse of the order they were made: the chart sorts by ordered_at, not by creation
        LocalDate before = day.minusDays(1);
        verifiedAt(w.clinic(), od, istOn(before, 1, 0), istOn(before, 7, 0));
        verifiedAt(w.clinic(), bd, istOn(before, 2, 0), istOn(before, 7, 0));
        verifiedAt(w.clinic(), tds, istOn(before, 3, 0), istOn(before, 7, 0));
        verifiedAt(w.clinic(), q8, istOn(before, 4, 0), istOn(before, 7, 0));
        clockAt(12, 0);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        List<String> ids = new ArrayList<>();
        for (JsonNode o : m.get("orders")) {
            ids.add(o.get("orderId").asText());
        }
        assertThat(ids).containsExactly(od, bd, tds, q8);
        assertThat(instants(m, od)).containsExactly(ist(8, 0));
        assertThat(instants(m, bd)).containsExactly(ist(8, 0), ist(20, 0));
        assertThat(instants(m, tds)).containsExactly(ist(8, 0), ist(14, 0), ist(20, 0));
        assertThat(instants(m, q8)).containsExactly(ist(6, 0), ist(14, 0), ist(22, 0));
        assertThat(marOrder(m, q8).get("frequencyLabel").asText()).isEqualTo("8-hourly");
        assertThat(marOrder(m, tds).get("frequencyLabel").asText()).isEqualTo("Three times daily");
        assertThat(marOrder(m, bd).get("frequencyLabel").asText()).isEqualTo("Twice daily");
    }

    private static List<Instant> instants(JsonNode mar, String orderId) {
        List<Instant> out = new ArrayList<>();
        for (JsonNode s : marOrder(mar, orderId).get("slots")) {
            out.add(Instant.parse(s.get("slotAt").asText()));
        }
        return out;
    }

    // ---------------- the states of a slot against the clock (AC3) ----------------

    private record Edge(int h, int m, int s, String s1, Integer late1, String s2, String s3) {
    }

    @Test
    void everySlotStateAtTheEdgesOfTheSixtyMinuteWindowsWithTheHoursRoundedDown() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "ondan", "tds"); // 8 AM, 2 PM, 8 PM
        Edge[] cases = {
                new Edge(0, 0, 0, "LATER", null, "LATER", "LATER"),
                new Edge(6, 59, 0, "LATER", null, "LATER", "LATER"),   // 61 minutes ahead
                new Edge(6, 59, 1, "DUE", null, "LATER", "LATER"),     // 60 min 59 s ahead rounds down to 60
                new Edge(7, 0, 0, "DUE", null, "LATER", "LATER"),      // exactly 60 minutes ahead
                new Edge(8, 0, 0, "DUE", null, "LATER", "LATER"),
                new Edge(9, 0, 0, "DUE", null, "LATER", "LATER"),      // exactly 60 minutes after is still due
                new Edge(9, 0, 59, "DUE", null, "LATER", "LATER"),     // 60 min 59 s after rounds down to 60
                new Edge(9, 1, 0, "LATE", 1, "LATER", "LATER"),        // 61 minutes after
                new Edge(9, 59, 59, "LATE", 1, "LATER", "LATER"),      // 119 minutes: 1 h late
                new Edge(10, 0, 0, "LATE", 2, "LATER", "LATER"),
                new Edge(10, 0, 59, "LATE", 2, "LATER", "LATER"),
                new Edge(12, 59, 0, "LATE", 4, "LATER", "LATER"),
                new Edge(13, 0, 0, "LATE", 5, "DUE", "LATER"),         // 2 PM is exactly 60 minutes ahead
                new Edge(15, 0, 0, "LATE", 7, "DUE", "LATER"),
                new Edge(15, 1, 0, "LATE", 7, "LATE", "LATER"),
                new Edge(19, 0, 0, "LATE", 11, "LATE", "DUE"),
                new Edge(23, 59, 0, "LATE", 15, "LATE", "LATE")};
        for (Edge e : cases) {
            clockAt(ist(e.h(), e.m(), e.s()));
            JsonNode m = mar(w.nurse().auth(), w.adm().id());
            String at = e.h() + ":" + e.m() + ":" + e.s();
            assertThat(states(m, order)).as(at).containsExactly(e.s1(), e.s2(), e.s3());
            JsonNode first = slot(m, order, 0);
            if (e.late1() == null) {
                assertThat(first.get("lateHours").isNull()).as("lateHours at " + at).isTrue();
            } else {
                assertThat(first.get("lateHours").asInt()).as("lateHours at " + at).isEqualTo(e.late1());
            }
            for (int i = 0; i < 3; i++) {
                JsonNode s = slot(m, order, i);
                boolean button = s.get("state").asText().equals("DUE") || s.get("state").asText().equals("LATE");
                assertThat(s.get("canGive").asBoolean()).as("canGive " + i + " at " + at).isEqualTo(button);
                assertThat(s.get("dose").isNull()).isTrue();
            }
        }
        // 8 PM at 23:59 is 239 minutes late: three hours, rounded down
        assertThat(slot(mar(w.nurse().auth(), w.adm().id()), order, 2).get("lateHours").asInt()).isEqualTo(3);
    }

    @Test
    void aPendingOrderIsListedWithWaitingSlotsThatCannotBeGivenAndDoesNotCount() throws Exception {
        Ward w = ward();
        String pending = pendingOrder(w, "panto", "bd");
        clockAt(9, 30);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        JsonNode o = marOrder(m, pending);
        assertThat(o.get("status").asText()).isEqualTo("PENDING");
        assertThat(states(m, pending)).containsExactly("WAITING", "WAITING");
        for (JsonNode s : o.get("slots")) {
            assertThat(s.get("canGive").asBoolean()).isFalse();
            assertThat(s.get("lateHours").isNull()).isTrue();
        }
        assertThat(m.get("doseCount").asInt()).isZero();
    }

    // ---------------- an order verified in the middle of the day (AC10) ----------------

    @Test
    void anOrderVerifiedMidDayDoesNotShowEarlierSlotsAsLateButTheDueWindowBeforeVerificationCounts() throws Exception {
        Ward w = ward();
        String order = freshActiveOrder(w, "panto", "bd"); // 8 AM and 8 PM
        // verified at 10:30: 8 AM is more than 60 minutes before, so it never started; 8 PM is later today
        verifiedAt(w.clinic(), order, ist(9, 30), ist(10, 30));
        clockAt(10, 35);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(m, order)).containsExactly("BEFORE_START", "LATER");
        assertThat(m.get("doseCount").asInt()).isZero();
        assertThat(slot(m, order, 0).get("canGive").asBoolean()).isFalse();
        clockAt(19, 30);
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("BEFORE_START", "DUE");
        clockAt(22, 0);
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("BEFORE_START", "LATE");

        // verified at 8:45: the 8 AM slot (8:00 is not before 7:45) counts and is due until 9:00, late from 9:01
        verifiedAt(w.clinic(), order, ist(7, 45), ist(8, 45));
        clockAt(8, 50);
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("DUE", "LATER");
        clockAt(9, 0);
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("DUE", "LATER");
        clockAt(9, 1);
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("LATE", "LATER");

        // verified at exactly 9:00 (8:00 is not before 8:00) counts; at 9:01 (8:00 is before 8:01) it does not
        verifiedAt(w.clinic(), order, ist(8, 0), ist(9, 0));
        clockAt(9, 5);
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("LATE", "LATER");
        verifiedAt(w.clinic(), order, ist(8, 0), ist(9, 1));
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("BEFORE_START", "LATER");
    }

    @Test
    void onTheNextDayEverySlotCountsAgainWhateverTimeTheOrderWasVerifiedAndAnyOldRecordIsNotShown() throws Exception {
        Ward w = ward();
        String order = freshActiveOrder(w, "panto", "bd");
        verifiedAt(w.clinic(), order, ist(9, 30), ist(10, 30));
        clockAt(istOn(day.plusDays(1), 8, 5));
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(states(m, order)).containsExactly("DUE", "LATER");
        assertThat(instants(m, order)).containsExactly(istOn(day.plusDays(1), 8, 0), istOn(day.plusDays(1), 20, 0));
    }

    // ---------------- the IST midnight ----------------

    @Test
    void todayIsTheIstDateOfTheClockAtMidnightTheSlotsMoveToTheNextDayAndYesterdaysRecordsLeaveTheMar() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "ceft", "q8"); // 6 AM, 2 PM, 10 PM
        clockAt(ist(22, 5));
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(instants(m, order)).containsExactly(ist(6, 0), ist(14, 0), ist(22, 0));
        assertThat(states(m, order)).containsExactly("LATE", "LATE", "DUE");
        doseOk(w.nurse().auth(), order, given(w, ist(22, 0)));
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("LATE", "LATE", "GIVEN");

        clockAt(ist(23, 59, 59));
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly("LATE", "LATE", "GIVEN");
        Instant midnight = istOn(day.plusDays(1), 0, 0);
        clockAt(midnight.minusSeconds(1));
        assertThat(instants(mar(w.nurse().auth(), w.adm().id()), order)).containsExactly(ist(6, 0), ist(14, 0), ist(22, 0));
        clockAt(midnight);
        JsonNode next = mar(w.nurse().auth(), w.adm().id());
        assertThat(instants(next, order)).containsExactly(istOn(day.plusDays(1), 6, 0), istOn(day.plusDays(1), 14, 0),
                istOn(day.plusDays(1), 22, 0));
        assertThat(states(next, order)).containsExactly("LATER", "LATER", "LATER");
        assertThat(next.get("doseCount").asInt()).isZero();
        // yesterday's slot can no longer be recorded after midnight
        refused(doseCall(w.nurse().auth(), order, given(w, ist(14, 0))), 400, "validation", NOT_A_DOSE_TIME);
    }

    // ---------------- the count and the Nursing sheet (AC1) ----------------

    @Test
    void doseCountIsTheDueAndLateSlotsOfActiveOrdersOnlyAndTheNursingSheetShowsTheSameNumber() throws Exception {
        Ward w = ward();
        String od = activeOrder(w, "pcm", "od");
        String bd = activeOrder(w, "panto", "bd");
        pendingOrder(w, "ondan", "tds"); // waiting: never counted
        clockAt(9, 30);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(m.get("doseCount").asInt()).isEqualTo(2); // pcm 8 AM late, panto 8 AM late; panto 8 PM later
        JsonNode sheet = ok(HttpMethod.GET, nursingUrl(w.adm().id()), w.nurse().auth(), null, 200);
        assertThat(sheet.get("doseCount").asInt()).isEqualTo(2);

        JsonNode saved = doseOk(w.nurse().auth(), od, notGiven(ist(8, 0), "NIL_BY_MOUTH"));
        assertThat(saved.get("mar").get("doseCount").asInt()).as("the answer carries the MAR now").isOne();
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("doseCount").asInt()).isOne();
        assertThat(ok(HttpMethod.GET, nursingUrl(w.adm().id()), w.nurse().auth(), null, 200).get("doseCount").asInt()).isOne();
        // a vitals save answers the same number
        JsonNode vitals = save(w.clinic(), w.adm().id(), low());
        assertThat(vitals.get("doseCount").asInt()).isOne();
        clockAt(20, 30);
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("doseCount").asInt()).isEqualTo(2); // pcm done; panto 8 AM late, 8 PM due
        assertThat(states(mar(w.nurse().auth(), w.adm().id()), bd)).containsExactly("LATE", "DUE");
    }

    @Test
    void forADischargedPatientTheCountIsZeroOnBothAndEverySlotWithoutARecordIsNotRecorded() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        String order2 = activeOrder(w, "panto", "od");
        clockAt(8, 30);
        doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        discharge(w);
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(m.get("status").asText()).isEqualTo("DISCHARGED");
        assertThat(m.get("doseCount").asInt()).isZero();
        assertThat(m.get("canGive").asBoolean()).isFalse();
        assertThat(m.get("giveLockReason").asText()).isEqualTo("NOT_ADMITTED");
        assertThat(m.get("secondNurses")).isEmpty();
        assertThat(states(m, order2)).containsExactly("NOT_RECORDED");
        assertThat(states(m, order)).as("a recorded dose stays visible").containsExactly("GIVEN");
        assertThat(slot(m, order, 0).get("dose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(slot(m, order2, 0).get("canGive").asBoolean()).isFalse();
        JsonNode sheet = ok(HttpMethod.GET, nursingUrl(w.adm().id()), w.nurse().auth(), null, 200);
        assertThat(sheet.get("doseCount").asInt()).isZero();
    }

    // ---------------- who may read ----------------

    @Test
    void ownerNurseAndDoctorReadTheMarAndTheDoctorCannotGiveAndHasNoSecondNurses() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        for (String role : MAR_ROLES) {
            RequestPostProcessorHolder who = new RequestPostProcessorHolder(role.equals("OWNER") ? w.clinic().owner()
                    : role.equals("NURSE") ? w.nurse().auth() : w.doctor().auth());
            JsonNode m = mar(who.auth, w.adm().id());
            boolean giver = !role.equals("DOCTOR");
            assertThat(m.get("canGive").asBoolean()).as(role).isEqualTo(giver);
            if (giver) {
                assertThat(m.get("giveLockReason").isNull()).as(role).isTrue();
                assertThat(m.get("secondNurses")).as(role).isNotEmpty();
            } else {
                assertThat(m.get("giveLockReason").asText()).isEqualTo("ROLE");
                assertThat(m.get("secondNurses")).isEmpty();
            }
            assertThat(slot(m, m.get("orders").get(0).get("orderId").asText(), 0).get("state").asText()).as(role).isEqualTo("DUE");
            assertThat(slot(m, m.get("orders").get(0).get("orderId").asText(), 0).get("canGive").asBoolean()).as(role)
                    .isEqualTo(giver);
        }
    }

    private static final class RequestPostProcessorHolder {
        private final org.springframework.test.web.servlet.request.RequestPostProcessor auth;

        RequestPostProcessorHolder(org.springframework.test.web.servlet.request.RequestPostProcessor auth) {
            this.auth = auth;
        }
    }

    @Test
    void everyOtherRoleGets403WithTheMedicineRoundTextAndNothingIsWritten() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        DSnap before = dsnap(w);
        for (String role : NON_MAR_ROLES) {
            refused(call(HttpMethod.GET, marUrl(w.adm().id()), staff(w.clinic(), role).auth(), null), 403, "forbidden", MAR_HIDDEN);
        }
        assertThat(dsnap(w)).as("no access row for a refusal").isEqualTo(before);
        refused(call(HttpMethod.GET, marUrl(w.adm().id()), noSession(), null), 401, "unauthorized", null);
    }

    @Test
    void theRoleInTheTokenAndTheRoleOfTheActiveMembershipMustBothAllowReadingAndGiving() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        // a nurse whose membership became FRONT_DESK keeps a NURSE token: the membership wins, 403
        Pn leaving = newPn(w.clinic(), "NURSE");
        setMembershipRole(w.clinic(), leaving.userId(), "FRONT_DESK");
        refused(call(HttpMethod.GET, marUrl(w.adm().id()), leaving.auth(), null), 403, "forbidden", MAR_HIDDEN);
        // a nurse whose membership became DOCTOR may still read but not give
        Pn promoted = newPn(w.clinic(), "NURSE");
        setMembershipRole(w.clinic(), promoted.userId(), "DOCTOR");
        JsonNode m = mar(promoted.auth(), w.adm().id());
        assertThat(m.get("canGive").asBoolean()).isFalse();
        assertThat(m.get("giveLockReason").asText()).isEqualTo("ROLE");
        // a doctor whose token says OWNER: the membership role DOCTOR cannot give
        JsonNode asOwnerToken = mar(tokenOf(w.clinic(), w.doctor().userId(), "OWNER"), w.adm().id());
        assertThat(asOwnerToken.get("canGive").asBoolean()).isFalse();
        // a nurse whose token says DOCTOR cannot give either
        JsonNode asDoctorToken = mar(tokenOf(w.clinic(), w.nurse().userId(), "DOCTOR"), w.adm().id());
        assertThat(asDoctorToken.get("canGive").asBoolean()).isFalse();
        // a switched-off membership has no access at all
        Pn gone = newPn(w.clinic(), "NURSE");
        setMembershipStatus(w.clinic().tenantId(), gone.userId(), "INACTIVE");
        refused(call(HttpMethod.GET, marUrl(w.adm().id()), gone.auth(), null), 403, "forbidden", MAR_HIDDEN);
    }

    @Test
    void theSecondNursesAreTheOtherActiveNursesOfTheClinicOnlyAndNeverTheSignedInPerson() throws Exception {
        Ward w = ward();
        activeOrder(w, "hep", "od");
        Pn third = newPn(w.clinic(), "NURSE");
        Pn off = newPn(w.clinic(), "NURSE");
        setMembershipStatus(w.clinic().tenantId(), off.userId(), "INACTIVE");
        newPn(w.clinic(), "PHARMACIST");
        clockAt(8, 5);
        List<String> ids = secondNurseIds(mar(w.nurse().auth(), w.adm().id()));
        assertThat(ids).containsExactlyInAnyOrder(w.nurse2().membershipId().toString(), third.membershipId().toString());
        assertThat(ids).doesNotContain(w.nurse().membershipId().toString(), off.membershipId().toString());
        List<String> forNurse2 = secondNurseIds(mar(w.nurse2().auth(), w.adm().id()));
        assertThat(forNurse2).containsExactlyInAnyOrder(w.nurse().membershipId().toString(), third.membershipId().toString());
        // the owner is not a nurse and is not offered; the owner sees every nurse
        List<String> forOwner = secondNurseIds(mar(w.clinic().owner(), w.adm().id()));
        assertThat(forOwner).containsExactlyInAnyOrder(w.nurse().membershipId().toString(), w.nurse2().membershipId().toString(),
                third.membershipId().toString());
        JsonNode option = mar(w.nurse().auth(), w.adm().id()).get("secondNurses").get(0);
        assertThat(option.get("name").asText()).isNotBlank();
    }

    private static List<String> secondNurseIds(JsonNode mar) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : mar.get("secondNurses")) {
            out.add(n.get("membershipId").asText());
        }
        return out;
    }

    // ---------------- the record-access row (ADR-011 D10) ----------------

    @Test
    void everyOpenOfTheMarWritesExactlyOneRecordAccessRowAndNothingElse() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        DSnap before = dsnap(w);
        mar(w.nurse().auth(), w.adm().id());
        DSnap one = dsnap(w);
        assertThat(one.access()).isEqualTo(before.access() + 1);
        assertThat(new DSnap(one.doses(), one.events(), one.audits(), before.access(), one.pins()))
                .as("no event, audit row, dose or PIN change").isEqualTo(before);
        mar(w.doctor().auth(), w.adm().id());
        mar(w.clinic().owner(), w.adm().id());
        assertThat(dsnap(w).access()).isEqualTo(before.access() + 3);
        assertThat(count(w.clinic(), "select count(*) from patient.record_access where patient_id = ?::uuid and user_id = ?::uuid",
                w.adm().patientId().toString(), w.nurse().userId().toString())).isOne();
    }

    @Test
    void aGiveWritesNoAccessRowAndAReplayWritesExactlyOneAndNothingElse() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        DSnap before = dsnap(w);
        java.util.Map<String, Object> body = given(w, ist(8, 0));
        JsonNode first = doseOk(w.nurse().auth(), order, body);
        DSnap afterGive = dsnap(w);
        assertThat(afterGive.access()).as("a 201 writes none (its audit row records it)").isEqualTo(before.access());
        assertThat(afterGive.doses()).isEqualTo(before.doses() + 1);
        JsonNode replay = ok(HttpMethod.POST, dosesUrl(order), w.nurse().auth(), js(body), 200);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        assertThat(replay.get("dose").get("id").asText()).isEqualTo(first.get("dose").get("id").asText());
        DSnap afterReplay = dsnap(w);
        assertThat(afterReplay.access()).isEqualTo(afterGive.access() + 1);
        assertThat(new DSnap(afterReplay.doses(), afterReplay.events(), afterReplay.audits(), afterGive.access(),
                afterReplay.pins())).isEqualTo(afterGive);
    }

    @Test
    void aRefusalOrANotFoundWritesNoAccessRow() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        DSnap before = dsnap(w);
        refused(call(HttpMethod.GET, marUrl(UUID.randomUUID()), w.nurse().auth(), null), 404, "not-found", null);
        refused(call(HttpMethod.GET, marUrl(w.adm().id()), staff(w.clinic(), "BILLING").auth(), null), 403, "forbidden", MAR_HIDDEN);
        assertThat(dsnap(w)).isEqualTo(before);
    }

    // ---------------- another clinic, IPD Off, Read-only ----------------

    @Test
    void anotherClinicsAdmissionAndOrderAreNotFoundAndNothingIsWritten() throws Exception {
        Ward a = ward();
        Ward b = ward();
        String orderB = activeOrder(b, "pcm", "od");
        clockAt(8, 5);
        DSnap before = dsnap(b);
        refused(call(HttpMethod.GET, marUrl(b.adm().id()), a.nurse().auth(), null), 404, "not-found", null);
        refused(call(HttpMethod.GET, marUrl(b.adm().id()), a.clinic().owner(), null), 404, "not-found", null);
        refused(doseCall(a.nurse().auth(), orderB, given(b, ist(8, 0))), 404, "not-found", null);
        refused(doseCall(a.clinic().owner(), orderB, notGiven(ist(8, 0), "NIL_BY_MOUTH")), 404, "not-found", null);
        assertThat(dsnap(b)).isEqualTo(before);
        // and the other way round the data of clinic B is not in A's MAR
        JsonNode mineA = mar(a.nurse().auth(), a.adm().id());
        assertThat(mineA.get("orders")).isEmpty();
        assertThat(secondNurseIds(mineA)).doesNotContain(b.nurse().membershipId().toString(), b.nurse2().membershipId().toString());
    }

    @Test
    void whenIpdIsOffTheMarAndTheDoseAreModuleOffForTheAllowedRolesAndTheRoleCheckComesFirst() throws Exception {
        Clinic off = newClinic(); // no trial: IPD is Off
        String id = UUID.randomUUID().toString();
        for (String role : MAR_ROLES) {
            refused(call(HttpMethod.GET, marUrl(id), off.as(role), null), 403, "module-off", OFF_TEXT);
        }
        for (String role : GIVE_ROLES) {
            refused(call(HttpMethod.POST, dosesUrl(id), off.as(role), "{}"), 403, "module-off", OFF_TEXT);
        }
        refused(call(HttpMethod.GET, marUrl(id), off.as("FRONT_DESK"), null), 403, "forbidden", MAR_HIDDEN);
        refused(call(HttpMethod.POST, dosesUrl(id), off.as("DOCTOR"), "{}"), 403, "forbidden", ONLY_GIVERS);
    }

    @Test
    void inReadOnlyTheMarStillOpensForReadingWithTheLockReasonAndNobodyCanGive() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "od");
        clockAt(8, 5);
        expireTrial(w.clinic());
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(m.get("state").asText()).isEqualTo("readonly");
        assertThat(m.get("canGive").asBoolean()).isFalse();
        assertThat(m.get("giveLockReason").asText()).isEqualTo("IPD_READONLY");
        assertThat(slot(m, order, 0).get("state").asText()).isEqualTo("DUE");
        assertThat(slot(m, order, 0).get("canGive").asBoolean()).isFalse();
        assertThat(m.get("secondNurses")).isEmpty();
        assertThat(m.get("doseCount").asInt()).as("the count is still the number of due slots").isOne();
        // the doctor in Read-only: the role is the first reason
        assertThat(mar(w.doctor().auth(), w.adm().id()).get("giveLockReason").asText()).isEqualTo("ROLE");
        // a discharged patient in Read-only: the IPD state is named first (the discharge is made before the trial ends: a
        // Read-only clinic cannot sign or discharge)
        Ward gone = ward();
        activeOrder(gone, "pcm", "od");
        discharge(gone);
        expireTrial(gone.clinic());
        JsonNode discharged = mar(gone.nurse().auth(), gone.adm().id());
        assertThat(discharged.get("status").asText()).isEqualTo("DISCHARGED");
        assertThat(discharged.get("giveLockReason").asText()).isEqualTo("IPD_READONLY");
    }

    @Test
    void aDischargedPatientsMarIsReadableByTheThreeRolesAndIsLockedForGiving() throws Exception {
        Ward w = ward();
        activeOrder(w, "pcm", "od");
        discharge(w);
        for (String role : MAR_ROLES) {
            RequestPostProcessorHolder who = new RequestPostProcessorHolder(role.equals("OWNER") ? w.clinic().owner()
                    : role.equals("NURSE") ? w.nurse().auth() : w.doctor().auth());
            JsonNode m = mar(who.auth, w.adm().id());
            assertThat(m.get("status").asText()).as(role).isEqualTo("DISCHARGED");
            assertThat(m.get("canGive").asBoolean()).as(role).isFalse();
            assertThat(m.get("giveLockReason").asText()).as(role).isEqualTo(role.equals("DOCTOR") ? "ROLE" : "NOT_ADMITTED");
        }
    }

    // ---------------- Stop and the last dose (AC10) ----------------

    @Test
    void aStoppedOrderLeavesTheMarButItsRecordedDoseStaysInTheEmrLastDose() throws Exception {
        Ward w = ward();
        String order = activeOrder(w, "pcm", "bd");
        clockAt(8, 20);
        JsonNode given = doseOk(w.nurse().auth(), order, given(w, ist(8, 0)));
        stopOk(w.doctor().auth(), order);
        assertThat(marOrder(mar(w.nurse().auth(), w.adm().id()), order)).isNull();
        assertThat(doseRowsOfOrder(w.clinic(), order)).isOne();
        JsonNode row = sheetRow(emr(w.doctor().auth(), w.adm().id()), order);
        assertThat(row.get("status").asText()).isEqualTo("STOPPED");
        assertThat(row.get("lastDose").get("id").asText()).isEqualTo(given.get("dose").get("id").asText());
        assertThat(row.get("lastDose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(Instant.parse(row.get("lastDose").get("slotAt").asText())).isEqualTo(ist(8, 0));
        // its remaining slot can no longer be recorded
        refused(doseCall(w.nurse().auth(), order, given(w, ist(20, 0))), 409, "order-state", DOSE_STOPPED);
    }

    // ---------------- only live orders on the MAR and in the count (review R7) ----------------

    @Test
    void aStoppedAndARejectedOrderNeverAppearOnTheMarNorInTheDoseCountOnAnyRouteButAStoppedOrdersDoseStaysInTheEmrLastDose()
            throws Exception {
        Ward w = ward();
        String live = activeOrder(w, "pcm", "od");
        String liveBd = activeOrder(w, "met", "bd");
        String waiting = pendingOrder(w, "ondan", "od");
        // would add late slots if it were still counted: active, 8 AM late, then stopped
        String stoppedLate = activeOrder(w, "panto", "bd");
        // given its 8 AM dose first, then stopped; its 8 PM slot is later and its last dose must stay readable
        String stoppedGiven = activeOrder(w, "ceft", "bd");
        String stoppedWaiting = pendingOrder(w, "amox", "od");
        String rejected = pendingOrder(w, "cotri", "od");
        clockAt(8, 20);
        JsonNode givenBeforeStop = doseOk(w.nurse().auth(), stoppedGiven, given(w, ist(8, 0)));
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("doseCount").asInt()).as("before the Stop: 3 late slots").isEqualTo(3);
        stopOk(w.doctor().auth(), stoppedLate);
        stopOk(w.doctor().auth(), stoppedGiven);
        stopOk(w.doctor().auth(), stoppedWaiting);
        rejectOk(w.pharmacist().auth(), rejected, "WRONG_DOSE");

        // the MAR
        JsonNode m = mar(w.nurse().auth(), w.adm().id());
        assertThat(m.get("orders")).as("only the live and the waiting orders").hasSize(3);
        for (String gone : new String[] {stoppedLate, stoppedGiven, stoppedWaiting, rejected}) {
            assertThat(marOrder(m, gone)).as("order " + gone).isNull();
        }
        assertThat(marOrder(m, live)).isNotNull();
        assertThat(marOrder(m, liveBd)).isNotNull();
        assertThat(marOrder(m, waiting)).isNotNull();
        assertThat(m.get("doseCount").asInt()).as("only pcm 8 AM and met 8 AM").isEqualTo(2);
        // the Nursing sheet GET
        assertThat(ok(HttpMethod.GET, nursingUrl(w.adm().id()), w.nurse().auth(), null, 200).get("doseCount").asInt()).isEqualTo(2);
        // a vitals save
        assertThat(save(w.clinic(), w.adm().id(), low()).get("doseCount").asInt()).isEqualTo(2);
        // the answer of a dose carries the MAR: no stopped or rejected order and the count after the dose
        JsonNode answer = doseOk(w.nurse().auth(), live, given(w, ist(8, 0)));
        JsonNode inAnswer = answer.get("mar");
        assertThat(inAnswer.get("orders")).hasSize(3);
        for (String gone : new String[] {stoppedLate, stoppedGiven, stoppedWaiting, rejected}) {
            assertThat(marOrder(inAnswer, gone)).as("order " + gone + " in the dose answer").isNull();
        }
        assertThat(inAnswer.get("doseCount").asInt()).isOne();
        assertThat(mar(w.nurse().auth(), w.adm().id()).get("doseCount").asInt()).isOne();
        assertThat(ok(HttpMethod.GET, nursingUrl(w.adm().id()), w.nurse().auth(), null, 200).get("doseCount").asInt()).isOne();

        // the EMR keeps all orders and the Stopped order's dose
        JsonNode emr = emr(w.doctor().auth(), w.adm().id());
        JsonNode row = sheetRow(emr, stoppedGiven);
        assertThat(row.get("status").asText()).isEqualTo("STOPPED");
        assertThat(row.get("lastDose").get("id").asText()).isEqualTo(givenBeforeStop.get("dose").get("id").asText());
        assertThat(row.get("lastDose").get("outcome").asText()).isEqualTo("GIVEN");
        assertThat(sheetRow(emr, stoppedLate).get("status").asText()).isEqualTo("STOPPED");
        assertThat(sheetRow(emr, stoppedLate).get("lastDose").isNull()).isTrue();
        assertThat(sheetRow(emr, rejected).get("status").asText()).isEqualTo("REJECTED");
        assertThat(sheetRow(emr, rejected).get("lastDose").isNull()).isTrue();
        // and nothing can be recorded on them
        refused(doseCall(w.nurse().auth(), stoppedLate, given(w, ist(8, 0))), 409, "order-state", DOSE_STOPPED);
        refused(doseCall(w.nurse().auth(), rejected, given(w, ist(8, 0))), 409, "order-state", DOSE_REJECTED);
    }

    // ---------------- the contract of the path ----------------

    @Test
    void aMalformedAdmissionIdIsAProblemNotAServerError() throws Exception {
        Ward w = ward();
        JsonNode p = problem(call(HttpMethod.GET, ADMISSIONS + "/not-a-uuid/mar", w.nurse().auth(), null), 400);
        assertThat(p.get("title")).isNotNull();
    }
}
