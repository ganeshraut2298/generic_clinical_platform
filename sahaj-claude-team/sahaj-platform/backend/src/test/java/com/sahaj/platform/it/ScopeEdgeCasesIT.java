package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;

/**
 * SAH-34 cases that sit between the other suites, on the real {@code sahaj_test} database: IPD Off, a grantee whose membership
 * ends, the scope that cannot be built (the request fails, never "all"), a clinic that was never switched on, two nurses in one
 * ward and one nurse in two wards, the visiting doctor who keeps 403, the record-access rows of a scoped read, and what the
 * guard writes to the application log (ids only, never a name, mobile or UHID). Test plan: docs/test-plans/SAH-34.md.
 */
@OnTestDatabase
@WithPinPepper
@ExtendWith(OutputCaptureExtension.class)
class ScopeEdgeCasesIT extends ScopeSupport {

    @Test
    void ipdOffTheNewEndpointsAre403ModuleOffForTheOwnerAndTheNurseAndNothingIsWritten() throws Exception {
        Clinic clinic = newClinic(); // IPD is Off until the trial starts
        Pn nurse = newPn(clinic, "NURSE");
        String id = UUID.randomUUID().toString();
        assertType(problem(call(HttpMethod.GET, EA, clinic.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, EA + "/" + id + "/review", clinic.owner(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, WARD_ASSIGNMENTS, clinic.owner(), null), 403), "module-off");
        assertType(problem(switchCall(clinic.owner(), "{\"on\":true}"), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, EA_PICKER, nurse.auth(), null), 403), "module-off");
        assertType(problem(call(HttpMethod.POST, EA, nurse.auth(), eaBody(id, "DETERIORATING", true)), 403), "module-off");
        assertType(problem(call(HttpMethod.GET, ADMISSIONS, nurse.auth(), null), 403), "module-off");
        assertThat(grantRows(clinic)).isZero();
        assertThat(rows(clinic, "ward_assignment")).isZero();
    }

    @Test
    void aClinicThatWasNeverSwitchedOnLeavesEveryNurseWithOrWithoutAWardUnscoped() throws Exception {
        Clinic clinic = hospital();
        String a = wardIdByName(clinic, "General ward");
        String b = newWard(clinic, "Surgical ward");
        Pn withWard = newPn(clinic, "NURSE");
        Pn noWard = newPn(clinic, "NURSE");
        assign(clinic, a, withWard);
        Adm inA = admitInNamed(clinic, a, "Asha Plain-A");
        Adm inB = admitInNamed(clinic, b, "Bhim Plain-B");
        for (Pn n : new Pn[] {withWard, noWard}) {
            JsonNode list = listOf(n.auth());
            assertThat(idsOf(list)).containsExactlyInAnyOrder(inA.id(), inB.id());
            assertThat(list.get("scope").get("kind").asText()).isEqualTo("ALL");
            ok(HttpMethod.GET, ADMISSIONS + "/" + inB.id(), n.auth(), null, 200);
            assertThat(boardOf(n.auth()).has("otherWardsPastTarget")).isFalse();
        }
        assertThat(grantRows(clinic)).isZero();
    }

