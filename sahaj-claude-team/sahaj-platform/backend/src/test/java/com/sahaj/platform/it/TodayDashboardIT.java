package com.sahaj.platform.it;

import static com.sahaj.platform.it.BackdateSupport.istToday;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-15 Today dashboard on the real {@code sahaj_test} database: {@code GET /opd/today-summary} and
 * {@code GET /billing/today-summary} after a real set-up, real tokens in several stages for two doctors (new and
 * follow-up), real payments and a refund. Wait times and follow-up dates are moved with committed SQL.
 */
@OnTestDatabase
class TodayDashboardIT extends SweepSupport {

    private static final String OPD = "/api/v1/opd/today-summary";
    private static final String BILLING = "/api/v1/billing";
    private static final String VISITS = "/api/v1/opd/visits";
    private static final String[] ALL_ROLES = {"OWNER", "DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING",
            "PHARMACIST", "COUNSELLOR"};

    /** The ids and token numbers of the scenario; see {@link #scenario}. */
    private record Day(Clinic clinic, UUID doctorB, int a1, int a2, int a3, int a4a, int a4b, int b5, int b6, int b7,
                       String a1Id, String a2Id, String a3Id, String a4bId, String b5Id, String b7Id) {
    }

    private JsonNode issue(Clinic clinic, UUID patient, UUID doctor) throws Exception {
        return ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + patient + "\",\"doctorMembershipId\":\"" + doctor + "\"}", 201);
    }

    private void move(Clinic clinic, JsonNode visit, String... stages) throws Exception {
        for (String stage : stages) {
            ok(HttpMethod.PATCH, VISITS + "/" + visit.get("id").asText() + "/stage", clinic.owner(),
                    "{\"stage\":\"" + stage + "\"}", 200);
        }
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        return out;
    }

    private static List<Integer> taskTokens(JsonNode tasks) {
        List<Integer> out = new ArrayList<>();
        tasks.forEach(t -> out.add(t.get("tokenNo").asInt()));
        return out;
    }

    private JsonNode doctor(JsonNode summary, UUID membershipId) {
        for (JsonNode d : summary.get("doctors")) {
            if (d.get("doctorMembershipId").asText().equals(membershipId.toString())) {
                return d;
            }
        }
        throw new AssertionError("Doctor " + membershipId + " not in " + summary.get("doctors"));
    }

    /**
     * Doctor A (Asha): A1 with doctor, A2 and A3 waiting in vitals, A4a done and paid (refunded Rs 50 afterwards),
     * A4b a follow-up token waiting. Doctor B: B5 at billing (partly paid), B6 in vitals, B7 at billing at a zero
     * balance (100 percent discount). Follow-ups: A4a returned, B5 due today, B7 missed.
     */
    private Day scenario() throws Exception {
        Clinic clinic = newClinic(true);
        UUID doctorB = addMember(clinic, "DOCTOR").membershipId();
        UUID a = clinic.doctorMembershipId();

        JsonNode a1 = issue(clinic, newPatient(clinic), a);
        move(clinic, a1, "WITH_DOCTOR");
        JsonNode a2 = issue(clinic, newPatient(clinic), a);
        JsonNode a3 = issue(clinic, newPatient(clinic), a);
        UUID p4 = newPatient(clinic);
        JsonNode a4a = issue(clinic, p4, a);
        move(clinic, a4a, "WITH_DOCTOR", "BILLING");
        ok(HttpMethod.POST, BILLING + "/bills/" + a4a.get("id").asText() + "/payments", clinic.as("FRONT_DESK"),
                "{\"mode\":\"CASH\",\"amountPaise\":30000}", 200);
        JsonNode a4b = issue(clinic, p4, a);
        assertThat(a4b.get("visitType").asText()).isEqualTo("FOLLOW_UP");

        JsonNode b5 = issue(clinic, newPatient(clinic), doctorB);
        move(clinic, b5, "WITH_DOCTOR", "BILLING");
        ok(HttpMethod.POST, BILLING + "/bills/" + b5.get("id").asText() + "/payments", clinic.owner(),
                "{\"mode\":\"UPI\",\"amountPaise\":10000}", 200);
        JsonNode b6 = issue(clinic, newPatient(clinic), doctorB);
        JsonNode b7 = issue(clinic, newPatient(clinic), doctorB);
        move(clinic, b7, "WITH_DOCTOR", "BILLING");
        ok(HttpMethod.PUT, BILLING + "/bills/" + b7.get("id").asText() + "/discount", clinic.owner(),
                "{\"percent\":100,\"reason\":\"Staff family\"}", 200);

        // A refund on the finished A4a bill: net of today's payments drops, and the bill gets a balance again.
        ok(HttpMethod.POST, BILLING + "/bills/" + a4a.get("id").asText() + "/refund", clinic.owner(),
                "{\"amountPaise\":5000,\"mode\":\"CASH\",\"reason\":\"Service not given\"}", 200);

        BackdateSupport.setFollowUpDueDate(db(), clinic.tenantId(), b5.get("id").asText(), istToday());
        BackdateSupport.setFollowUpDueDate(db(), clinic.tenantId(), b7.get("id").asText(), istToday().minusDays(3));
        return new Day(clinic, doctorB, a1.get("tokenNo").asInt(), a2.get("tokenNo").asInt(),
                a3.get("tokenNo").asInt(), a4a.get("tokenNo").asInt(), a4b.get("tokenNo").asInt(),
                b5.get("tokenNo").asInt(), b6.get("tokenNo").asInt(), b7.get("tokenNo").asInt(),
                a1.get("id").asText(), a2.get("id").asText(), a3.get("id").asText(), a4b.get("id").asText(),
                b5.get("id").asText(), b7.get("id").asText());
    }

    // ---------------- AC2: the four tiles ----------------

    @Test
    void tilesCountNewFollowUpWaitingInOpdAndFollowUps() throws Exception {
        Day d = scenario();
        JsonNode s = getJson(OPD, d.clinic().owner());
        assertThat(s.get("patientsTotal").asInt()).isEqualTo(8);
        assertThat(s.get("newCount").asInt()).isEqualTo(7);
        assertThat(s.get("followUpCount").asInt()).isEqualTo(1);
        // Waiting now = vitals (or waiting): A2, A3, A4b, B6. In OPD = everything not done (A4a is done).
        assertThat(s.get("waitingCount").asInt()).isEqualTo(4);
        assertThat(s.get("inOpdCount").asInt()).isEqualTo(7);
        assertThat(s.get("longestWaitMinutes").asInt()).isBetween(0, 5);
        // Follow-ups: A4a returned (A4b came back), B5 due today, B7 missed.
        assertThat(s.get("followUpsDue").asInt()).isEqualTo(1);
        assertThat(s.get("followUpsReturned").asInt()).isEqualTo(1);
        assertThat(s.get("followUpsMissed").asInt()).isEqualTo(1);
        // The tiles agree with the Follow-ups screen counts.
        JsonNode counts = getJson("/api/v1/opd/follow-ups?status=DUE", d.clinic().owner()).get("counts");
        assertThat(counts.get("due").asInt()).isEqualTo(s.get("followUpsDue").asInt());
        assertThat(counts.get("missed").asInt()).isEqualTo(s.get("followUpsMissed").asInt());
        assertThat(counts.get("returned").asInt()).isEqualTo(s.get("followUpsReturned").asInt());
    }

    @Test
    void aFollowUpReturnedYesterdayIsNotCountedAsReturnedToday() throws Exception {
        Clinic clinic = newClinic(true);
        UUID patient = newPatient(clinic);
        JsonNode first = issue(clinic, patient, clinic.doctorMembershipId());
        move(clinic, first, "WITH_DOCTOR", "BILLING");
        JsonNode comeBack = issue(clinic, patient, clinic.doctorMembershipId());

        assertThat(getJson(OPD, clinic.owner()).get("followUpsReturned").asInt()).isEqualTo(1);

        BackdateSupport.setFollowUpReturnedAt(db(), clinic.tenantId(), comeBack.get("id").asText(),
                BackdateSupport.noon(1));

        JsonNode s = getJson(OPD, clinic.owner());
        assertThat(s.get("followUpsReturned").asInt()).isZero();
        // The Returned tab on the Follow-ups screen still counts it: that badge is all time.
        JsonNode counts = getJson("/api/v1/opd/follow-ups?status=RETURNED", clinic.owner()).get("counts");
        assertThat(counts.get("returned").asInt()).isEqualTo(1);
    }

    @Test
    void longestWaitIsTheOldestWaitingTokenInMinutesAndIgnoresOtherStages() throws Exception {
        Day d = scenario();
        // An old token that is already with the doctor must not count; an old waiting one must.
        // Offsets shrink (never beyond 1x) right after IST midnight so the visits stay on today's IST date.
        // Skipped only in the first 3 minutes of the IST day, where no meaningful wait fits.
        Assumptions.assumeTrue(BackdateSupport.secondsSinceIstMidnight() > 180, "too close to IST midnight");
        double f = BackdateSupport.fitToIstDay(40);
        BackdateSupport.setVisitCreatedAt(db(), d.clinic().tenantId(), d.a1Id(), BackdateSupport.ago(40, f));
        // The 40 min token is with the doctor and must be ignored; the real waits are only seconds old (< 5 min).
        assertThat(getJson(OPD, d.clinic().owner()).get("longestWaitMinutes").asInt()).isBetween(0, 5);
        BackdateSupport.setVisitCreatedAt(db(), d.clinic().tenantId(), d.a3Id(), BackdateSupport.ago(12, f));
        int longest = getJson(OPD, d.clinic().owner()).get("longestWaitMinutes").asInt();
        int expected = (int) Math.floor(12 * f);
        assertThat(longest).isBetween(expected, expected + 1);
        if (f == 1.0) {
            assertThat(longest).isBetween(12, 13);
        }
    }

    @Test
    void anEmptyClinicShowsZerosRoomFreeAndNoTasks() throws Exception {
        Clinic clinic = newClinic(true);
        JsonNode s = getJson(OPD, clinic.owner());
        for (String field : new String[] {"patientsTotal", "newCount", "followUpCount", "waitingCount", "inOpdCount",
                "longestWaitMinutes", "followUpsDue", "followUpsReturned", "followUpsMissed"}) {
            assertThat(s.get(field).asInt()).as(field).isZero();
        }
        assertThat(s.get("myTasks")).isEmpty();
        JsonNode asha = doctor(s, clinic.doctorMembershipId());
        assertThat(asha.get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        assertThat(asha.get("nowWithDoctor").isNull()).isTrue();
        assertThat(asha.get("nextTokens")).isEmpty();
        JsonNode money = getJson(BILLING + "/today-summary", clinic.owner());
        assertThat(money.get("collectedPaise").asLong()).isZero();
        assertThat(money.get("billsPending").asInt()).isZero();
    }

    // ---------------- AC3: Live OPD per doctor ----------------

    @Test
    void eachDoctorShowsNowWithDoctorAndNextTokensOldestFirst() throws Exception {
        Day d = scenario();
        JsonNode s = getJson(OPD, d.clinic().owner());

        JsonNode a = doctor(s, d.clinic().doctorMembershipId());
        JsonNode now = a.get("nowWithDoctor");
        assertThat(now.get("tokenNo").asInt()).isEqualTo(d.a1());
        assertThat(now.get("visitId").asText()).isEqualTo(d.a1Id());
        assertThat(now.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
        assertThat(now.get("age").asInt()).isEqualTo(45);
        assertThat(now.get("sex").asText()).isEqualTo("M");
        assertThat(now.get("visitType").asText()).isEqualTo("NEW");
        // Waiting or vitals only: not A1 (with doctor), not A4a (done).
        assertThat(ints(a.get("nextTokens"))).containsExactly(d.a2(), d.a3(), d.a4b());

        JsonNode b = doctor(s, d.doctorB());
        assertThat(b.get("doctorName").asText()).isEqualTo("Member DOCTOR");
        assertThat(b.get("nowWithDoctor").isNull()).isTrue();
        // B5 and B7 are at billing: only B6 waits.
        assertThat(ints(b.get("nextTokens"))).containsExactly(d.b6());

        // Next in line is ordered by when the token was issued, not by its number.
        // The 3 min offset must exceed the scenario's own age, so skip the first 10 IST minutes (offsets are then
        // scaled to stay on today's IST date).
        Assumptions.assumeTrue(BackdateSupport.secondsSinceIstMidnight() > 600, "too close to IST midnight");
        double f = BackdateSupport.fitToIstDay(8);
        BackdateSupport.setVisitCreatedAt(db(), d.clinic().tenantId(), d.a3Id(), BackdateSupport.ago(8, f));
        BackdateSupport.setVisitCreatedAt(db(), d.clinic().tenantId(), d.a2Id(), BackdateSupport.ago(3, f));
        assertThat(ints(doctor(getJson(OPD, d.clinic().owner()), d.clinic().doctorMembershipId()).get("nextTokens")))
                .containsExactly(d.a3(), d.a2(), d.a4b());
    }

    @Test
    void aFollowUpTokenIsLabelledFollowUpWhenItIsWithTheDoctor() throws Exception {
        Day d = scenario();
        // A1 leaves for billing; A4b (follow-up) is called in.
        ok(HttpMethod.PATCH, VISITS + "/" + d.a1Id() + "/stage", d.clinic().owner(), "{\"stage\":\"BILLING\"}", 200);
        ok(HttpMethod.PATCH, VISITS + "/" + d.a4bId() + "/stage", d.clinic().owner(), "{\"stage\":\"WITH_DOCTOR\"}", 200);
        JsonNode a = doctor(getJson(OPD, d.clinic().owner()), d.clinic().doctorMembershipId());
        assertThat(a.get("nowWithDoctor").get("tokenNo").asInt()).isEqualTo(d.a4b());
        assertThat(a.get("nowWithDoctor").get("visitType").asText()).isEqualTo("FOLLOW_UP");
        assertThat(ints(a.get("nextTokens"))).containsExactly(d.a2(), d.a3());
    }

    // ---------------- AC4: My tasks per role ----------------

    @Test
    void myTasksFollowTheRoleAndNeverIncludeDoneVisits() throws Exception {
        Day d = scenario();
        Clinic c = d.clinic();
        List<Integer> notDone = List.of(d.a1(), d.a2(), d.a3(), d.a4b(), d.b5(), d.b6(), d.b7());

        for (String role : new String[] {"OWNER", "FRONT_DESK"}) {
            assertThat(taskTokens(getJson(OPD, c.as(role)).get("myTasks"))).as(role)
                    .containsExactlyInAnyOrderElementsOf(notDone);
        }
        // Nurse owns the vitals stage (workflow default).
        JsonNode nurse = getJson(OPD, c.as("NURSE")).get("myTasks");
        assertThat(taskTokens(nurse)).containsExactlyInAnyOrder(d.a2(), d.a3(), d.a4b(), d.b6());
        for (JsonNode t : nurse) {
            assertThat(t.get("stage").asText()).isEqualTo("VITALS");
            assertThat(t.get("patientName").asText()).isEqualTo("Ramesh Kulkarni");
            assertThat(t.get("waitMinutes").asInt()).isBetween(0, 5);
            assertThat(t.get("doctorName").asText()).isNotBlank();
            assertThat(t.get("visitId").asText()).matches("[0-9a-f-]{36}");
        }
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR"}) {
            JsonNode tasks = getJson(OPD, c.as(role)).get("myTasks");
            assertThat(taskTokens(tasks)).as(role).containsExactly(d.a1());
            assertThat(tasks.get(0).get("stage").asText()).isEqualTo("WITH_DOCTOR");
            assertThat(tasks.get(0).get("doctorName").asText()).isEqualTo("Dr Asha Rao");
        }
        for (String role : new String[] {"BILLING", "PHARMACIST", "COUNSELLOR"}) {
            assertThat(getJson(OPD, c.as(role)).get("myTasks")).as(role).isEmpty();
        }
        for (String role : ALL_ROLES) {
            JsonNode body = getJson(OPD, c.as(role));
            // The finished token never shows for anyone, and the tile figures are the same for every role.
            assertThat(taskTokens(body.get("myTasks"))).as(role).doesNotContain(d.a4a());
            assertThat(body.get("patientsTotal").asInt()).as(role).isEqualTo(8);
        }
    }

    // ---------------- billing today-summary ----------------

    @Test
    void billingSummaryIsTheNetOfTodaysRealPaymentsAndPendingIsTheCountersAwaitingWithBalance() throws Exception {
        Day d = scenario();
        Clinic c = d.clinic();
        JsonNode money = getJson(BILLING + "/today-summary", c.owner());
        // 30000 cash + 10000 UPI - 5000 cash refund.
        assertThat(money.get("collectedPaise").asLong()).isEqualTo(35000L);
        // B5 awaits with a balance; B7 awaits at zero (100 percent discount); A4a is done (with a balance after the refund).
        assertThat(money.get("billsPending").asInt()).isEqualTo(1);
        JsonNode counter = getJson(BILLING + "/counter", c.owner());
        assertThat(counter.get("awaiting").size()).isEqualTo(2);
        long awaitingWithBalance = 0;
        for (JsonNode row : counter.get("awaiting")) {
            if (row.get("balancePaise").asLong() > 0) {
                awaitingWithBalance++;
            }
        }
        assertThat(money.get("billsPending").asInt()).isEqualTo(awaitingWithBalance);

        // A payment recorded yesterday is not part of today's figure and does not change what is pending.
        BackdateSupport.insertPayment(db(), c.tenantId(), c.ownerUserId(), d.b5Id(), "UPI", 5000,
                null, BackdateSupport.noon(1));
        JsonNode after = getJson(BILLING + "/today-summary", c.owner());
        assertThat(after.get("collectedPaise").asLong()).isEqualTo(35000L);
        assertThat(after.get("billsPending").asInt()).isEqualTo(1);

        // Paying B5 off (fee 25000 - 10000 - 5000 = 10000 left) moves it to done: pending falls to zero.
        ok(HttpMethod.POST, BILLING + "/bills/" + d.b5Id() + "/payments", c.owner(),
                "{\"mode\":\"CARD\",\"amountPaise\":10000}", 200);
        JsonNode paid = getJson(BILLING + "/today-summary", c.owner());
        assertThat(paid.get("collectedPaise").asLong()).isEqualTo(45000L);
        assertThat(paid.get("billsPending").asInt()).isZero();
    }

    // ---------------- roles, anonymous, tenant isolation, audit ----------------

    @Test
    void everyClinicRoleCanReadBothSummaries() throws Exception {
        Clinic clinic = newClinic(true);
        for (String role : ALL_ROLES) {
            mvc.perform(get(OPD).with(clinic.as(role))).andExpect(status().isOk());
            mvc.perform(get(BILLING + "/today-summary").with(clinic.as(role))).andExpect(status().isOk());
        }
    }

    @Test
    void anonymousCallsGet401AsProblemJson() throws Exception {
        problem(mvc.perform(get(OPD)), 401);
        problem(mvc.perform(get(BILLING + "/today-summary")), 401);
    }

    @Test
    void anotherClinicSeesNoneOfTheNumbersOrDoctors() throws Exception {
        Day d = scenario();
        Clinic other = newClinic(true);
        JsonNode s = getJson(OPD, other.owner());
        assertThat(s.get("patientsTotal").asInt()).isZero();
        assertThat(s.get("waitingCount").asInt()).isZero();
        assertThat(s.get("followUpsDue").asInt() + s.get("followUpsMissed").asInt() + s.get("followUpsReturned").asInt())
                .isZero();
        assertThat(s.get("myTasks")).isEmpty();
        for (JsonNode doc : s.get("doctors")) {
            assertThat(doc.get("doctorMembershipId").asText())
                    .isNotEqualTo(d.clinic().doctorMembershipId().toString())
                    .isNotEqualTo(d.doctorB().toString());
            assertThat(doc.get("nowWithDoctor").isNull()).isTrue();
            assertThat(doc.get("nextTokens")).isEmpty();
        }
        JsonNode money = getJson(BILLING + "/today-summary", other.owner());
        assertThat(money.get("collectedPaise").asLong()).isZero();
        assertThat(money.get("billsPending").asInt()).isZero();
    }

    @Test
    void readingTheSummariesWritesNoAuditRow() throws Exception {
        Day d = scenario();
        long before = BackdateSupport.auditRows(db(), d.clinic().tenantId());
        getJson(OPD, d.clinic().owner());
        getJson(BILLING + "/today-summary", d.clinic().owner());
        assertThat(BackdateSupport.auditRows(db(), d.clinic().tenantId())).isEqualTo(before);
    }
}
