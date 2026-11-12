package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrTemplateResolver;
import com.sahaj.opd.application.emr.EmrViews.TemplateSnapshot;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;

/**
 * SAH-43-3 (the doctor's own details through {@code platform.api}) and SAH-44-4 (which template the EMR chooses, and
 * the section list a visit is opened with) on the real database. The visit that stores the snapshot arrives with
 * SAH-46, so the snapshot rule is tested on the resolver service the visit story will call. Names, degrees and
 * registration numbers are made up. Test plan: docs/test-plans/SAH-43-44.md.
 */
@OnTestDatabase
class EmrDoctorContextIT extends EmrSupport {

    @Autowired
    private EmrTemplateResolver resolver;

    @Autowired
    private TeamMemberPort teamMembers;

    // ---------------- /emr/me ----------------

    @Test
    void aDoctorGetsTheirOwnDetailsExactlyAsSavedAndTheTemplateTheirSpecialtyChooses() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addStaff(clinic, "DOCTOR", "Dr Zxqv Namefield", "General Physician", "MBBS, MD", "MH-ZXQ-4411");
        JsonNode me = get(ME, doctor.token(clinic));
        assertThat(me.get("membershipId").asText()).isEqualTo(doctor.membershipId().toString());
        assertThat(me.get("name").asText()).isEqualTo("Dr Zxqv Namefield");
        assertThat(me.get("role").asText()).isEqualTo("DOCTOR");
        assertThat(me.get("specialty").asText()).isEqualTo("General Physician");
        assertThat(me.get("degree").asText()).isEqualTo("MBBS, MD");
        assertThat(me.get("registrationNo").asText()).isEqualTo("MH-ZXQ-4411");
        assertThat(me.get("missing").size()).isZero();
        JsonNode template = me.get("template");
        assertThat(template.get("templateKey").asText()).isEqualTo("gp");
        assertThat(template.get("templateVersion").asInt()).isEqualTo(1);
        assertThat(template.get("templateName").asText()).isEqualTo("General practice");
        assertThat(template.get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
    }

    @Test
    void aMissingDegreeOrRegistrationNumberIsListedAndNothingIsBlocked() throws Exception {
        Clinic clinic = newClinic();
        Staff noDegree = addStaff(clinic, "DOCTOR", "Dr No Degree", "Dental surgeon", null, "REG-" + shortId());
        Staff noRegistration = addStaff(clinic, "VISITING_DOCTOR", "Dr No Registration", "Dental", "BDS", null);
        Staff neither = addStaff(clinic, "DOCTOR", "Dr Neither", "Homeopath", null, null);
        JsonNode first = get(ME, noDegree.token(clinic));
        assertThat(texts(first.get("missing"), null)).containsExactly("DEGREE");
        assertThat(first.get("degree").isNull()).isTrue();
        assertThat(first.get("template").get("templateKey").asText()).isEqualTo("dental");
        JsonNode second = get(ME, noRegistration.token(clinic));
        assertThat(texts(second.get("missing"), null)).containsExactly("REGISTRATION_NO");
        assertThat(second.get("registrationNo").isNull()).isTrue();
        assertThat(second.get("role").asText()).isEqualTo("VISITING_DOCTOR");
        JsonNode third = get(ME, neither.token(clinic));
        assertThat(texts(third.get("missing"), null)).containsExactly("DEGREE", "REGISTRATION_NO");
        // text that is only spaces counts as empty
        setDoctorDegreeAndRegistration(clinic, noDegree, "   ", "   ");
        assertThat(texts(get(ME, noDegree.token(clinic)).get("missing"), null)).containsExactly("DEGREE", "REGISTRATION_NO");
    }

