package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-33 review fix R8 (ADR-007, security review I7): {@code TeamMemberPort.findActiveByUserId} reads one membership row
 * of the clinic and the user (it no longer loads the whole team and filters). The behaviour must not change: the answer is
 * the membership of <em>this</em> clinic (a person who belongs to two clinics has a role in each), a changed role and a
 * deactivated or removed membership are applied at once, in a clinic with many members only the right member is affected.
 * It is seen through the Safety actions, which re-read the role from the membership in every transaction. The statement
 * itself (one row) is the unit test of the platform module; here the observable behaviour is proved on the real database.
 * Test plan: docs/test-plans/SAH-33.md.
 */
@OnTestDatabase
class MembershipLookupIT extends SafetyTestSupport {

    /** Adds a person with the given mobile to the clinic through the real team endpoint; returns the user id. */
    private UUID addWithMobile(Clinic clinic, String role, String mobile, String name) throws Exception {
        String doctor = role.contains("DOCTOR")
                ? ",\"doctor\":{\"specialty\":\"General physician\",\"feeRupees\":250,\"degree\":\"MBBS\",\"registrationNo\":\"R1\"}"
                : "";
        JsonNode created = ok(HttpMethod.POST, "/api/v1/platform/team", clinic.owner(),
                "{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\",\"role\":\"" + role + "\"" + doctor + "}", 201);
        return UUID.fromString(values(clinic, "select user_id::text from platform.membership where id = ?::uuid",
                created.get("id").asText()).get(0));
    }

    private record Ward(Clinic clinic, String esc, String other) {
    }

    /** A hospital with two open escalations on two admitted patients. */
    private Ward ward() throws Exception {
        Clinic clinic = hospital();
        String a = raise(clinic, admitOne(clinic), medium3());
        String b = raise(clinic, admitOne(clinic), medium3());
        return new Ward(clinic, a, b);
    }

    @Test
    void aPersonInTwoClinicsHasTheRoleOfTheClinicOfTheRequestAndNeverTheRoleOfTheOther() throws Exception {
        Ward a = ward();
        Ward b = ward();
        String mobile = uniqueMobile();
        UUID user = addWithMobile(a.clinic(), "NURSE", mobile, "Shared Person");
        assertThat(addWithMobile(b.clinic(), "DOCTOR", mobile, "Shared Person")).as("the same person in both clinics").isEqualTo(user);

        // each clinic: the role of that clinic works
        assertThat(callOk(tokenOf(a.clinic(), user, "NURSE"), a.esc(), 0, 0).get("replayed").asBoolean()).isFalse();
        assertThat(ackOk(tokenOf(b.clinic(), user, "DOCTOR"), b.esc(), 0).get("escalation").get("status").asText()).isEqualTo("ACK");

        // a token that claims the other clinic's role is refused by the membership of the clinic of the request
        Side sideA = side(a.clinic());
        Side sideB = side(b.clinic());
        assertType(problem(ack(tokenOf(a.clinic(), user, "DOCTOR"), a.other(), 0), 403), "forbidden");
        assertType(problem(respond(tokenOf(a.clinic(), user, "DOCTOR"), a.other(), 0, "NO_CHANGE", "Plan"), 403), "forbidden");
        assertType(problem(callSenior(tokenOf(b.clinic(), user, "NURSE"), b.other(), 0, 0), 403), "forbidden");
        assertThat(side(a.clinic())).isEqualTo(sideA);
        assertThat(side(b.clinic())).isEqualTo(sideB);
    }

