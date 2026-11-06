package com.sahaj.platform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * SAH-30 AC1, AC3, AC10, AC11 on the real {@code sahaj_test} database: who may open the Nursing sheet and who may
 * save (the role is checked first), IPD Off / Read-only / a discharged patient, the order of the answers, another
 * clinic's admission (404), the Nursing button, vitals due and the latest score of the admission sheet for every role.
 * Test plan: docs/test-plans/SAH-30.md.
 */
@OnTestDatabase
class VitalsAccessIT extends VitalsSupport {

    private static final String OFF_TEXT = "In-patients (IPD) is not switched on for your clinic.";

    private record Setup(Clinic clinic, Adm adm) {
    }

    private Setup setup() throws Exception {
        Clinic clinic = hospital();
        return new Setup(clinic, admitOne(clinic));
    }

    // ---------------- roles ----------------

    @Test
    void ownerNurseAndDoctorOpenTheSheetAndOnlyOwnerAndNurseMaySaveFromIt() throws Exception {
        Setup s = setup();
        save(s.clinic(), s.adm().id(), low());
        for (String role : NURSING_VIEWERS) {
            JsonNode sheet = nurseSheetAs(s.clinic(), role, s.adm().id());
            assertThat(sheet.get("patientName").asText()).as(role).isEqualTo(s.adm().name());
            assertThat(sheet.get("latest").isNull()).as(role).isFalse();
            boolean recorder = !role.equals("DOCTOR");
            assertThat(sheet.get("canRecord").asBoolean()).as(role).isEqualTo(recorder);
            if (recorder) {
                assertThat(sheet.get("readOnlyReason").isNull()).as(role).isTrue();
            } else {
                assertThat(sheet.get("readOnlyReason").asText()).as(role).isEqualTo("ROLE");
            }
        }
        for (String role : NURSING_RECORDERS) {
            JsonNode saved = saveAs(s.clinic(), role, s.adm().id(), low());
            assertThat(saved.get("canRecord").asBoolean()).as(role).isTrue();
        }
        assertThat(vitalsCount(s.clinic(), s.adm().id())).isEqualTo(3);
    }

    @Test
    void otherRolesGetA403ForTheSheetAndNoAccessRowIsWritten() throws Exception {
        Setup s = setup();
        long access = recordAccess(s.clinic(), s.adm().patientId());
        for (String role : NURSING_NON_VIEWERS) {
            JsonNode p = problem(sheetCall(s.clinic().as(role), s.adm().id()), 403);
            assertType(p, "forbidden");
        }
        assertThat(recordAccess(s.clinic(), s.adm().patientId())).isEqualTo(access);
    }

    // ---------------- a role changed while the token is still valid (security review M1) ----------------
    // The only owner cannot be demoted (the database keeps one active owner), so these tests use the clinic's added
    // member: the token says NURSE, the membership row is changed underneath it.