    @Test
    void twoNursesOfOneWardBothSeeItAndANurseOfTwoWardsSeesBothAndAnEndedAssignmentCountsForNothing() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        Pn second = newPn(clinic, "NURSE");
        assign(clinic, h.wardA(), h.nurseA(), second);
        assertThat(idsOf(listOf(h.nurseA().auth()))).containsExactly(h.inA().id());
        assertThat(idsOf(listOf(second.auth()))).containsExactly(h.inA().id());
        assign(clinic, h.wardB(), h.nurseB(), second);
        assertThat(idsOf(listOf(second.auth()))).containsExactlyInAnyOrder(h.inA().id(), h.inB().id());
        assertThat(listOf(second.auth()).get("scope").get("visible").asInt()).isEqualTo(2);
        // her assignment to ward A ends: ward B only
        assign(clinic, h.wardA(), h.nurseA());
        assertThat(idsOf(listOf(second.auth()))).containsExactly(h.inB().id());
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), second.auth(), null));
        // the assignment of a member who is no longer active counts for nothing: she gets 403 and nobody else changes
        setMembershipStatus(clinic.tenantId(), second.userId(), "INACTIVE");
        forbidden(call(HttpMethod.GET, ADMISSIONS, second.auth(), null));
        assertThat(idsOf(listOf(h.nurseB().auth()))).containsExactly(h.inB().id());
    }

    @Test
    void aGrantStopsGivingAccessWhenTheGranteesMembershipEndsAndStaysInTheOwnersListAndWorksAgainWhenSheIsBack() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
        setMembershipStatus(clinic.tenantId(), h.nurseA().userId(), "INACTIVE");
        forbidden(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null));
        forbidden(call(HttpMethod.POST, vitalsUrl(h.inB().id()), h.nurseA().auth(), js(low())));
        assertThat(boardOf(clinic.owner()).get("emergencyAccess").get("waiting").asInt()).isOne();
        setMembershipStatus(clinic.tenantId(), h.nurseA().userId(), "ACTIVE");
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
    }

    @Test
    void noQueryParameterOrHeaderCanChooseOrWidenTheScopeOfTheSignedInNurse() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String other = UUID.randomUUID().toString();
        for (String query : new String[] {"?scope=ALL", "?kind=ALL", "?wardId=" + h.wardB(), "?wards=" + h.wardB(), "?tenantId=" + other,
            "?membershipId=" + h.nurseB().membershipId(), "?mine=false&all=true", "?role=OWNER"}) {
            JsonNode list = ok(HttpMethod.GET, ADMISSIONS + query, h.nurseA().auth(), null, 200);
            assertThat(idsOf(list)).as(query).doesNotContain(h.inB().id());
            assertThat(list.get("scope").get("kind").asText()).as(query).isEqualTo("WARDS");
            assertThat(boardOf(h.nurseA().auth()).get("scope").get("kind").asText()).isEqualTo("WARDS");
        }
        problem(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id() + "?scope=ALL", h.nurseA().auth(), null), 403);
        assertThat(grantRows(clinic)).isZero();
    }

    @Test
    void aMemberWhoseRoleChangedToDoctorSeesEveryoneAndHasNoEmergencyAccessAndWhoComesBackAsNurseHasTheGrantAgain() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        setMembershipRole(clinic, h.nurseA().userId(), "DOCTOR");
        var asDoctor = tokenOf(clinic, h.nurseA().userId(), "DOCTOR");
        JsonNode list = listOf(asDoctor);
        assertThat(list.get("scope").get("kind").asText()).isEqualTo("ALL");
        assertThat(idsOf(list)).containsExactlyInAnyOrder(h.inA().id(), h.inB().id());
        assertThat(problem(call(HttpMethod.GET, EA_PICKER, asDoctor, null), 403).get("detail").asText()).isEqualTo(EA_ONLY);
        // the grant row stays for the owner's review whatever her role is now
        assertThat(boardOf(clinic.owner()).get("emergencyAccess").get("waiting").asInt()).isOne();
        setMembershipRole(clinic, h.nurseA().userId(), "NURSE");
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null, 200);
    }

    @Test
    void aGrantOfOneNurseNeverOpensTheSecondPatientOrAnotherModuleForHer() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        String wardC = newWard(clinic, "Orthopaedic ward");
        Adm inC = admitInNamed(clinic, wardC, "Hari Ward-C");
        openGrant(h.nurseA(), h.inB().id(), "DETERIORATING");
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + inC.id(), h.nurseA().auth(), null));
        // the Patients module is not opened by a grant: the nurse role has no patient search beyond what she had
        JsonNode picker = ok(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null, 200);
        assertThat(idsOfPicker(picker)).containsExactly(inC.id());
    }

    private static java.util.List<String> idsOfPicker(JsonNode picker) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        picker.get("items").forEach(i -> ids.add(i.get("admissionId").asText()));
        return ids;
    }

    @Test
    void theVisitingDoctorKeepsA403OnEveryIpdReadAndOnTheEmergencyAccessEndpoints() throws Exception {
        Hosp h = hosp();
        Pn visiting = newPn(h.clinic(), "VISITING_DOCTOR");
        String written = written(h.clinic());
        for (String url : new String[] {ADMISSIONS, ADMISSIONS + "/" + h.inA().id(), BOARD, SAFETY, EA_PICKER, EA}) {
            forbidden(call(HttpMethod.GET, url, visiting.auth(), null));
        }
        forbidden(call(HttpMethod.POST, EA, visiting.auth(), eaBody(h.inA().id(), "DETERIORATING", true)));
        forbidden(call(HttpMethod.POST, vitalsUrl(h.inA().id()), visiting.auth(), js(low())));
        assertThat(written(h.clinic())).isEqualTo(written);
    }

    @Test
    void aScopedReadWritesTheSameRecordAccessRowsAsTheOwnersAndTheListWritesNoneAndARefusalWritesNone() throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        long before = recordAccess(clinic, h.inA().patientId());
        listOf(h.nurseA().auth());
        assertThat(recordAccess(clinic, h.inA().patientId())).as("the list writes none (DEC-021)").isEqualTo(before);
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), h.nurseA().auth(), null, 200);
        long nurseDelta = recordAccess(clinic, h.inA().patientId()) - before;
        before = recordAccess(clinic, h.inA().patientId());
        ok(HttpMethod.GET, ADMISSIONS + "/" + h.inA().id(), clinic.owner(), null, 200);
        long ownerDelta = recordAccess(clinic, h.inA().patientId()) - before;
        assertThat(nurseDelta).as("the same rows as today").isEqualTo(ownerDelta).isPositive();
        long theirs = recordAccess(clinic, h.inB().patientId());
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + h.inB().id(), h.nurseA().auth(), null));
        assertThat(recordAccess(clinic, h.inB().patientId())).as("a refusal writes none").isEqualTo(theirs);
        assertThat(count(clinic, "select count(*) from ipd.clinical_event where kind = 'deny'")).isZero();
    }

    @Test
    void whenTheScopeCannotBeBuiltTheRequestFailsWithAnErrorAndNoDataAndAWarnLineOfIdsAndTheOwnerIsNotAffected(CapturedOutput output)
            throws Exception {
        Hosp h = hosp();
        Clinic clinic = h.clinic();
        try (Connection c = db().migrator()) {
            c.setAutoCommit(true);
            DbAccess.execute(c, "revoke select on ipd.ward_assignment from sahaj_app");
            try {
                for (String url : new String[] {ADMISSIONS, SAFETY, BOARD, ADMISSIONS + "/" + h.inB().id(), EA_PICKER}) {
                    var res = call(HttpMethod.GET, url, h.nurseA().auth(), null).andReturn().getResponse();
                    assertThat(res.getStatus()).as(url + " " + res.getContentAsString()).isEqualTo(500);
                    assertThat(res.getContentAsString()).doesNotContain(h.inA().name()).doesNotContain(h.inB().name())
                            .doesNotContain(h.inA().id()).doesNotContain(h.inB().id()).doesNotContain("items");
                }
                var write = call(HttpMethod.POST, vitalsUrl(h.inA().id()), h.nurseA().auth(), js(low())).andReturn().getResponse();
                assertThat(write.getStatus()).isEqualTo(500);
                // the owner (scope ALL, no assignment read) is not touched
                assertThat(idsOf(listOf(clinic.owner()))).hasSize(2);
            } finally {
                DbAccess.execute(c, "grant select on ipd.ward_assignment to sahaj_app");
            }
        }
        assertThat(vitalsCount(clinic, h.inA().id())).as("nothing written").isZero();
        assertThat(output.getAll()).contains("ipd scope could not be built tenantId=" + clinic.tenantId());
        assertThat(output.getAll()).doesNotContain(h.inA().name()).doesNotContain(h.inB().name()).doesNotContain(h.inA().mobile());
        assertThat(idsOf(listOf(h.nurseA().auth()))).as("restored").containsExactly(h.inA().id());
    }

    @Test
    void theApplicationLogOfARefusalAndOfAGrantHoldsIdsOnlyNoNameMobileUhidOrReason(CapturedOutput output) throws Exception {
        Full f = full();
        Hosp h = f.h();
        Clinic clinic = h.clinic();
        Adm theirs = h.inB();
        String uhid = values(clinic, "select uhid from patient.patient where id = ?::uuid", theirs.patientId()).get(0);
        String nurseName = nameOf(clinic, h.nurseA().userId());
        outside(call(HttpMethod.GET, ADMISSIONS + "/" + theirs.id(), h.nurseA().auth(), null));
        outside(call(HttpMethod.POST, dosesUrl(f.orderId()), h.nurseA().auth(), js(notGiven(ist(8, 0), "NIL_BY_MOUTH"))));
        ok(HttpMethod.GET, EA_PICKER, h.nurseA().auth(), null, 200);
        JsonNode g = openGrant(h.nurseA(), theirs.id(), "DETERIORATING");
        ok(HttpMethod.POST, EA + "/" + g.get("id").asText() + "/review", clinic.owner(), null, 200);
        listOf(h.nurseA().auth());
        boardOf(h.nurseA().auth());
        String log = output.getAll();
        assertThat(log).contains("ipd outside-scope tenantId=" + clinic.tenantId());
        assertThat(log).contains("userId=" + h.nurseA().userId()).contains("admissionId=" + theirs.id());
        assertThat(log).contains("ipd emergency-access opened tenantId=" + clinic.tenantId());
        assertThat(log).doesNotContain(theirs.name()).doesNotContain(theirs.mobile()).doesNotContain(uhid)
                .doesNotContain(nurseName).doesNotContain(h.inA().name()).doesNotContain("Patient deteriorating");
        for (String line : log.split("\\R")) {
            if (line.contains("ipd outside-scope")) {
                assertThat(line).as("the route is a name, not a url with an id of a person").doesNotContain(theirs.name());
            }
        }
    }
}
