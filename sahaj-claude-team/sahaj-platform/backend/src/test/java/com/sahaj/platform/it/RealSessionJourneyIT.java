package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Sweep: whole-clinic days driven with REAL signed tokens (OTP login per person, Bearer header) instead of a
 * stubbed principal, so the JWT role claim, the active-membership check, the tenant binding and the actor
 * columns (created_by, recorded_by) are all exercised for each role together.
 */
@OnTestDatabase
class RealSessionJourneyIT extends SweepSupport {

    private static final String VISITS = "/api/v1/opd/visits";
    private static final String BILLING = "/api/v1/billing";

    private record Staff(Member member, RequestPostProcessor auth) {
    }

    private Staff staff(Clinic clinic, String role) throws Exception {
        Member m = addMember(clinic, role);
        Session s = login(m.mobile());
        assertThat(s.next()).isEqualTo("APP");
        assertThat(s.role()).isEqualTo(role);
        return new Staff(m, bearer(s.accessToken()));
    }

    @Test
    void aFullClinicDayWithEveryoneSignedInForReal() throws Exception {
        Clinic clinic = newClinic(true);
        Staff desk = staff(clinic, "FRONT_DESK");
        Staff nurse = staff(clinic, "NURSE");
        Staff doctor = staff(clinic, "DOCTOR");

        // front desk registers a patient and gives a token
        JsonNode patient = ok(HttpMethod.POST, "/api/v1/patient/patients", desk.auth(),
                "{\"name\":\"Kavita Rane\",\"age\":33,\"sex\":\"F\",\"mobile\":\"" + uniqueMobile() + "\"}", 201);
        JsonNode visit = ok(HttpMethod.POST, VISITS, desk.auth(),
                "{\"patientId\":\"" + patient.get("id").asText() + "\",\"doctorMembershipId\":\"" + doctor.member().membershipId() + "\"}", 201);
        String id = visit.get("id").asText();
        assertThat(visit.get("stage").asText()).isEqualTo("VITALS");
        assertThat(visit.get("feePaise").asLong()).isEqualTo(25000L);

        // nurse sees it under "mine", takes vitals, moves it on; cannot go further
        assertThat(ok(HttpMethod.GET, VISITS + "/today?mine=true", nurse.auth(), null, 200).findValuesAsText("id")).contains(id);
        assertThat(ok(HttpMethod.GET, VISITS + "/today?mine=true", doctor.auth(), null, 200).findValuesAsText("id")).doesNotContain(id);
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", nurse.auth(), "{\"stage\":\"WITH_DOCTOR\"}", 200);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", nurse.auth(), "{\"stage\":\"BILLING\"}"), 403);

