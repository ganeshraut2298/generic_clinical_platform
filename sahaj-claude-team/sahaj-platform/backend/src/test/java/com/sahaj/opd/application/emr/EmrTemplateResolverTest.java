package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrSetupRepository.OverrideRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.SectionRow;
import com.sahaj.opd.application.emr.EmrTemplateRepository.TemplateHeader;
import com.sahaj.opd.application.emr.EmrViews.TemplateChoice;
import com.sahaj.opd.application.emr.EmrViews.TemplateSnapshot;
import com.sahaj.platform.api.ClinicSpecialtyPort;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-44-4, ADR-019 D6: template choice (doctor specialty, clinic specialty, default) and the section snapshot. */
class EmrTemplateResolverTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-11T05:35:00Z");

    private TeamMemberPort team;
    private ClinicSpecialtyPort clinic;
    private EmrTemplateRepository templates;
    private EmrSetupRepository setup;
    private EmrTemplateResolver resolver;

    @BeforeEach
    void setUp() {
        team = mock(TeamMemberPort.class);
        clinic = mock(ClinicSpecialtyPort.class);
        templates = mock(EmrTemplateRepository.class);
        setup = mock(EmrSetupRepository.class);
        resolver = new EmrTemplateResolver(team, clinic, templates, setup, Clock.fixed(NOW, ZoneOffset.UTC));

        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.empty());
        when(templates.templateKeyForAlias(any())).thenReturn(Optional.empty());
        for (String[] row : new String[][] {{"gp", "General practice"}, {"dental", "Dental"},
                {"paeds", "Paediatrics"}, {"homeo", "Homeopathy"}}) {
            when(templates.newest(row[0])).thenReturn(Optional.of(new TemplateHeader(row[0], 1, row[1])));
        }
    }

    private void doctorSpecialty(String specialty) {
        when(team.doctorDetails(TENANT, DOCTOR)).thenReturn(Optional.of(
                new DoctorDetails(DOCTOR, "Test Doctor", "DOCTOR", "ACTIVE", specialty, null, null)));
    }

    @Test
    void theDoctorsSpecialtyDecidesThroughTheAliasList() {
        doctorSpecialty("Dental Surgeon");
        when(templates.templateKeyForAlias("dental surgeon")).thenReturn(Optional.of("dental"));
        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("paeds"));

        TemplateChoice choice = resolver.choose(TENANT, DOCTOR);

        assertThat(choice.templateKey()).isEqualTo("dental");
        assertThat(choice.templateVersion()).isEqualTo(1);
        assertThat(choice.templateName()).isEqualTo("Dental");
        assertThat(choice.choiceSource()).isEqualTo("DOCTOR_SPECIALTY");
    }

    @Test
    void theMatchIgnoresLetterCaseAndExtraSpaces() {
        when(templates.templateKeyForAlias("general physician")).thenReturn(Optional.of("gp"));

        assertThat(resolver.chooseForSpecialty(TENANT, "  GENERAL    Physician ").choiceSource())
                .isEqualTo("DOCTOR_SPECIALTY");
        assertThat(EmrTemplateResolver.normalise("  GENERAL \t Physician\n")).isEqualTo("general physician");
    }

    @Test
    void anUnlistedSpecialtyFallsBackToTheClinicsSpecialty() {
        doctorSpecialty("Cardiology");
        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("paeds"));

        TemplateChoice choice = resolver.choose(TENANT, DOCTOR);

        assertThat(choice.templateKey()).isEqualTo("paeds");
        assertThat(choice.choiceSource()).isEqualTo("CLINIC_SPECIALTY");
    }

    @Test
    void anEmptySpecialtyFallsBackToTheClinicsSpecialtyWithoutAskingTheAliasList() {
        doctorSpecialty("   ");
        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("homeo"));

        assertThat(resolver.choose(TENANT, DOCTOR).templateKey()).isEqualTo("homeo");
        org.mockito.Mockito.verify(templates, org.mockito.Mockito.never()).templateKeyForAlias(any());
    }

    @Test
    void aClinicSpecialtyWithNoTemplateIsIgnored() {
        doctorSpecialty(null);
        when(clinic.specialtyCode(TENANT)).thenReturn(Optional.of("ayur"));

        TemplateChoice choice = resolver.choose(TENANT, DOCTOR);

        assertThat(choice.templateKey()).isEqualTo("gp");
        assertThat(choice.choiceSource()).isEqualTo("DEFAULT");
    }

    @Test
    void nothingAtAllGivesGeneralPracticeFromTheDefault() {
        when(team.doctorDetails(TENANT, DOCTOR)).thenReturn(Optional.empty());

        TemplateChoice choice = resolver.choose(TENANT, DOCTOR);

        assertThat(choice.templateKey()).isEqualTo("gp");
        assertThat(choice.templateName()).isEqualTo("General practice");
        assertThat(choice.choiceSource()).isEqualTo("DEFAULT");
    }

    // ---- snapshot ----

    private static SectionRow section(String key, int position, String part, boolean requiredDefault, boolean hideable,
                                      String source, String master) {
        return new SectionRow(key, position, "Label " + key, part, requiredDefault, hideable, "CHIP_LIST", null,
                source, master);
    }

    @Test
    void theSnapshotHoldsOnlyShownSectionsInOrderWithTheEffectiveRequiredFlag() {
        when(templates.sections("gp", 1)).thenReturn(List.of(
                section("chief_complaints", 1, "ASSESSMENT", true, true, "MASTER", "complaints"),
                section("local_exam", 2, "ASSESSMENT", false, true, "TEMPLATE", null),
                section("vitals", 3, "ASSESSMENT", true, true, "NONE", null),
                section("prescription", 4, "TREATMENT", true, false, "MASTER", "medicines")));
        when(setup.overrides(TENANT, "gp")).thenReturn(List.of(
                new OverrideRow("local_exam", false, false),
                new OverrideRow("vitals", true, false),
                new OverrideRow("chief_complaints", true, true)));

        TemplateSnapshot snapshot = resolver.snapshot(TENANT, "gp", "DOCTOR_SPECIALTY");

        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.templateKey()).isEqualTo("gp");
        assertThat(snapshot.templateVersion()).isEqualTo(1);
        assertThat(snapshot.choiceSource()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(snapshot.takenAt()).isEqualTo(NOW);
        assertThat(snapshot.sections()).extracting(EmrViews.SnapshotSection::key)
                .containsExactly("chief_complaints", "vitals", "prescription");
        assertThat(snapshot.sections()).extracting(EmrViews.SnapshotSection::required)
                .containsExactly(true, false, true);
        assertThat(snapshot.sections().get(0).options().source()).isEqualTo("MASTER");
        assertThat(snapshot.sections().get(0).options().master()).isEqualTo("complaints");
        assertThat(snapshot.sections().get(1).options().master()).isNull();
    }

    @Test
    void aSwitchedSnapshotRecordsTheSource() {
        when(templates.sections("dental", 1)).thenReturn(List.of(
                section("chief_complaints", 1, "ASSESSMENT", true, true, "MASTER", "complaints")));
        when(setup.overrides(TENANT, "dental")).thenReturn(List.of());

        assertThat(resolver.snapshot(TENANT, "dental", EmrTemplateResolver.SWITCHED).choiceSource())
                .isEqualTo("SWITCHED");
    }

    @Test
    void aKeyThatIsNotOneOfTheFourIsRefusedAsBadRequest() {
        assertThatThrownBy(() -> resolver.snapshot(TENANT, "ayur", "DEFAULT"))
                .isInstanceOfSatisfying(EmrProblemException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(400);
                    assertThat(ex.slug()).isEqualTo("bad-request");
                });
        assertThatThrownBy(() -> resolver.snapshot(TENANT, null, "DEFAULT"))
                .isInstanceOf(EmrProblemException.class);
        assertThatThrownBy(() -> resolver.snapshot(TENANT, "gp", "MADE_UP"))
                .isInstanceOf(EmrProblemException.class);
    }
}