    @Test
    void theSpecialtyNamesOfTheAliasListChooseTheirTemplateIgnoringLetterCaseAndExtraSpaces() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General practice");
        String[][] cases = {
                {"General Physician", "gp"}, {"general practice", "gp"}, {"  GENERAL    physician  ", "gp"},
                {"Dental Surgeon", "dental"}, {"dental", "dental"}, {"DENTAL   SURGEON", "dental"},
                {"Paediatrician", "paeds"}, {"Paediatrics", "paeds"}, {" paediatrician ", "paeds"},
                {"Homeopath", "homeo"}, {"Homeopathy", "homeo"}, {"HOMEOPATH", "homeo"}};
        for (String[] c : cases) {
            setDoctorSpecialty(clinic, doctor, c[0]);
            JsonNode me = get(ME, doctor.token(clinic));
            assertThat(me.get("template").get("templateKey").asText()).as("specialty '" + c[0] + "'").isEqualTo(c[1]);
            assertThat(me.get("template").get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
            assertThat(me.get("specialty").asText()).as("returned as saved").isEqualTo(c[0].strip());
        }
    }

    @Test
    void aSpecialtyNotInTheListFallsBackToTheClinicsSpecialtyThenToGeneralPractice() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Cardiologist");
        setClinicSpecialty(clinic, "paeds");
        JsonNode viaClinic = get(ME, doctor.token(clinic)).get("template");
        assertThat(viaClinic.get("templateKey").asText()).isEqualTo("paeds");
        assertThat(viaClinic.get("choiceSource").asText()).isEqualTo("CLINIC_SPECIALTY");

        for (String odd : new String[] {"Ayurveda", "General physician MD", "dent", "gp", "General  Practice Clinic"}) {
            setDoctorSpecialty(clinic, doctor, odd);
            assertThat(get(ME, doctor.token(clinic)).get("template").get("choiceSource").asText()).as(odd)
                    .isEqualTo("CLINIC_SPECIALTY");
        }

        // no specialty on the doctor either: the clinic decides
        commit(clinic.tenantId(), "update platform.doctor_profile set specialty = null where membership_id = ?",
                doctor.membershipId());
        assertThat(get(ME, doctor.token(clinic)).get("template").get("templateKey").asText()).isEqualTo("paeds");
        assertThat(get(ME, doctor.token(clinic)).get("specialty").isNull()).isTrue();