    @Test
    void aRoleChangeOrDeactivationInOneClinicIsAppliedThereAtOnceAndNotInTheOther() throws Exception {
        Ward a = ward();
        Ward b = ward();
        String mobile = uniqueMobile();
        UUID user = addWithMobile(a.clinic(), "NURSE", mobile, "Shared Nurse");
        addWithMobile(b.clinic(), "NURSE", mobile, "Shared Nurse");
        var inA = tokenOf(a.clinic(), user, "NURSE");
        var inB = tokenOf(b.clinic(), user, "NURSE");
        assertThat(callOk(inA, a.esc(), 0, 0).get("replayed").asBoolean()).isFalse();
        assertThat(callOk(inB, b.esc(), 0, 0).get("replayed").asBoolean()).isFalse();

        setMembershipRole(a.clinic(), user, "FRONT_DESK");
        assertType(problem(callSenior(inA, a.other(), 0, 0), 403), "forbidden");
        assertThat(callOk(inB, b.other(), 0, 0).get("replayed").asBoolean()).as("the other clinic is untouched").isFalse();

        setMembershipRole(a.clinic(), user, "NURSE");
        assertThat(callOk(inA, a.other(), 0, 0).get("replayed").asBoolean()).as("back to nurse, applied at once").isFalse();

        setMembershipStatus(b.clinic().tenantId(), user, "INACTIVE");
        Side sideB = side(b.clinic());
        assertType(problem(callSenior(inB, b.esc(), 0, 1), 403), "forbidden");
        assertThat(side(b.clinic())).isEqualTo(sideB);
        assertThat(callSenior(inA, a.esc(), 0, 1).andReturn().getResponse().getStatus()).as("still active in A").isEqualTo(200);
        setMembershipStatus(b.clinic().tenantId(), user, "ACTIVE");
        assertThat(callSenior(inB, b.esc(), 0, 1).andReturn().getResponse().getStatus()).as("active again").isEqualTo(200);
    }

    @Test
    void inAClinicWithManyMembershipRowsOnlyTheChangedMemberIsAffected() throws Exception {
        // The free plan allows five active users, but removed and inactive memberships stay as rows (past entries keep the
        // name), so a clinic can hold many rows: twelve people are added and removed first, then three nurses stay.
        Ward w = ward();
        List<Staff> gone = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Staff former = staff(w.clinic(), "NURSE");
            setMembershipStatus(w.clinic().tenantId(), former.userId(), i % 2 == 0 ? "REMOVED" : "INACTIVE");
            gone.add(former);
        }
        Staff a = staff(w.clinic(), "NURSE");
        Staff b = staff(w.clinic(), "NURSE");
        Staff c = staff(w.clinic(), "NURSE");
        assertThat(count(w.clinic(), "select count(*) from platform.membership")).isGreaterThanOrEqualTo(17);

        // every former member is refused at once, the three active nurses work
        Side side = side(w.clinic());
        for (Staff former : gone) {
            assertThat(callSenior(former.auth(), w.esc(), 0, 0).andReturn().getResponse().getStatus()).as(former.name()).isEqualTo(403);
        }
        assertThat(side(w.clinic())).as("nothing written for a former member").isEqualTo(side);
        for (Staff n : new Staff[] {a, b, c}) {
            assertThat(callSenior(n.auth(), w.esc(), 0, 0).andReturn().getResponse().getStatus()).as(n.name()).isEqualTo(200);
        }

        // one is demoted, one is deactivated: only those two are refused, the third still works
        setMembershipRole(w.clinic(), b.userId(), "BILLING");
        setMembershipStatus(w.clinic().tenantId(), c.userId(), "INACTIVE");
        assertThat(callSenior(b.auth(), w.other(), 0, 0).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(callSenior(c.auth(), w.other(), 0, 0).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(callSenior(a.auth(), w.other(), 0, 0).andReturn().getResponse().getStatus()).isEqualTo(200);

        // and back: applied at once
        setMembershipRole(w.clinic(), b.userId(), "NURSE");
        setMembershipStatus(w.clinic().tenantId(), c.userId(), "ACTIVE");
        assertThat(callSenior(b.auth(), w.other(), 0, 0).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(callSenior(c.auth(), w.other(), 0, 0).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