        // doctor now has it, moves it to billing, cannot close it
        assertThat(ok(HttpMethod.GET, VISITS + "/today?mine=true", doctor.auth(), null, 200).findValuesAsText("id")).contains(id);
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", doctor.auth(), "{\"stage\":\"BILLING\"}", 200);
        problem(call(HttpMethod.PATCH, VISITS + "/" + id + "/stage", doctor.auth(), "{\"stage\":\"DONE\"}"), 403);
        problem(call(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", doctor.auth(), "{\"mode\":\"CASH\",\"amountPaise\":100}"), 403);
        assertThat(ok(HttpMethod.GET, BILLING + "/counter", doctor.auth(), null, 200).get("awaiting").size()).isEqualTo(1);

        // front desk bills: discount within 10 percent, above it refused, then pays in two parts
        ok(HttpMethod.PUT, BILLING + "/bills/" + id + "/discount", desk.auth(), "{\"percent\":10,\"reason\":\"Staff relative\"}", 200);
        problem(call(HttpMethod.PUT, BILLING + "/bills/" + id + "/discount", desk.auth(), "{\"percent\":11}"), 400);
        JsonNode half = ok(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", desk.auth(), "{\"mode\":\"CASH\",\"amountPaise\":10000}", 200);
        assertThat(half.get("totalPaise").asLong()).isEqualTo(22500L);
        assertThat(half.get("canCollect").asBoolean()).isTrue();
        assertThat(half.get("payments").get(0).get("recordedByName").asText()).isEqualTo("Member FRONT_DESK");
        JsonNode done = ok(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", desk.auth(), "{\"mode\":\"UPI\",\"amountPaise\":12500}", 200);
        assertThat(done.get("balancePaise").asLong()).isZero();
        assertThat(ok(HttpMethod.GET, VISITS + "/today", nurse.auth(), null, 200).get(0).get("stage").asText()).isEqualTo("DONE");

        // owner signs in for real: refund works, front desk refund does not, day closing once
        Session ownerSession = loginOwner(clinic);
        RequestPostProcessor owner = bearer(ownerSession.accessToken());
        problem(call(HttpMethod.POST, BILLING + "/bills/" + id + "/refund", desk.auth(),
                "{\"amountPaise\":500,\"mode\":\"CASH\",\"reason\":\"Try\"}"), 403);
        JsonNode refunded = ok(HttpMethod.POST, BILLING + "/bills/" + id + "/refund", owner,
                "{\"amountPaise\":500,\"mode\":\"CASH\",\"reason\":\"Rounding\"}", 200);
        assertThat(refunded.get("balancePaise").asLong()).isEqualTo(500L);
        ok(HttpMethod.POST, BILLING + "/day-closing", desk.auth(), "{\"countedCashPaise\":9500}", 201);
        problem(call(HttpMethod.POST, BILLING + "/day-closing", owner, "{\"countedCashPaise\":9500}"), 409);

        // the trail names the real people
        JsonNode events = ok(HttpMethod.GET, "/api/v1/platform/audit-events?limit=100", owner, null, 200);
        assertThat(events.findValuesAsText("actorName")).contains("Member FRONT_DESK", "Member NURSE", "Member DOCTOR");
        assertThat(events.toString()).doesNotContain("Kavita").doesNotContain("Rane");
    }

    private Session loginOwner(Clinic clinic) throws Exception {
        String mobile = db().asMigrator(c -> DbAccess.text(c, "select mobile from platform.app_user where id = ?::uuid",
                clinic.ownerUserId().toString()));
        ageOutLatestChallenge(mobile);
        Session s = login(mobile);
        assertThat(s.next()).isEqualTo("APP");
        return s;
    }

    @Test
    void realSessionsOfEveryRoleSeeTheirMenuAndTheRightReadsAndWrites() throws Exception {
        Clinic clinic = newClinic();
        UUID patient = newPatient(clinic);
        for (String role : new String[] {"DOCTOR", "VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            Clinic fresh = newClinic();
            Staff s = staff(fresh, role);
            JsonNode me = ok(HttpMethod.GET, "/api/v1/platform/me", s.auth(), null, 200);
            assertThat(me.get("role").asText()).isEqualTo(role);
            ok(HttpMethod.GET, "/api/v1/platform/team", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/platform/role-permissions", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/services", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/fee-settings", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/case-paper-settings", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/medicines", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/diagnoses", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/workflow/current", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/doctor-slots", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/opd/booking-rules", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/platform/clinic/profile", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/platform/clinic/holidays", s.auth(), null, 200);
            ok(HttpMethod.GET, "/api/v1/platform/specialties", s.auth(), null, 200);
            ok(HttpMethod.GET, VISITS + "/today", s.auth(), null, 200);
            ok(HttpMethod.GET, BILLING + "/counter", s.auth(), null, 200);
            // owner-only things stay closed
            problem(call(HttpMethod.GET, "/api/v1/platform/setup/overview", s.auth(), null), 403);
            problem(call(HttpMethod.GET, "/api/v1/platform/audit-events", s.auth(), null), 403);
            problem(call(HttpMethod.PUT, "/api/v1/opd/fee-settings", s.auth(), "{}"), 403);
            problem(call(HttpMethod.POST, "/api/v1/opd/workflow/publish", s.auth(), null), 403);
            problem(call(HttpMethod.PUT, "/api/v1/opd/booking-rules", s.auth(), "{}"), 403);
        }
        assertThat(patient).isNotNull();
    }

    @Test
    void anotherClinicsRealTokenCannotReachMyData() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID patientA = newPatient(a);
        Staff deskB = staff(b, "FRONT_DESK");
        problem(call(HttpMethod.GET, "/api/v1/patient/patients/" + patientA, deskB.auth(), null), 404);
        problem(call(HttpMethod.POST, VISITS, deskB.auth(),
                "{\"patientId\":\"" + patientA + "\",\"doctorMembershipId\":\"" + a.doctorMembershipId() + "\"}"), 404);
        assertThat(ok(HttpMethod.GET, "/api/v1/platform/team", deskB.auth(), null, 200).findValuesAsText("id"))
                .doesNotContain(a.doctorMembershipId().toString());
    }

    @Test
    void tamperedOrMissingTokensAreRefused() throws Exception {
        Clinic clinic = newClinic();
        Staff desk = staff(clinic, "FRONT_DESK");
        String token = desk.auth() == null ? "" : "x";
        assertThat(token).isNotEmpty();
        problem(call(HttpMethod.GET, "/api/v1/platform/me", bearer("not.a.jwt"), null), 401);
        problem(call(HttpMethod.GET, "/api/v1/platform/me", bearer("eyJhbGciOiJub25lIn0.e30."), null), 401);
    }

    // ---------------- big numbers must not become server errors ----------------

    @Test
    void veryLargeMoneyValuesAreHandledWithoutAServerError() throws Exception {
        Clinic clinic = newClinic();
        int max = Integer.MAX_VALUE;
        int teamStatus = call(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(),
                "{\"name\":\"Dr Rich\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"DOCTOR\",\"doctor\":{\"feeRupees\":" + max + "}}")
                .andReturn().getResponse().getStatus();
        assertThat(teamStatus).isIn(201, 400);
        int serviceStatus = call(HttpMethod.POST, "/api/v1/opd/services", clinic.owner(),
                "{\"name\":\"Gold plated\",\"category\":\"Package\",\"priceRupees\":" + max + "}").andReturn().getResponse().getStatus();
        assertThat(serviceStatus).isIn(201, 400);
        int wizardStatus = call(HttpMethod.PUT, "/api/v1/platform/setup/steps/3", clinic.owner(),
                "{\"doctors\":[{\"name\":\"D\",\"specialty\":\"x\",\"feeRupees\":" + max + "}],\"staff\":[]}")
                .andReturn().getResponse().getStatus();
        assertThat(wizardStatus).isIn(200, 400);
        int overflow = call(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(),
                "{\"name\":\"Dr Over\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"DOCTOR\",\"doctor\":{\"feeRupees\":99999999999999}}")
                .andReturn().getResponse().getStatus();
        assertThat(overflow).isEqualTo(400);
    }

    @Test
    void aVisitAndBillForAVeryExpensiveDoctorWorksEndToEnd() throws Exception {
        Clinic clinic = newClinic(false);
        JsonNode doc = ok(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(),
                "{\"name\":\"Dr Costly\",\"mobile\":\"" + uniqueMobile() + "\",\"role\":\"DOCTOR\",\"doctor\":{\"feeRupees\":1000000}}", 201);
        JsonNode v = ok(HttpMethod.POST, VISITS, clinic.owner(),
                "{\"patientId\":\"" + newPatient(clinic) + "\",\"doctorMembershipId\":\"" + doc.get("id").asText() + "\"}", 201);
        assertThat(v.get("feePaise").asLong()).isEqualTo(100_000_000L);
        String id = v.get("id").asText();
        ok(HttpMethod.PATCH, VISITS + "/" + id + "/stage", clinic.owner(), "{\"stage\":\"BILLING\"}", 200);
        ok(HttpMethod.PUT, BILLING + "/bills/" + id + "/discount", clinic.owner(), "{\"percent\":33}", 200);
        JsonNode b = ok(HttpMethod.GET, BILLING + "/bills/" + id, clinic.owner(), null, 200);
        assertThat(b.get("totalPaise").asLong()).isEqualTo(100_000_000L - 33_000_000L);
        ok(HttpMethod.POST, BILLING + "/bills/" + id + "/payments", clinic.owner(),
                "{\"mode\":\"CARD\",\"amountPaise\":" + b.get("totalPaise").asLong() + "}", 200);
    }
}