        // neither the doctor nor the clinic has one: General practice, and the answer says it came from the default
        setClinicSpecialty(clinic, null);
        JsonNode byDefault = get(ME, doctor.token(clinic)).get("template");
        assertThat(byDefault.get("templateKey").asText()).isEqualTo("gp");
        assertThat(byDefault.get("choiceSource").asText()).isEqualTo("DEFAULT");
        assertThat(byDefault.get("templateName").asText()).isEqualTo("General practice");
    }

    @Test
    void theDoctorsSpecialtyBeatsTheClinicsAndTheSwitchIsNotSavedAsADefault() throws Exception {
        Clinic clinic = newClinic();
        setClinicSpecialty(clinic, "dental");
        Staff doctor = addDoctor(clinic, "Paediatrician");
        assertThat(get(ME, doctor.token(clinic)).get("template").get("templateKey").asText()).isEqualTo("paeds");
        // asking the resolver for a snapshot of another template (the switch of SAH-46) leaves the choice alone
        Authentication auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");
        TemplateSnapshot switched = runAs(auth, () -> tx().execute(s -> resolver.snapshot(clinic.tenantId(), "homeo", "SWITCHED")));
        assertThat(switched.templateKey()).isEqualTo("homeo");
        assertThat(get(ME, doctor.token(clinic)).get("template").get("templateKey").asText()).isEqualTo("paeds");
    }

    @Test
    void anOwnerWithDoctorDetailsGetsThemAnOwnerWithoutIsToldToAddThem() throws Exception {
        Clinic clinic = newClinic();
        JsonNode problem = problemOf(HttpMethod.GET, ME, clinic.owner(), null, 403, "doctor-details-missing");
        assertThat(problem.get("detail").asText()).isEqualTo("Add your doctor details in Team and access to write consultations.");
        ownerWithDoctorDetails(clinic, "Dental surgeon", "BDS, MDS", "MH-OWN-77");
        JsonNode me = get(ME, clinic.owner());
        assertThat(me.get("role").asText()).isEqualTo("OWNER");
        assertThat(me.get("degree").asText()).isEqualTo("BDS, MDS");
        assertThat(me.get("registrationNo").asText()).isEqualTo("MH-OWN-77");
        assertThat(me.get("template").get("templateKey").asText()).isEqualTo("dental");
    }

    @Test
    void nurseFrontDeskBillingPharmacistAndCounsellorAre403AndTheRoleInTheTokenIsReReadFromTheMembership() throws Exception {
        Clinic clinic = newClinic();
        for (String role : NOT_DOCTOR_ROLES) {
            Staff staff = addNonDoctor(clinic, role);
            problemOf(HttpMethod.GET, ME, staff.token(clinic), null, 403, "forbidden");
            problemOf(HttpMethod.GET, ME, staff.tokenClaiming(clinic, "DOCTOR"), null, 403, "forbidden");
        }
        problemOf(HttpMethod.GET, ME, anonymous(), null, 401, "unauthorized");
    }

    @Test
    void thereIsNoCallToReadAnotherDoctorsDetailsAndAnIdInTheRequestIsIgnored() throws Exception {
        Clinic clinic = newClinic();
        Staff x = addStaff(clinic, "DOCTOR", "Dr X Person", "Dental", "BDS", "REG-X-" + shortId());
        Staff y = addStaff(clinic, "DOCTOR", "Dr Y Person", "Homeopath", "BHMS", "REG-Y-" + shortId());
        JsonNode asY = get(ME + "?membershipId=" + x.membershipId() + "&doctorMembershipId=" + x.membershipId(), y.token(clinic));
        assertThat(asY.get("membershipId").asText()).isEqualTo(y.membershipId().toString());
        assertThat(asY.get("degree").asText()).isEqualTo("BHMS");
        for (String path : new String[] {ME + "/" + x.membershipId(), EMR + "/doctors/" + x.membershipId(),
                EMR + "/doctors"}) {
            assertThat(call(HttpMethod.GET, path, y.token(clinic), null).andReturn().getResponse().getStatus())
                    .as(path).isBetween(400, 499);
        }
    }

    @Test
    void aVisitingDoctorOfTwoClinicsGetsEachClinicsOwnSavedDetails() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        UUID user = newClinic().ownerUserId();
        addMembership(a.tenantId(), user, "VISITING_DOCTOR");
        addMembership(b.tenantId(), user, "VISITING_DOCTOR");
        UUID inA = UUID.fromString(row(a, "select id::text from platform.membership where user_id = ?::uuid and role = 'VISITING_DOCTOR'", user).get(0));
        UUID inB = UUID.fromString(row(b, "select id::text from platform.membership where user_id = ?::uuid and role = 'VISITING_DOCTOR'", user).get(0));
        commit(a.tenantId(), "insert into platform.doctor_profile (membership_id, specialty, fee_paise, degree, registration_no) "
                + "values (?, 'Dental', 0, 'BDS', 'REG-A-1')", inA);
        commit(b.tenantId(), "insert into platform.doctor_profile (membership_id, specialty, fee_paise, degree, registration_no) "
                + "values (?, 'Paediatrician', 0, 'MD Paeds', 'REG-B-2')", inB);
        JsonNode inClinicA = get(ME, new Staff(inA, user, "VISITING_DOCTOR").token(a));
        JsonNode inClinicB = get(ME, new Staff(inB, user, "VISITING_DOCTOR").token(b));
        assertThat(inClinicA.get("registrationNo").asText()).isEqualTo("REG-A-1");
        assertThat(inClinicA.get("template").get("templateKey").asText()).isEqualTo("dental");
        assertThat(inClinicB.get("registrationNo").asText()).isEqualTo("REG-B-2");
        assertThat(inClinicB.get("degree").asText()).isEqualTo("MD Paeds");
        assertThat(inClinicB.get("template").get("templateKey").asText()).isEqualTo("paeds");
    }

    // ---------------- the platform.api port ----------------

    @Test
    void theEmrReadsDoctorDetailsThroughThePortAndDoesNotKeepACopy() throws Exception {
        List<List<String>> copies = globalRows("select table_name || '.' || column_name from information_schema.columns "
                + "where table_schema = 'opd' and column_name in ('registration_no', 'degree', 'doctor_name', 'specialty_text') "
                + "and table_name like 'emr\\_%'");
        assertThat(copies).as("no second copy of the doctor's fields in the EMR tables").isEmpty();
    }

    @Test
    void anotherClinicsDoctorIsNeverReturnedByThePort() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        Staff doctorA = addDoctor(a, "Dental");
        Authentication asB = authentication(b.tenantId(), b.ownerUserId(), "OWNER");
        Optional<DoctorDetails> seenByB = runAs(asB, () -> tx().execute(s -> teamMembers.doctorDetails(b.tenantId(), doctorA.membershipId())));
        assertThat(seenByB).isEmpty();
        Authentication asA = authentication(a.tenantId(), a.ownerUserId(), "OWNER");
        Optional<DoctorDetails> seenByA = runAs(asA, () -> tx().execute(s -> teamMembers.doctorDetails(a.tenantId(), doctorA.membershipId())));
        assertThat(seenByA).isPresent();
        assertThat(seenByA.get().specialty()).isEqualTo("Dental");
        // asking for A's tenant while B is bound still returns nothing (row level security)
        Optional<DoctorDetails> crossAsk = runAs(asB, () -> tx().execute(s -> teamMembers.doctorDetails(a.tenantId(), doctorA.membershipId())));
        assertThat(crossAsk).isEmpty();
    }

    @Test
    void aDoctorWhoLeftTheClinicStillHasTheirNameDegreeAndRegistrationForEarlierRecords() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addStaff(clinic, "DOCTOR", "Dr Left Clinic", "Dental", "BDS", "REG-LEFT-1");
        setMembershipStatusOf(clinic, doctor, "REMOVED");
        Authentication auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");
        Optional<DoctorDetails> details = runAs(auth, () -> tx().execute(s -> teamMembers.doctorDetails(clinic.tenantId(), doctor.membershipId())));
        assertThat(details).isPresent();
        assertThat(details.get().name()).isEqualTo("Dr Left Clinic");
        assertThat(details.get().degree()).isEqualTo("BDS");
        assertThat(details.get().registrationNo()).isEqualTo("REG-LEFT-1");
        assertThat(details.get().membershipStatus()).isEqualTo("REMOVED");
        // but they cannot open the EMR any more
        problemOf(HttpMethod.GET, ME, doctor.token(clinic), null, 403, "forbidden");
    }

    @Test
    void aDoctorWithoutADoctorProfileRowStillOpensWithEverythingMissing() throws Exception {
        Clinic clinic = newClinic();
        UUID user = newClinic().ownerUserId();
        addMembership(clinic.tenantId(), user, "DOCTOR");
        UUID membershipId = UUID.fromString(row(clinic, "select id::text from platform.membership where user_id = ?::uuid and role = 'DOCTOR'", user).get(0));
        JsonNode me = get(ME, new Staff(membershipId, user, "DOCTOR").token(clinic));
        assertThat(me.get("specialty").isNull()).isTrue();
        assertThat(texts(me.get("missing"), null)).containsExactly("DEGREE", "REGISTRATION_NO");
        assertThat(me.get("template").get("templateKey").asText()).isEqualTo("gp");
    }

    // ---------------- the snapshot rule (SAH-44-4) ----------------

    private TemplateSnapshot snapshot(Clinic clinic, String key, String source) {
        Authentication auth = authentication(clinic.tenantId(), clinic.ownerUserId(), "OWNER");
        return runAs(auth, () -> tx().execute(s -> resolver.snapshot(clinic.tenantId(), key, source)));
    }

    @Test
    void aSnapshotHoldsTheShownSectionsInTemplateOrderWithTheEffectiveRequiredFlag() throws Exception {
        Clinic clinic = newClinic();
        TemplateSnapshot snap = snapshot(clinic, "gp", "DOCTOR_SPECIALTY");
        assertThat(snap.schemaVersion()).isEqualTo(1);
        assertThat(snap.templateKey()).isEqualTo("gp");
        assertThat(snap.templateVersion()).isEqualTo(1);
        assertThat(snap.choiceSource()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(snap.takenAt()).isNotNull();
        assertThat(snap.sections()).hasSize(11);
        assertThat(snap.sections().stream().map(s -> s.key()).toList()).containsExactly("chief_complaints",
                "history_allergies", "vitals", "general_exam", "systemic_exam", "local_exam", "diagnosis", "prescription",
                "investigations", "services", "advice_followup");
        assertThat(snap.sections().stream().filter(s -> s.required()).count()).isEqualTo(6);
        assertThat(snap.sections().get(0).label()).isEqualTo("Chief complaints");
        assertThat(snap.sections().get(0).part()).isEqualTo("ASSESSMENT");
        assertThat(snap.sections().get(0).editorType()).isEqualTo("COMPLAINTS");
        assertThat(snap.sections().get(0).options().source()).isEqualTo("MASTER");
        assertThat(snap.sections().get(0).options().master()).isEqualTo("complaints");
    }

    @Test
    void anOwnersChangeAppliesToSnapshotsTakenAfterItAndNotToOnesTakenBefore() throws Exception {
        Clinic a = newClinic();
        Clinic b = newClinic();
        TemplateSnapshot before = snapshot(a, "gp", "DEFAULT");
        put(overrideUrl("gp", "local_exam"), a.owner(), overrideBody(false, false, 0), 200);
        put(overrideUrl("gp", "systemic_exam"), a.owner(), overrideBody(true, true, 1), 200);
        TemplateSnapshot after = snapshot(a, "gp", "DEFAULT");
        assertThat(before.sections()).hasSize(11);
        assertThat(before.sections().stream().anyMatch(s -> s.key().equals("local_exam"))).isTrue();
        assertThat(before.sections().stream().filter(s -> s.key().equals("systemic_exam")).findFirst().get().required()).isFalse();
        assertThat(after.sections()).hasSize(10);
        assertThat(after.sections().stream().anyMatch(s -> s.key().equals("local_exam"))).isFalse();
        assertThat(after.sections().stream().filter(s -> s.key().equals("systemic_exam")).findFirst().get().required()).isTrue();
        // another clinic is not affected
        assertThat(snapshot(b, "gp", "DEFAULT").sections()).hasSize(11);
    }

    @Test
    void aSnapshotOfATemplateThatIsNotOneOfTheFourOrWithAnUnknownSourceIsABadRequest() throws Exception {
        Clinic clinic = newClinic();
        for (String key : new String[] {"ayur", "physio", "derma", "gynae", "ent", "oncology", "surgery", "GP", "", "gp "}) {
            assertThatThrownBy(() -> snapshot(clinic, key, "SWITCHED")).as("key '" + key + "'")
                    .isInstanceOf(EmrProblemException.class);
        }
        assertThatThrownBy(() -> snapshot(clinic, "gp", "WHIM")).isInstanceOf(EmrProblemException.class);
        assertThatThrownBy(() -> snapshot(clinic, "gp", null)).isInstanceOf(EmrProblemException.class);
        assertThat(snapshot(clinic, "homeo", "SWITCHED").sections()).hasSize(7);
        assertThat(snapshot(clinic, "paeds", "CLINIC_SPECIALTY").sections()).hasSize(9);
        assertThat(snapshot(clinic, "dental", "DOCTOR_SPECIALTY").sections()).hasSize(11);
    }
}
