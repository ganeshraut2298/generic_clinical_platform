package com.sahaj.platform.it;

import static com.sahaj.platform.support.TestTenantSecurity.authentication;
import static com.sahaj.platform.support.TestTenantSecurity.runAs;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * SAH-43-3 (the doctor's own details through {@code platform.api}) and SAH-46-3 (which department the EMR chooses for a
 * doctor: specialty alias, then the doctor's published departments, then the clinic default) on the real database. The
 * record that stores the layout is tested in {@link EmrRecordsIT}. Names, degrees and registration numbers are made up.
 * Test plan: docs/test-plans/SAH-53.md.
 */
@OnTestDatabase
class EmrDoctorContextIT extends EmrSupport {

    @Autowired
    private TeamMemberPort teamMembers;

    private JsonNode department(Clinic clinic, Staff who) throws Exception {
        return get(ME, who.token(clinic)).get("department");
    }

    // ---------------- /emr/me ----------------

    @Test
    void aDoctorGetsTheirOwnDetailsExactlyAsSavedAndTheDepartmentTheirSpecialtyChooses() throws Exception {
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
        assertThat(me.has("template")).as("the SAH-44 template block is gone").isFalse();
        JsonNode department = me.get("department");
        assertThat(department.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(department.get("departmentName").asText()).isEqualTo("General Medicine");
        assertThat(department.get("starterKey").asText()).isEqualTo("gm");
        assertThat(department.get("layoutSource").asText()).isEqualTo("STARTER");
        assertThat(department.get("versionNo").isNull()).isTrue();
        assertThat(department.get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(me.get("departments").size()).isZero();
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
        assertThat(first.get("department").get("departmentKey").asText()).isEqualTo("dental");
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
    void theSpecialtyNamesOfTheAliasListChooseTheirDepartmentIgnoringLetterCaseAndExtraSpaces() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        String[][] cases = {
                {"General Physician", "gm"}, {"physician", "gm"}, {"  GENERAL    physician  ", "gm"}, {"GM", "gm"},
                {"Dental Surgeon", "dental"}, {"dentist", "dental"}, {"DENTAL   SURGEON", "dental"}, {"dental", "dental"},
                {"Paediatrician", "paeds"}, {"pediatrician", "paeds"}, {" paediatrics ", "paeds"},
                {"Gynaecologist", "og"}, {"Obstetrician", "og"}, {"Obstetrics & Gynaecology", "og"}, {"og", "og"},
                {"Physiotherapist", "physio"}, {"Orthopaedic Surgeon", "ortho"}, {"Ophthalmologist", "eye"},
                {"ENT surgeon", "ent"}, {"Dermatologist", "derm"}, {"Diabetologist", "diab"}, {"Cardiologist", "cardio"},
                {"Ayurveda", "ayush"}, {"AYUSH (Ayurveda)", "ayush"}, {"Oncologist", "onco"}, {"General Surgeon", "surgery"},
                {"Psychiatrist", "psych"}, {"Counsellor", "psych"}};
        for (String[] c : cases) {
            setDoctorSpecialty(clinic, doctor, c[0]);
            JsonNode me = get(ME, doctor.token(clinic));
            assertThat(me.get("department").get("departmentKey").asText()).as("specialty '" + c[0] + "'").isEqualTo(c[1]);
            assertThat(me.get("department").get("starterKey").asText()).isEqualTo(c[1]);
            assertThat(me.get("department").get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
            assertThat(me.get("specialty").asText()).as("returned as saved").isEqualTo(c[0].strip());
        }
    }

    @Test
    void aSpecialtyNotInTheListFallsBackToTheClinicDefaultAndSaysSo() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Cardiology consultant");
        // the clinic default: gp -> General Medicine, dental -> Dental, paeds -> Paediatrics, homeo or none -> General Medicine
        String[][] clinicSpecialties = {{"gp", "gm"}, {"dental", "dental"}, {"paeds", "paeds"}, {"homeo", "gm"}};
        for (String[] cs : clinicSpecialties) {
            setClinicSpecialty(clinic, cs[0]);
            for (String odd : new String[] {"Cardiology consultant", "Zxq specialty", "dent", "gp", "General  Practice Clinic",
                    "Homeopath", "homeopathy"}) {
                setDoctorSpecialty(clinic, doctor, odd);
                JsonNode department = department(clinic, doctor);
                assertThat(department.get("departmentKey").asText()).as("clinic " + cs[0] + ", specialty '" + odd + "'").isEqualTo(cs[1]);
                assertThat(department.get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
            }
        }
        // no specialty on the doctor either: still the clinic default
        setClinicSpecialty(clinic, "paeds");
        commit(clinic.tenantId(), "update platform.doctor_profile set specialty = null where membership_id = ?", doctor.membershipId());
        JsonNode viaClinic = get(ME, doctor.token(clinic));
        assertThat(viaClinic.get("specialty").isNull()).isTrue();
        assertThat(viaClinic.get("department").get("departmentKey").asText()).isEqualTo("paeds");
        // neither the doctor nor the clinic has one: General Medicine, from the default
        setClinicSpecialty(clinic, null);
        JsonNode byDefault = department(clinic, doctor);
        assertThat(byDefault.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(byDefault.get("departmentName").asText()).isEqualTo("General Medicine");
        assertThat(byDefault.get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
    }

    @Test
    void thereIsNoHomeopathyDepartmentAndAHomeopathDoctorGetsTheClinicDefault() throws Exception {
        Clinic clinic = newClinic();
        setClinicSpecialty(clinic, "homeo");
        Staff doctor = addDoctor(clinic, "Homeopath");
        JsonNode department = department(clinic, doctor);
        assertThat(department.get("departmentKey").asText()).isEqualTo("gm");
        assertThat(department.get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
        problemOf(HttpMethod.GET, DEPARTMENTS + "/homeo", clinic.owner(), null, 404, "department-not-found");
        problemOf(HttpMethod.GET, STARTERS + "/homeo", clinic.owner(), null, 404, "starter-not-found");
    }

    @Test
    void theDoctorsSpecialtyBeatsTheClinicsDefault() throws Exception {
        Clinic clinic = newClinic();
        setClinicSpecialty(clinic, "dental");
        Staff doctor = addDoctor(clinic, "Paediatrician");
        JsonNode department = department(clinic, doctor);
        assertThat(department.get("departmentKey").asText()).isEqualTo("paeds");
        assertThat(department.get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
    }

    // ---------------- the doctor's published departments (SAH-54-3) ----------------

    private JsonNode createAndPublish(Clinic clinic, String name, String sourceJson, UUID doctor) throws Exception {
        JsonNode created = post(DEPARTMENTS, clinic.owner(), createBody(name, sourceJson, List.of(doctor), List.of("Room 7"), true), 201);
        String key = created.get("departmentKey").asText();
        publishAsIs(clinic, key);
        return department(clinic, key);
    }

    @Test
    void whenTheSpecialtyIsNotInTheListTheDoctorsPublishedDepartmentIsUsedAndAnUnpublishedOneIsNot() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Zxq specialty");
        JsonNode created = post(DEPARTMENTS, clinic.owner(), createBody("Skin and hair clinic", BLANK_SOURCE, List.of(doctor.membershipId()),
                List.of("Room 7"), true), 201);
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        // a draft department is not offered to doctors
        JsonNode before = get(ME, doctor.token(clinic));
        assertThat(before.get("department").get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
        assertThat(before.get("departments").size()).isZero();
        publishAsIs(clinic, created.get("departmentKey").asText());
        JsonNode after = get(ME, doctor.token(clinic));
        assertThat(after.get("department").get("departmentKey").asText()).isEqualTo(created.get("departmentKey").asText());
        assertThat(after.get("department").get("departmentName").asText()).isEqualTo("Skin and hair clinic");
        assertThat(after.get("department").get("starterKey").isNull()).as("a blank department has no starter").isTrue();
        assertThat(after.get("department").get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(after.get("department").get("versionNo").asInt()).isEqualTo(1);
        assertThat(after.get("department").get("choiceSource").asText()).isEqualTo("DOCTOR_DEPARTMENT");
        assertThat(after.get("departments")).hasSize(1);
        assertThat(after.get("departments").get(0).get("name").asText()).isEqualTo("Skin and hair clinic");
        // another doctor of the same clinic is not assigned and still gets the default
        Staff other = addDoctor(clinic, "Zxq specialty");
        assertThat(department(clinic, other).get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
    }

    @Test
    void withSeveralDepartmentsTheOneOfTheClinicsSpecialtyIsUsedElseTheFirstByNameAndTheSpecialtyAliasBeatsBoth() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "Zxq specialty");
        JsonNode dental = createAndPublish(clinic, "Annexe dental", starterSource("dental"), doctor.membershipId());
        JsonNode medicine = createAndPublish(clinic, "Annexe medicine", starterSource("gm"), doctor.membershipId());
        // clinic default (gp -> gm): the department started from General Medicine wins although "Annexe dental" sorts first
        JsonNode chosen = department(clinic, doctor);
        assertThat(chosen.get("departmentKey").asText()).isEqualTo(medicine.get("departmentKey").asText());
        assertThat(chosen.get("choiceSource").asText()).isEqualTo("DOCTOR_DEPARTMENT");
        assertThat(get(ME, doctor.token(clinic)).get("departments")).hasSize(2);
        setClinicSpecialty(clinic, "dental");
        assertThat(department(clinic, doctor).get("departmentKey").asText()).isEqualTo(dental.get("departmentKey").asText());
        // no assigned department matches the clinic: the first by name
        Staff second = addDoctor(clinic, "Zxq specialty");
        JsonNode zeta = createAndPublish(clinic, "Zeta clinic", BLANK_SOURCE, second.membershipId());
        JsonNode alpha = createAndPublish(clinic, "Alpha clinic", BLANK_SOURCE, second.membershipId());
        assertThat(department(clinic, second).get("departmentKey").asText()).as("Alpha before Zeta")
                .isEqualTo(alpha.get("departmentKey").asText()).isNotEqualTo(zeta.get("departmentKey").asText());
        // a specialty that is in the alias list beats the assigned departments
        setDoctorSpecialty(clinic, second, "Paediatrician");
        JsonNode bySpecialty = department(clinic, second);
        assertThat(bySpecialty.get("departmentKey").asText()).isEqualTo("paeds");
        assertThat(bySpecialty.get("choiceSource").asText()).isEqualTo("DOCTOR_SPECIALTY");
    }

    @Test
    void thePublishedVersionOfTheDoctorsDepartmentIsWhatTheContextReports() throws Exception {
        Clinic clinic = newClinic();
        Staff doctor = addDoctor(clinic, "General physician");
        assertThat(department(clinic, doctor).get("layoutSource").asText()).isEqualTo("STARTER");
        publishAsIs(clinic, "gm");
        JsonNode v1 = department(clinic, doctor);
        assertThat(v1.get("layoutSource").asText()).isEqualTo("PUBLISHED");
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        publishOneChange(clinic, "gm", "chronic", false);
        assertThat(department(clinic, doctor).get("versionNo").asInt()).isEqualTo(2);
    }

    // ---------------- who may read ----------------

    @Test
    void anOwnerWithDoctorDetailsGetsThemAnOwnerWithoutIsToldToAddThem() throws Exception {
        Clinic clinic = bareOwnerClinic();
        JsonNode problem = problemOf(HttpMethod.GET, ME, clinic.owner(), null, 403, "doctor-details-missing");
        assertThat(problem.get("detail").asText()).isEqualTo("Add your doctor details in Team and access to write consultations.");
        ownerWithDoctorDetails(clinic, "Dental surgeon", "BDS, MDS", "MH-OWN-77");
        JsonNode me = get(ME, clinic.owner());
        assertThat(me.get("role").asText()).isEqualTo("OWNER");
        assertThat(me.get("degree").asText()).isEqualTo("BDS, MDS");
        assertThat(me.get("registrationNo").asText()).isEqualTo("MH-OWN-77");
        assertThat(me.get("department").get("departmentKey").asText()).isEqualTo("dental");
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
    void aVisitingDoctorOfTwoClinicsGetsEachClinicsOwnSavedDetailsAndDepartment() throws Exception {
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
        assertThat(inClinicA.get("department").get("departmentKey").asText()).isEqualTo("dental");
        assertThat(inClinicB.get("registrationNo").asText()).isEqualTo("REG-B-2");
        assertThat(inClinicB.get("degree").asText()).isEqualTo("MD Paeds");
        assertThat(inClinicB.get("department").get("departmentKey").asText()).isEqualTo("paeds");
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
        assertThat(me.get("department").get("departmentKey").asText()).isEqualTo("gm");
        assertThat(me.get("department").get("choiceSource").asText()).isEqualTo("CLINIC_DEFAULT");
    }
}