    @Test
    void aRoleChangedInTheMembershipIsRefusedAtOnceOnBothEndpointsWhileTheTokenStillSaysNurse() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        String id = s.adm().id();
        UUID user = doctorUserId(clinic);
        RequestPostProcessor nurse = tokenOf(clinic, user, "NURSE");
        setMembershipRole(clinic, user, "NURSE");
        // positive control: with the membership a nurse, the token opens the sheet and saves
        Map<String, Object> stored = low();
        ok(HttpMethod.POST, vitalsUrl(id), nurse, js(stored), 201);
        assertThat(ok(HttpMethod.GET, nursingUrl(id), nurse, null, 200).get("canRecord").asBoolean()).isTrue();
        Snap before = snap(clinic, s.adm());
        try {
            for (String demoted : new String[] {"FRONT_DESK", "BILLING"}) {
                setMembershipRole(clinic, user, demoted);
                assertType(problem(sheetCall(nurse, id), 403), "forbidden");
                assertType(problem(saveCall(nurse, id, medium3()), 403), "forbidden");
                // a repeat of a stored key is not answered either: the replay would hand out the sheet
                assertType(problem(saveCall(nurse, id, stored), 403), "forbidden");
                assertThat(snap(clinic, s.adm())).as(demoted + ": nothing written, no access row").isEqualTo(before);
            }
            // a doctor may read the sheet but not save, whatever the token says
            setMembershipRole(clinic, user, "DOCTOR");
            JsonNode sheet = ok(HttpMethod.GET, nursingUrl(id), nurse, null, 200);
            assertThat(sheet.get("canRecord").asBoolean()).isFalse();
            assertThat(sheet.get("readOnlyReason").asText()).isEqualTo("ROLE");
            assertType(problem(saveCall(nurse, id, medium3()), 403), "forbidden");
            assertThat(vitalsCount(clinic, id)).isOne();
            // restored to nurse: both work again (positive control)
            setMembershipRole(clinic, user, "NURSE");
            assertThat(ok(HttpMethod.GET, nursingUrl(id), nurse, null, 200).get("canRecord").asBoolean()).isTrue();
            ok(HttpMethod.POST, vitalsUrl(id), nurse, js(medium3()), 201);
            assertThat(vitalsCount(clinic, id)).isEqualTo(2);
        } finally {
            setMembershipRole(clinic, user, "DOCTOR");
        }
    }

    @Test
    void aDeactivatedMembershipIsRefusedAtOnceOnBothEndpointsWhileTheTokenIsStillValid() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        String id = s.adm().id();
        UUID user = doctorUserId(clinic);
        RequestPostProcessor nurse = tokenOf(clinic, user, "NURSE");
        setMembershipRole(clinic, user, "NURSE");
        ok(HttpMethod.POST, vitalsUrl(id), nurse, js(low()), 201);
        Snap before = snap(clinic, s.adm());
        try {
            for (String status : new String[] {"INACTIVE", "REMOVED"}) {
                setMembershipStatus(clinic.tenantId(), user, status);
                assertType(problem(sheetCall(nurse, id), 403), "forbidden");
                assertType(problem(saveCall(nurse, id, medium3()), 403), "forbidden");
                assertThat(snap(clinic, s.adm())).as(status + ": nothing written").isEqualTo(before);
            }
        } finally {
            setMembershipStatus(clinic.tenantId(), user, "ACTIVE");
        }
        // reactivated: positive control
        assertThat(ok(HttpMethod.GET, nursingUrl(id), nurse, null, 200).get("patientName").asText()).isEqualTo(s.adm().name());
        ok(HttpMethod.POST, vitalsUrl(id), nurse, js(medium3()), 201);
        assertThat(vitalsCount(clinic, id)).isEqualTo(2);
        setMembershipRole(clinic, user, "DOCTOR");
    }

    @Test
    void everyRoleExceptOwnerAndNurseIs403OnSaveAndNothingIsWritten() throws Exception {
        Setup s = setup();
        Snap before = snap(s.clinic(), s.adm());
        for (String role : NURSING_NON_RECORDERS) {
            JsonNode p = problem(saveCall(s.clinic().as(role), s.adm().id(), medium3()), 403);
            assertType(p, "forbidden");
            // the role is checked before the body: an invalid body is a 403 for them too
            assertType(problem(saveCall(s.clinic().as(role), s.adm().id(), vit("x", "x", "x", "x", "x")), 403), "forbidden");
        }
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
        assertThat(vitalsAudits(s.clinic())).isZero();
    }

    @Test
    void withoutASessionBothEndpointsAre401() throws Exception {
        Setup s = setup();
        Snap before = snap(s.clinic(), s.adm());
        assertType(problem(sheetCall(noSession(), s.adm().id()), 401), "unauthorized");
        assertType(problem(saveCall(noSession(), s.adm().id(), low()), 401), "unauthorized");
        assertThat(snap(s.clinic(), s.adm())).isEqualTo(before);
    }

    // ---------------- IPD Off, Read-only, discharged ----------------

    @Test
    void whenIpdIsOffBothEndpointsAreModuleOffForTheAllowedRolesAndForbiddenForTheOthers() throws Exception {
        Clinic off = newClinic(); // no trial: IPD is Off
        String id = java.util.UUID.randomUUID().toString();
        for (String role : NURSING_VIEWERS) {
            JsonNode p = problem(sheetCall(off.as(role), id), 403);
            assertType(p, "module-off");
            assertThat(p.get("detail").asText()).as(role).isEqualTo(OFF_TEXT);
        }
        for (String role : NURSING_RECORDERS) {
            JsonNode p = problem(saveCall(off.as(role), id, low()), 403);
            assertType(p, "module-off");
            assertThat(p.get("detail").asText()).isEqualTo(OFF_TEXT);
        }
        // the role check comes first
        assertType(problem(sheetCall(off.as("FRONT_DESK"), id), 403), "forbidden");
        assertType(problem(saveCall(off.as("DOCTOR"), id, low()), 403), "forbidden");
        assertThat(rows(off, "vitals")).isZero();
    }

    @Test
    void inReadOnlyTheSheetOpensToReadButSavingIs409IpdReadonlyBeforeTheBodyIsChecked() throws Exception {
        Setup s = setup();
        Map<String, Object> first = noted(low(), "Before the trial ended");
        save(s.clinic(), s.adm().id(), first);
        expireTrial(s.clinic());
        Snap before = snap(s.clinic(), s.adm());

        for (String role : NURSING_VIEWERS) {
            JsonNode sheet = nurseSheetAs(s.clinic(), role, s.adm().id());
            assertThat(sheet.get("state").asText()).isEqualTo("readonly");
            assertThat(sheet.get("canRecord").asBoolean()).isFalse();
            assertThat(sheet.get("readOnlyReason").asText()).as(role).isEqualTo(role.equals("DOCTOR") ? "ROLE" : "IPD_READONLY");
            assertThat(sheet.get("latest").isNull()).isFalse();
            assertThat(sheet.get("recentNotes")).hasSize(1);
        }
        for (Map<String, Object> body : java.util.List.of(low(), medium3(), vit("x", "x", "x", "x", "x"), first)) {
            JsonNode p = problem(saveCall(s.clinic().owner(), s.adm().id(), body), 409);
            assertType(p, "ipd-readonly");
            assertThat(p.get("detail").asText()).isEqualTo(READ_ONLY);
        }
        assertType(problem(saveCall(s.clinic().as("NURSE"), s.adm().id(), low()), 409), "ipd-readonly");
        assertThat(snap(s.clinic(), s.adm()).vitals()).isEqualTo(before.vitals());
        assertThat(snap(s.clinic(), s.adm()).events()).isEqualTo(before.events());
        assertThat(vitalsAudits(s.clinic())).isOne();
    }

    @Test
    void aDischargedPatientsSheetCanBeReadButNotSavedAndTheReasonIsNotAdmitted() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        save(clinic, s.adm().id(), noted(medium3(), "Last note"));
        // SAH-33: a Normal discharge is refused while the escalation is not closed, so a doctor's response is recorded first
        closeEscalation(clinic, s.adm().id());
        signAndDischarge(clinic, s.adm().id());
        Snap before = snap(clinic, s.adm());

        for (String role : NURSING_VIEWERS) {
            JsonNode sheet = nurseSheetAs(clinic, role, s.adm().id());
            assertThat(sheet.get("status").asText()).isEqualTo("DISCHARGED");
            assertThat(sheet.get("canRecord").asBoolean()).isFalse();
            assertThat(sheet.get("readOnlyReason").asText()).as(role).isEqualTo(role.equals("DOCTOR") ? "ROLE" : "NOT_ADMITTED");
            assertThat(sheet.get("latest").get("ewsScore").asInt()).isEqualTo(3);
            assertThat(sheet.get("recentNotes").get(0).get("text").asText()).isEqualTo("Last note");
            assertThat(sheet.get("vitalsDue").asBoolean()).isFalse();
            assertThat(sheet.get("bedCode").asText()).isEqualTo(s.adm().bedCode());
        }
        JsonNode p = problem(saveCall(clinic.owner(), s.adm().id(), newKey(low())), 409);
        assertType(p, "not-admitted");
        assertThat(p.get("detail").asText()).isEqualTo(VITALS_NOT_ADMITTED);
        assertThat(snap(clinic, s.adm()).vitals()).isEqualTo(before.vitals());
        assertThat(snap(clinic, s.adm()).events()).isEqualTo(before.events());
        assertThat(snap(clinic, s.adm()).dump()).isEqualTo(before.dump());
        assertThat(snap(clinic, s.adm()).audits()).isEqualTo(before.audits());

        // a discharged patient in a Read-only clinic: Read-only is the reason that applies first
        expireTrial(clinic);
        assertThat(nurseSheet(clinic, s.adm().id()).get("readOnlyReason").asText()).isEqualTo("IPD_READONLY");
        assertType(problem(saveCall(clinic.owner(), s.adm().id(), newKey(low())), 409), "ipd-readonly");
    }

    // ---------------- the order of the answers ----------------

    @Test
    void theBodyIsCheckedBeforeTheAdmissionAndABadBodyOnADischargedPatientIsStill400() throws Exception {
        Setup s = setup();
        Clinic other = hospital();
        Clinic clinic = s.clinic();
        // another clinic's admission: a valid body is 404, an invalid one is 400 (the body is checked before the lock)
        assertType(problem(saveCall(other.owner(), s.adm().id(), low()), 404), "not-found");
        assertType(problem(saveCall(other.owner(), s.adm().id(), with(low(), "bp", "x")), 400), "validation");
        signAndDischarge(clinic, s.adm().id());
        assertType(problem(saveCall(clinic.owner(), s.adm().id(), with(low(), "bp", "x")), 400), "validation");
        assertType(problem(saveCall(clinic.owner(), s.adm().id(), newKey(low())), 409), "not-admitted");
    }

    // ---------------- other clinics ----------------

    @Test
    void anotherClinicsAdmissionIs404OnBothEndpointsAndNothingIsWrittenOrLogged() throws Exception {
        Setup a = setup();
        save(a.clinic(), a.adm().id(), noted(medium3(), "Private note"));
        Clinic b = hospital();
        Snap before = snap(a.clinic(), a.adm());
        long accessA = recordAccess(a.clinic(), a.adm().patientId());
        long accessB = count(b, "select count(*) from patient.record_access");
        long auditsB = allAudits(b);

        JsonNode get = problem(sheetCall(b.owner(), a.adm().id()), 404);
        assertType(get, "not-found");
        assertThat(get.toString()).doesNotContain(a.adm().name()).doesNotContain("Private note");
        for (String role : NURSING_VIEWERS) {
            assertType(problem(sheetCall(b.as(role), a.adm().id()), 404), "not-found");
        }
        JsonNode post = problem(saveCall(b.owner(), a.adm().id(), medium3()), 404);
        assertType(post, "not-found");
        assertType(problem(saveCall(b.as("NURSE"), a.adm().id(), high7()), 404), "not-found");

        assertThat(snap(a.clinic(), a.adm())).isEqualTo(before);
        assertThat(recordAccess(a.clinic(), a.adm().patientId())).isEqualTo(accessA);
        assertThat(count(b, "select count(*) from patient.record_access")).isEqualTo(accessB);
        assertThat(allAudits(b)).isEqualTo(auditsB);
        assertThat(rows(b, "vitals") + rows(b, "nursing_note") + rows(b, "clinical_event") + rows(b, "ews_escalation")).isZero();

        // an admission that does not exist, and a path that is not a UUID
        assertType(problem(sheetCall(b.owner(), java.util.UUID.randomUUID()), 404), "not-found");
        assertType(problem(saveCall(b.owner(), java.util.UUID.randomUUID(), low()), 404), "not-found");
        assertThat(problem(sheetCall(b.owner(), "not-a-uuid"), 400).get("title")).isNotNull();
        assertThat(problem(saveCall(b.owner(), "not-a-uuid", low()), 400).get("title")).isNotNull();
    }

    @Test
    void theSameClientKeyInAnotherClinicIsAnIndependentFirstSave() throws Exception {
        Setup a = setup();
        Setup b = setup();
        Map<String, Object> body = noted(medium3(), "Same key, other clinic");
        JsonNode first = save(a.clinic(), a.adm().id(), body);
        JsonNode second = save(b.clinic(), b.adm().id(), body);
        assertThat(first.get("saved").get("replayed").asBoolean()).isFalse();
        assertThat(second.get("saved").get("replayed").asBoolean()).isFalse();
        assertThat(second.get("saved").get("vitalsId").asText()).isNotEqualTo(first.get("saved").get("vitalsId").asText());
        assertThat(vitalsCount(a.clinic(), a.adm().id())).isOne();
        assertThat(vitalsCount(b.clinic(), b.adm().id())).isOne();
    }

    // ---------------- the admission sheet: Nursing button, vitals due, latest score ----------------

    @Test
    void theAdmissionSheetHasTheNursingButtonVitalsDueAndNoScoreBeforeAnyVitals() throws Exception {
        Setup s = setup();
        for (String role : NURSING_VIEWERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), s.clinic().as(role), null, 200);
            assertThat(d.get("canOpenNursing").asBoolean()).as(role).isTrue();
            assertThat(d.get("vitalsDue").asBoolean()).as(role).isTrue();
            assertThat(absent(d, "latestEws")).as(role).isTrue();
        }
        JsonNode front = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), s.clinic().as("FRONT_DESK"), null, 200);
        assertThat(front.get("canOpenNursing").asBoolean()).isFalse();
        assertThat(absent(front, "vitalsDue")).isTrue();
        assertThat(absent(front, "latestEws")).isTrue();
    }

    @Test
    void afterASaveVitalsDueIsFalseAndTheLatestScoreIsShownToTheThreeRolesOnly() throws Exception {
        Setup s = setup();
        JsonNode saved = save(s.clinic(), s.adm().id(), medium3());
        for (String role : NURSING_VIEWERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), s.clinic().as(role), null, 200);
            assertThat(d.get("vitalsDue").asBoolean()).as(role).isFalse();
            assertThat(d.get("latestEws").get("score").asInt()).as(role).isEqualTo(3);
            assertThat(d.get("latestEws").get("risk").asText()).as(role).isEqualTo("MEDIUM");
            assertThat(instant(d.get("latestEws").get("recordedAt"))).isEqualTo(instant(saved.get("saved").get("recordedAt")));
        }
        // the newest record decides, not the highest
        JsonNode lowSaved = save(s.clinic(), s.adm().id(), low());
        JsonNode d = detail(s.clinic(), s.adm().id());
        assertThat(d.get("latestEws").get("score").asInt()).isZero();
        assertThat(d.get("latestEws").get("risk").asText()).isEqualTo("LOW");
        assertThat(instant(d.get("latestEws").get("recordedAt"))).isEqualTo(instant(lowSaved.get("saved").get("recordedAt")));

        JsonNode front = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), s.clinic().as("FRONT_DESK"), null, 200);
        assertThat(front.get("canOpenNursing").asBoolean()).isFalse();
        assertThat(absent(front, "vitalsDue")).isTrue();
        assertThat(absent(front, "latestEws")).isTrue();
    }

    @Test
    void vitalsBecomeDueWhenMoreThanTwoHundredAndFortyWholeMinutesHavePassed() throws Exception {
        Clinic clinic = hospital();
        int[] minutes = {0, 239, 240, 241, 300};
        boolean[] due = {false, false, false, true, true};
        for (int i = 0; i < minutes.length; i++) {
            Adm adm = admitOne(clinic);
            if (minutes[i] == 0) {
                save(clinic, adm.id(), low());
            } else {
                vitalsAged(clinic, adm.id(), minutes[i]);
            }
            String label = minutes[i] + " minutes";
            assertThat(detail(clinic, adm.id()).get("vitalsDue").asBoolean()).as(label).isEqualTo(due[i]);
            assertThat(nurseSheet(clinic, adm.id()).get("vitalsDue").asBoolean()).as(label + " (sheet)").isEqualTo(due[i]);
            for (String role : new String[] {"NURSE", "DOCTOR"}) {
                assertThat(ok(HttpMethod.GET, ADMISSIONS + "/" + adm.id(), clinic.as(role), null, 200).get("vitalsDue").asBoolean())
                        .as(label + " " + role).isEqualTo(due[i]);
            }
        }
    }

    @Test
    void aNewSaveClearsVitalsDueAndOnlyTheNewestRecordCounts() throws Exception {
        Clinic clinic = hospital();
        Adm adm = admitOne(clinic);
        vitalsAged(clinic, adm.id(), 300);
        assertThat(detail(clinic, adm.id()).get("vitalsDue").asBoolean()).isTrue();
        JsonNode sheet = nurseSheet(clinic, adm.id());
        assertThat(sheet.get("vitalsDue").asBoolean()).isTrue();
        assertThat(sheet.get("latest").isNull()).isFalse();
        save(clinic, adm.id(), low());
        assertThat(detail(clinic, adm.id()).get("vitalsDue").asBoolean()).isFalse();
        assertThat(nurseSheet(clinic, adm.id()).get("vitalsDue").asBoolean()).isFalse();
        assertThat(vitalsCount(clinic, adm.id())).isEqualTo(2);
    }

    @Test
    void aDischargedAdmissionIsNeverDueKeepsTheLastScoreAndStillHasTheNursingButtonForTheThreeRoles() throws Exception {
        Setup s = setup();
        Clinic clinic = s.clinic();
        Adm noVitals = admitOne(clinic);
        save(clinic, s.adm().id(), high7());
        closeEscalation(clinic, s.adm().id()); // SAH-33: a Normal discharge needs the escalation closed first
        vitalsAged(clinic, noVitals.id(), 500);
        signAndDischarge(clinic, s.adm().id());
        signAndDischarge(clinic, noVitals.id());
        for (String role : NURSING_VIEWERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), clinic.as(role), null, 200);
            assertThat(d.get("status").asText()).isEqualTo("DISCHARGED");
            assertThat(d.get("canOpenNursing").asBoolean()).as(role).isTrue();
            assertThat(d.get("vitalsDue").isNull()).as(role).isFalse();
            assertThat(d.get("vitalsDue").asBoolean()).as(role).isFalse();
            assertThat(d.get("latestEws").get("score").asInt()).as(role).isEqualTo(7);
            assertThat(d.get("latestEws").get("risk").asText()).isEqualTo("HIGH");
            JsonNode old = ok(HttpMethod.GET, ADMISSIONS + "/" + noVitals.id(), clinic.as(role), null, 200);
            assertThat(old.get("vitalsDue").asBoolean()).as(role + " old vitals, discharged").isFalse();
        }
        JsonNode front = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), clinic.as("FRONT_DESK"), null, 200);
        assertThat(front.get("canOpenNursing").asBoolean()).isFalse();
        assertThat(absent(front, "vitalsDue")).isTrue();
        assertThat(absent(front, "latestEws")).isTrue();
    }

    @Test
    void inReadOnlyTheNursingButtonIsStillThereToReadButNotToSave() throws Exception {
        Setup s = setup();
        save(s.clinic(), s.adm().id(), low());
        expireTrial(s.clinic());
        for (String role : NURSING_VIEWERS) {
            JsonNode d = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), s.clinic().as(role), null, 200);
            assertThat(d.get("canOpenNursing").asBoolean()).as(role).isTrue();
            assertThat(d.get("latestEws").get("score").asInt()).isZero();
        }
        JsonNode front = ok(HttpMethod.GET, ADMISSIONS + "/" + s.adm().id(), s.clinic().as("FRONT_DESK"), null, 200);
        assertThat(front.get("canOpenNursing").asBoolean()).isFalse();
    }
}
