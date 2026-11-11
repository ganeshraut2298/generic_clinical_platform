package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SAH-34 story 34A (the data part: the owner's ward assignments and the clinic switch "Nurses see only their wards") on the
 * real {@code sahaj_test} database. PUT sets the whole set of nurses of one ward, ended assignments stay as history, the audit
 * holds ids only, the switch refuses "on" while an active nurse has no ward (on every request, also a repeated one), the three
 * bodies refuse a field they do not know with 400 malformed-request without echoing it, and another clinic's data is invisible.
 * The 34A screen is not built (it is not in the mockup); these tests call the endpoints the way the screen will. Test plan:
 * docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
class WardAssignmentIT extends ScopeSupport {

    private static final String[] NON_OWNER = {"DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "VISITING_DOCTOR", "PHARMACIST",
        "COUNSELLOR"};

    private JsonNode get(Clinic clinic) throws Exception {
        return ok(HttpMethod.GET, WARD_ASSIGNMENTS, clinic.owner(), null, 200);
    }

    private static JsonNode wardRow(JsonNode answer, String wardId) {
        for (JsonNode w : answer.get("wards")) {
            if (wardId.equals(w.get("wardId").asText())) {
                return w;
            }
        }
        throw new AssertionError("no ward " + wardId);
    }

    private static JsonNode nurseRow(JsonNode answer, UUID membershipId) {
        for (JsonNode n : answer.get("nurses")) {
            if (membershipId.toString().equals(n.get("membershipId").asText())) {
                return n;
            }
        }
        return null;
    }

    private long live(Clinic clinic) throws Exception {
        return count(clinic, "select count(*) from ipd.ward_assignment where removed_at is null");
    }

    @Test
    void theOwnerReadsEveryWardEveryActiveNurseAndTheSwitchWhichStartsOff() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        String b = newWard(clinic, "Surgical ward");
        Pn n1 = newPn(clinic, "NURSE");
        Pn n2 = newPn(clinic, "NURSE");
        newPn(clinic, "DOCTOR");
        JsonNode start = get(clinic);
        assertThat(start.get("wardScope").asBoolean()).as("every clinic starts with the switch off").isFalse();
        assertThat(start.get("wards")).hasSize(2);
        assertThat(start.get("nurses")).as("active NURSE members only, no doctor").hasSize(2);
        assertThat(start.get("nursesWithoutWard").asInt()).isEqualTo(2);
        assertThat(start.get("wards").get(0).get("wardId").asText()).as("order of the layout").isEqualTo(a);
        assign(clinic, a, n1);
        assign(clinic, b, n1, n2);
        JsonNode now = get(clinic);
        assertThat(now.get("nursesWithoutWard").asInt()).isZero();
        assertThat(wardRow(now, a).get("nurseMembershipIds")).hasSize(1);
        assertThat(wardRow(now, b).get("nurseMembershipIds")).hasSize(2);
        assertThat(nurseRow(now, n1.membershipId()).get("wardIds")).hasSize(2);
        assertThat(nurseRow(now, n2.membershipId()).get("wardIds")).hasSize(1);
        assertThat(nurseRow(now, n1.membershipId()).get("name").asText()).isNotBlank();
    }

    @Test
    void putSetsTheWholeSetEndedNursesStayAsHistoryAndTheSameSetWritesNothing() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn n1 = newPn(clinic, "NURSE");
        Pn n2 = newPn(clinic, "NURSE");
        Pn n3 = newPn(clinic, "NURSE");
        assign(clinic, a, n1, n2);
        assertThat(live(clinic)).isEqualTo(2);
        long auditBefore = audits(clinic, "IPD_WARD_ASSIGNMENT_CHANGED");
        assertThat(auditBefore).isOne();

        // the same set again, in another order: 200, nothing written, no audit row
        String before = values(clinic, "select coalesce(string_agg(id::text || removed_at, ',' order by id), '') from ipd.ward_assignment").get(0);
        assign(clinic, a, n2, n1);
        assertThat(values(clinic, "select coalesce(string_agg(id::text || removed_at, ',' order by id), '') from ipd.ward_assignment").get(0))
                .isEqualTo(before);
        assertThat(audits(clinic, "IPD_WARD_ASSIGNMENT_CHANGED")).isEqualTo(auditBefore);

        // n1 leaves, n3 comes: n1's row is ended (not deleted), n2 keeps its original row
        String n2Row = values(clinic, "select id::text from ipd.ward_assignment where membership_id = ?::uuid", n2.membershipId().toString()).get(0);
        JsonNode answer = assign(clinic, a, n2, n3);
        assertThat(wardRow(answer, a).get("nurseMembershipIds")).hasSize(2);
        assertThat(live(clinic)).isEqualTo(2);
        assertThat(count(clinic, "select count(*) from ipd.ward_assignment")).as("history kept").isEqualTo(3);
        assertThat(values(clinic, "select id::text from ipd.ward_assignment where membership_id = ?::uuid and removed_at is null",
                n2.membershipId().toString())).containsExactly(n2Row);
        List<String> ended = values(clinic, "select removed_by::text || '|' || (removed_at is not null) || '|' || (removed_at >= assigned_at) "
                + "from ipd.ward_assignment where membership_id = ?::uuid", n1.membershipId().toString());
        assertThat(ended).containsExactly(clinic.ownerUserId() + "|true|true");

        // the whole set is replaceable, and empty removes every nurse
        JsonNode empty = ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + a, clinic.owner(), "{\"membershipIds\":[]}", 200);
        assertThat(wardRow(empty, a).get("nurseMembershipIds")).isEmpty();
        assertThat(live(clinic)).isZero();
        assertThat(empty.get("nursesWithoutWard").asInt()).isEqualTo(3);

        // a nurse who came back gets a new live row (the ended one stays)
        assign(clinic, a, n1);
        assertThat(count(clinic, "select count(*) from ipd.ward_assignment where membership_id = ?::uuid", n1.membershipId())).isEqualTo(2);
        assertThat(live(clinic)).isOne();
    }

    @Test
    void theAuditHoldsIdsOnlyNoNameAndTheEntityIsTheWard() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn n1 = newPn(clinic, "NURSE");
        String name = nameOf(clinic, n1.userId());
        assign(clinic, a, n1);
        switchScope(clinic, true);
        List<String[]> rows = audit(clinic, "IPD_WARD_ASSIGNMENT_CHANGED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("IPD_WARD_ASSIGNMENT");
        assertThat(rows.get(0)[1]).isEqualTo(a);
        String text = String.join(" ", rows.get(0)[2] == null ? "" : rows.get(0)[2], rows.get(0)[3]);
        assertThat(text).contains(n1.membershipId().toString()).doesNotContain(name).doesNotContain("General ward");
        List<String[]> scope = audit(clinic, "IPD_WARD_SCOPE_CHANGED");
        assertThat(scope).hasSize(1);
        assertThat(scope.get(0)[0]).isEqualTo("IPD_CONFIG");
        assertThat(scope.get(0)[1]).as("entity id null, as the other IPD settings").isNull();
        assertThat(scope.get(0)[2] + scope.get(0)[3]).doesNotContain(name);
        assertThat(allAuditText(clinic)).doesNotContain(name);
    }

    @Test
    void aMembershipThatIsNotAnActiveNurseIsRefusedWithTheFieldMessageAndNothingIsWritten() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn doctor = newPn(clinic, "DOCTOR");
        Pn gone = newPn(clinic, "NURSE");
        Pn ok = newPn(clinic, "NURSE");
        setMembershipStatus(clinic.tenantId(), gone.userId(), "INACTIVE");
        String written = written(clinic);
        for (String body : new String[] {idsJson(doctor.membershipId()), idsJson(gone.membershipId()), idsJson(UUID.randomUUID()),
            idsJson(ok.membershipId(), UUID.randomUUID()), idsJson(ok.membershipId(), ok.membershipId())}) {
            JsonNode p = problem(assignCall(clinic.owner(), a, body), 400);
            assertType(p, "validation");
            assertThat(fieldNames(p)).containsExactly("membershipIds");
            assertThat(fieldMessage(p, "membershipIds")).isEqualTo(CHOOSE_NURSES_TEXT);
        }
        UUID[] many = new UUID[101];
        for (int i = 0; i < many.length; i++) {
            many[i] = UUID.randomUUID();
        }
        JsonNode p = problem(assignCall(clinic.owner(), a, idsJson(many)), 400);
        assertThat(fieldMessage(p, "membershipIds")).isEqualTo(CHOOSE_NURSES_TEXT);
        assertThat(written(clinic)).isEqualTo(written);
        assertThat(rows(clinic, "ward_assignment")).isZero();
    }

    @Test
    void anUnknownWardAnotherClinicsWardAndABadIdAreAnswered404And400() throws Exception {
        Clinic clinic = hospital();
        Clinic other = hospital();
        Pn nurse = newPn(clinic, "NURSE");
        String otherWard = wardIdByName(other, "General ward");
        problem(assignCall(clinic.owner(), UUID.randomUUID(), idsJson(nurse.membershipId())), 404);
        problem(assignCall(clinic.owner(), otherWard, idsJson(nurse.membershipId())), 404);
        JsonNode bad = problem(assignCall(clinic.owner(), "not-a-uuid", idsJson(nurse.membershipId())), 400);
        assertType(bad, "bad-request");
        assertThat(rows(clinic, "ward_assignment")).isZero();
        assertThat(rows(other, "ward_assignment")).isZero();
    }

    @Test
    void onlyTheOwnerReadsOrChangesWardsAndTheSwitchEveryoneElseGets403() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn nurse = newPn(clinic, "NURSE");
        String written = written(clinic);
        for (String role : NON_OWNER) {
            forbidden(call(HttpMethod.GET, WARD_ASSIGNMENTS, clinic.as(role), null));
            forbidden(assignCall(clinic.as(role), a, idsJson(nurse.membershipId())));
            forbidden(switchCall(clinic.as(role), "{\"on\":true}"));
        }
        // a real nurse of the clinic: her own token, her own membership
        forbidden(assignCall(nurse.auth(), a, idsJson(nurse.membershipId())));
        forbidden(switchCall(nurse.auth(), "{\"on\":true}"));
        assertThat(written(clinic)).isEqualTo(written);
        assertThat(rows(clinic, "ward_assignment")).isZero();
        assertThat(get(clinic).get("wardScope").asBoolean()).isFalse();
        // no session at all
        call(HttpMethod.GET, WARD_ASSIGNMENTS, noSession(), null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void anOwnerTokenWhoseMembershipIsNoLongerOwnerIsRefused() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn nurse = newPn(clinic, "NURSE");
        Pn demoted = newPn(clinic, "DOCTOR");
        var ownerClaim = tokenOf(clinic, demoted.userId(), "OWNER");
        forbidden(assignCall(ownerClaim, a, idsJson(nurse.membershipId())));
        forbidden(switchCall(ownerClaim, "{\"on\":false}"));
        assertThat(rows(clinic, "ward_assignment")).isZero();
    }

    @Test
    void theSwitchIsRefusedWhileAnActiveNurseHasNoWardOnEveryRequestAlsoARepeatedOneAndOffIsNeverRefused() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn n1 = newPn(clinic, "NURSE");
        Pn n2 = newPn(clinic, "NURSE");
        assign(clinic, a, n1);
        String written = written(clinic);
        JsonNode p = problem(switchCall(clinic.owner(), "{\"on\":true}"), 409);
        assertType(p, "nurse-without-ward");
        assertThat(p.get("detail").asText()).isEqualTo(NURSE_WITHOUT_WARD_TEXT);
        assertThat(written(clinic)).as("a refused switch writes nothing").isEqualTo(written);
        assertThat(get(clinic).get("wardScope").asBoolean()).isFalse();

        assign(clinic, a, n1, n2);
        JsonNode on = switchScope(clinic, true);
        assertThat(on.get("wardScope").asBoolean()).isTrue();
        assertThat(audits(clinic, "IPD_WARD_SCOPE_CHANGED")).isOne();
        // the same "on" again with everyone assigned: 200, nothing written, no second audit row
        switchScope(clinic, true);
        assertThat(audits(clinic, "IPD_WARD_SCOPE_CHANGED")).as("unchanged: no audit row").isOne();

        // the owner removes the last nurse of the ward while the switch is on (allowed), then repeats "on"
        ok(HttpMethod.PUT, WARD_ASSIGNMENTS + "/" + a, clinic.owner(), "{\"membershipIds\":[]}", 200);
        assertThat(get(clinic).get("nursesWithoutWard").asInt()).isEqualTo(2);
        String beforeRepeat = written(clinic);
        JsonNode repeat = problem(switchCall(clinic.owner(), "{\"on\":true}"), 409);
        assertType(repeat, "nurse-without-ward");
        assertThat(written(clinic)).isEqualTo(beforeRepeat);
        assertThat(get(clinic).get("wardScope").asBoolean()).as("the switch stays on").isTrue();
        assertThat(audits(clinic, "IPD_WARD_SCOPE_CHANGED")).isOne();

        // off is never refused by it
        JsonNode off = switchScope(clinic, false);
        assertThat(off.get("wardScope").asBoolean()).isFalse();
        assertThat(audits(clinic, "IPD_WARD_SCOPE_CHANGED")).isEqualTo(2);
        switchScope(clinic, false);
        assertThat(audits(clinic, "IPD_WARD_SCOPE_CHANGED")).isEqualTo(2);
    }

    @Test
    void aClinicWithNoNurseCanSwitchOnAndAnInactiveNurseDoesNotBlockIt() throws Exception {
        Clinic clinic = hospital();
        assertThat(switchScope(clinic, true).get("wardScope").asBoolean()).isTrue();
        switchScope(clinic, false);
        Pn gone = newPn(clinic, "NURSE");
        setMembershipStatus(clinic.tenantId(), gone.userId(), "INACTIVE");
        assertThat(get(clinic).get("nurses")).as("an inactive nurse is not listed").isEmpty();
        assertThat(switchScope(clinic, true).get("wardScope").asBoolean()).isTrue();
    }

    @Test
    void anInvalidOnIsA400ValidationAndEachOfTheThreeBodiesRefusesAnUnknownFieldAsMalformedRequestWithoutEchoingIt() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Adm adm = admitOne(clinic);
        Pn nurse = newPn(clinic, "NURSE");
        String written = written(clinic);

        for (String bad : new String[] {"{}", "{\"on\":null}", "{\"on\":\"yes\"}", "{\"on\":1}"}) {
            JsonNode p = problem(switchCall(clinic.owner(), bad), 400);
            assertThat(p.get("status").asInt()).isEqualTo(400);
        }
        String[][] bodies = {
            {WARD_SCOPE, "PUT", "{\"on\":true,\"zzUnknownScopeField\":1}", "zzUnknownScopeField"},
            {WARD_ASSIGNMENTS + "/" + a, "PUT", "{\"membershipIds\":[],\"zzUnknownWardField\":true}", "zzUnknownWardField"},
            {EA, "POST", "{\"admissionId\":\"" + adm.id() + "\",\"reasonKey\":\"DETERIORATING\",\"confirmed\":true,"
                    + "\"durationMinutes\":240}", "durationMinutes"},
            {EA, "POST", "{\"admissionId\":\"" + adm.id() + "\",\"reasonKey\":\"DETERIORATING\",\"confirmed\":true,"
                    + "\"expiresAt\":\"2099-01-01T00:00:00Z\"}", "expiresAt"},
        };
        for (String[] b : bodies) {
            // the owner for the two ward bodies; a nurse for the emergency body (the body is read before the role of the endpoint's service)
            var auth = b[0].equals(EA) ? nurse.auth() : clinic.owner();
            var result = call(HttpMethod.valueOf(b[1]), b[0], auth, b[2]).andReturn().getResponse();
            assertThat(result.getStatus()).as(b[0] + " " + result.getContentAsString()).isEqualTo(400);
            JsonNode p = objectMapper.readTree(result.getContentAsString());
            assertType(p, "malformed-request");
            assertThat(p.get("title").asText()).isEqualTo(MALFORMED_TITLE);
            assertThat(p.get("detail").asText()).isEqualTo(MALFORMED_DETAIL);
            assertThat(p.has("fieldErrors")).as("no fieldErrors").isFalse();
            assertThat(result.getContentAsString()).as("the field name is not echoed").doesNotContain(b[3]);
        }
        // a body that is not a JSON object, or is missing, is the same answer
        for (String[] target : new String[][] {{"PUT", WARD_SCOPE}, {"PUT", WARD_ASSIGNMENTS + "/" + a}}) {
            for (String notObject : new String[] {"[]", "\"text\"", "12", "{not json"}) {
                JsonNode p = problem(call(HttpMethod.valueOf(target[0]), target[1], clinic.owner(), notObject), 400);
                assertType(p, "malformed-request");
            }
        }
        assertThat(written(clinic)).isEqualTo(written);
    }

    @Test
    void anotherClinicsAssignmentsAndSwitchAreInvisibleAndUntouched() throws Exception {
        Hosp one = hosp();
        Hosp two = hosp();
        JsonNode seenByTwo = get(two.clinic());
        assertThat(seenByTwo.get("wards")).hasSize(2);
        assertThat(nurseRow(seenByTwo, one.nurseA().membershipId())).as("another clinic's nurse is not listed").isNull();
        for (JsonNode w : seenByTwo.get("wards")) {
            for (JsonNode id : w.get("nurseMembershipIds")) {
                assertThat(id.asText()).isNotEqualTo(one.nurseA().membershipId().toString()).isNotEqualTo(one.nurseB().membershipId().toString());
            }
        }
        // row level security: clinic two sees only its own rows, and cannot write one for clinic one's ward
        assertThat(count(two.clinic(), "select count(*) from ipd.ward_assignment")).isEqualTo(2);
        assertThat(count(one.clinic(), "select count(*) from ipd.ward_assignment")).isEqualTo(2);
        assertThat(count(two.clinic(), "select count(*) from ipd.ward_assignment where ward_id = ?::uuid", one.wardA())).isZero();
        SqlErr crossTenant = appErr(two.clinic(), "insert into ipd.ward_assignment (ward_id, membership_id, assigned_by) "
                + "values (?::uuid, ?::uuid, ?::uuid)", one.wardA(), UUID.randomUUID(), two.clinic().ownerUserId());
        assertThat(crossTenant.state()).as("composite key to the ward of the same clinic: " + crossTenant.message()).isEqualTo("23503");
        // clinic two's owner cannot assign clinic one's nurse to its own ward (the nurse is not a member there): 400
        problem(assignCall(two.clinic().owner(), two.wardA(), idsJson(one.nurseA().membershipId())), 400);
        // the switch of one clinic does not follow the other
        switchScope(two.clinic(), false);
        assertThat(get(one.clinic()).get("wardScope").asBoolean()).isTrue();
    }

    @Test
    void whenIpdIsReadOnlyTheWritesAreRefused409AndTheReadIsAllowed() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        Pn nurse = newPn(clinic, "NURSE");
        assign(clinic, a, nurse);
        expireTrial(clinic);
        assertThat(get(clinic).get("wards")).hasSize(1);
        JsonNode p = problem(assignCall(clinic.owner(), a, "{\"membershipIds\":[]}"), 409);
        assertType(p, "ipd-readonly");
        JsonNode s = problem(switchCall(clinic.owner(), "{\"on\":true}"), 409);
        assertType(s, "ipd-readonly");
        assertThat(live(clinic)).isOne();
    }

    @Test
    void theNewLogLinesAndAuditHoldNoNameAndTheResponseOfAnOwnerReadHoldsNoPatient() throws Exception {
        Hosp h = hosp();
        String name = nameOf(h.clinic(), h.nurseA().userId());
        String raw = call(HttpMethod.GET, WARD_ASSIGNMENTS, h.clinic().owner(), null).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(h.inA().name()).doesNotContain(h.inA().mobile()).doesNotContain("uhid");
        assertThat(raw).contains(name);
    }
}
