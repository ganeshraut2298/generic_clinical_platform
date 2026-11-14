package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrDepartmentRepository.DepartmentRow;
import com.sahaj.opd.application.emr.EmrDepartmentRepository.VersionRow;
import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterLayoutRow;
import com.sahaj.opd.application.emr.EmrLibraryRepository.StarterRow;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmrDepartmentResolverTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();

    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final ClinicSpecialtyPort clinic = mock(ClinicSpecialtyPort.class);
    private final EmrLibraryRepository library = mock(EmrLibraryRepository.class);
    private final EmrDepartmentRepository departments = mock(EmrDepartmentRepository.class);
    private EmrDepartmentResolver resolver;

    @BeforeEach
    void setUp() {
        String layout = EmrTestData.JSON.writeLayout(List.of(EmrTestData.section("vitals", true, false)));
        List<StarterRow> starters = new java.util.ArrayList<>();
        for (String key : EmrKeys.STARTER_KEYS) {
            starters.add(new StarterRow(key, "Name " + key, "XX", "#1D5FA8", null, false, false, 1, 1));
            when(library.starter(key)).thenReturn(Optional.of(new StarterRow(key, "Name " + key, "XX", "#1D5FA8", null,
                    false, false, 1, 1)));
            when(library.currentStarterLayout(key)).thenReturn(Optional.of(new StarterLayoutRow(key, 1, layout)));
        }
        when(library.starters()).thenReturn(starters);
        when(library.starterForAlias("general physician")).thenReturn(Optional.of("gm"));
        when(library.starterForAlias("dentist")).thenReturn(Optional.of("dental"));
        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.empty());
        EmrLayouts layouts = new EmrLayouts(library, departments, EmrTestData.MAPPER);
        resolver = new EmrDepartmentResolver(team, clinic, library, departments, layouts);
    }

    private DepartmentRow published(String key, String name, String starter, int version) {
        return new DepartmentRow(UUID.randomUUID(), key, name, "AB", "#2E7D32", starter, "COPY", null, List.of(), null,
                version, 3);
    }

    private void publishedVersion(DepartmentRow row) {
        when(departments.find(TENANT, row.key())).thenReturn(Optional.of(row));
        when(departments.latestVersion(TENANT, row.id())).thenReturn(Optional.of(new VersionRow(UUID.randomUUID(),
                row.id(), row.publishedVersion(), EmrTestData.JSON.writeLayout(
                        List.of(EmrTestData.section("vitals", true, false))), Instant.now(), DOCTOR)));
    }

    @Test
    void theDoctorsSpecialtyMatchesTheAliasListIgnoringCaseAndExtraSpaces() {
        Resolution resolution = resolver.chooseForSpecialty(TENANT, DOCTOR, "  General   PHYSICIAN ");

        assertThat(resolution.choiceSource()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(resolution.department().key()).isEqualTo("gm");
        assertThat(resolution.choice().layoutSource()).isEqualTo("STARTER");
        assertThat(resolution.choice().versionNo()).isNull();
        assertThat(resolution.published().starterLayoutVersion()).isEqualTo(1);
    }

    @Test
    void aPublishedStarterDepartmentUsesItsLatestVersion() {
        publishedVersion(published("dental", "Dental", "dental", 6));

        Resolution resolution = resolver.chooseForSpecialty(TENANT, DOCTOR, "Dentist");

        assertThat(resolution.choice().layoutSource()).isEqualTo("PUBLISHED");
        assertThat(resolution.choice().versionNo()).isEqualTo(6);
        assertThat(resolution.published().versionId()).isNotNull();
    }

    @Test
    void withNoMatchingSpecialtyTheDoctorsPublishedDepartmentIsUsed() {
        DepartmentRow assigned = published("d_abcdefghij", "Annexe", "gm", 2);
        publishedVersion(assigned);
        when(departments.publishedForDoctor(TENANT, DOCTOR)).thenReturn(List.of(assigned));

        Resolution resolution = resolver.chooseForSpecialty(TENANT, DOCTOR, "Homeopath");

        assertThat(resolution.choiceSource()).isEqualTo("DOCTOR_DEPARTMENT");
        assertThat(resolution.department().key()).isEqualTo("d_abcdefghij");
        assertThat(resolution.choice().versionNo()).isEqualTo(2);
    }

    @Test
    void withSeveralDepartmentsTheOneOfTheClinicDefaultWinsElseTheFirstByName() {
        DepartmentRow first = published("d_aaaaaaaaaa", "A first", "ortho", 1);
        DepartmentRow dental = published("d_bbbbbbbbbb", "B dental", "dental", 1);
        publishedVersion(first);
        publishedVersion(dental);
        when(departments.publishedForDoctor(TENANT, DOCTOR)).thenReturn(List.of(first, dental));

        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("dental"));
        assertThat(resolver.chooseForSpecialty(TENANT, DOCTOR, null).department().key()).isEqualTo("d_bbbbbbbbbb");

        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("paeds"));
        assertThat(resolver.chooseForSpecialty(TENANT, DOCTOR, null).department().key()).isEqualTo("d_aaaaaaaaaa");
    }

    @Test
    void theClinicDefaultIsTheLastResortAndHomeopathyOrNothingMeansGeneralMedicine() {
        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("dental"));
        Resolution dental = resolver.chooseForSpecialty(TENANT, DOCTOR, "unknown specialty");
        assertThat(dental.choiceSource()).isEqualTo("CLINIC_DEFAULT");
        assertThat(dental.department().key()).isEqualTo("dental");

        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("homeo"));
        assertThat(resolver.chooseForSpecialty(TENANT, DOCTOR, "").department().key()).isEqualTo("gm");

        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.empty());
        assertThat(resolver.chooseForSpecialty(TENANT, DOCTOR, null).department().key()).isEqualTo("gm");
    }

    @Test
    void theDoctorsSpecialtyIsReadThroughThePlatformPort() {
        when(team.doctorDetails(TENANT, DOCTOR)).thenReturn(Optional.of(new DoctorDetails(DOCTOR, "Dr X", "DOCTOR",
                "ACTIVE", "Dentist", "BDS", null)));

        assertThat(resolver.choose(TENANT, DOCTOR).department().key()).isEqualTo("dental");
    }

    @Test
    void switchingAllowsAnyDepartmentVisibleToDoctorsAndRefusesAnUnpublishedAddedOne() {
        DepartmentRow unpublished = published("d_abcdefghij", "Annexe", "gm", 0);
        when(departments.find(TENANT, "d_abcdefghij")).thenReturn(Optional.of(unpublished));

        Resolution ortho = resolver.switchTo(TENANT, "ortho");
        assertThat(ortho.choiceSource()).isEqualTo("SWITCHED");
        assertThat(ortho.department().key()).isEqualTo("ortho");

        assertThatThrownBy(() -> resolver.switchTo(TENANT, "d_abcdefghij")).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("department-not-published"));
        assertThatThrownBy(() -> resolver.switchTo(TENANT, "d_zzzzzzzzzz")).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("department-not-found"));
        assertThatThrownBy(() -> resolver.switchTo(TENANT, "Bad Key")).isInstanceOfSatisfying(
                EmrProblemException.class, ex -> assertThat(ex.slug()).isEqualTo("bad-request"));
    }

    @Test
    void theSwitchListHasTheFifteenStartersThenPublishedAddedDepartments() {
        DepartmentRow renamed = new DepartmentRow(UUID.randomUUID(), "gm", "Family medicine", "FM", "#1D5FA8", "gm",
                "STARTER", null, List.of(), null, 0, 1);
        DepartmentRow published = published("d_abcdefghij", "Annexe", "gm", 1);
        DepartmentRow draftOnly = published("d_klmnopqrst", "Hidden", "gm", 0);
        when(departments.findAll(TENANT)).thenReturn(List.of(renamed, published, draftOnly));

        List<EmrViews.DepartmentRef> options = resolver.switchOptions(TENANT);

        assertThat(options).hasSize(16);
        assertThat(options.get(0).departmentKey()).isEqualTo("gm");
        assertThat(options.get(15).departmentKey()).isEqualTo("d_abcdefghij");
        assertThat(options).extracting(EmrViews.DepartmentRef::departmentKey).doesNotContain("d_klmnopqrst");
    }

    @Test
    void normalisingTrimsLowersAndCollapsesSpaces() {
        assertThat(EmrDepartmentResolver.normalise("  Ent   Surgeon ")).isEqualTo("ent surgeon");
        assertThat(EmrDepartmentResolver.normalise("   ")).isNull();
        assertThat(EmrDepartmentResolver.normalise(null)).isNull();
    }
}
