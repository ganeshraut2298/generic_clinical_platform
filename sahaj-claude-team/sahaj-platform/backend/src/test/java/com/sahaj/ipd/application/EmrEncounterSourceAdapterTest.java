package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.opd.api.EmrEncounterSource.Access;
import com.sahaj.opd.api.EmrEncounterSource.AdmissionFacts;
import com.sahaj.opd.api.EmrEncounterSource.EmergencyCaseFacts;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import com.sahaj.platform.support.TestTenantSecurity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-46-4 (ADR-020 D9): the IPD side of the port that lets the Doctor EMR open an admission or an Emergency case. */
class EmrEncounterSourceAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID ADMISSION = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();

    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final EmergencyCaseRepository cases = mock(EmergencyCaseRepository.class);
    private final ScopeGuard guard = mock(ScopeGuard.class);
    private final AdmissionService admissionService = mock(AdmissionService.class);
    private final IpdCatalog catalog = mock(IpdCatalog.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private EmrEncounterSourceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new EmrEncounterSourceAdapter(admissions, cases, guard, admissionService, catalog, team);
        AdmissionRow row = new AdmissionRow(ADMISSION, "26-27/0001", PATIENT, UUID.randomUUID(), "B1", "general",
                "Ward A", MEMBER, "PLANNED", "SELF", "reason", "ADMITTED", Instant.parse("2026-11-10T04:00:00Z"), 0,
                null);
        when(admissions.find(TENANT, ADMISSION)).thenReturn(Optional.of(row));
        when(admissionService.emrHeader(TENANT, row)).thenReturn(new AdmissionService.EmrHeader("Dr X", 3));
        CaseRow caseRow = new CaseRow(CASE, null, "Unknown male, about 40", 40, "M", null, "chest pain", true, 1, "Bay 1",
                "WAITING", Instant.parse("2026-11-12T04:00:00Z"), USER, null, null, null, null, null, UUID.randomUUID(),
                1);
        when(cases.find(TENANT, CASE)).thenReturn(Optional.of(caseRow));
        when(catalog.emergencySetup(TENANT)).thenReturn(IpdSettingsRepository.EmergencySetup.DEFAULTS);
    }

    private void member(String role) {
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.of(new TeamMember(MEMBER, "Name", role, true,
                0)));
    }

    private <T> T as(String tokenRole, java.util.function.Supplier<T> action) {
        return TestTenantSecurity.runAs(TestTenantSecurity.authentication(TENANT, USER, tokenRole), action);
    }

    @Test
    void aDoctorReachesAnAdmissionAndGetsTheBilledBedDay() {
        member("DOCTOR");

        AdmissionFacts facts = as("DOCTOR", () -> adapter.admission(TENANT, ADMISSION)).orElseThrow();

        assertThat(facts.access()).isEqualTo(Access.ALLOWED);
        assertThat(facts.dayNumber()).isEqualTo(3);
        assertThat(facts.patientId()).isEqualTo(PATIENT);
        assertThat(facts.status()).isEqualTo("ADMITTED");
        assertThat(facts.admittingDoctorMembershipId()).isEqualTo(MEMBER);
        assertThat(facts.toString()).doesNotContain("26-27");
        verify(guard).requireAdmission(eq(TENANT), eq(USER), eq("DOCTOR"), eq("DOCTOR"), eq(ADMISSION), anyString());
    }

    @Test
    void aCallerOutsideTheIpdScopeIsOutsideScopeAndTheGuardLogsTheRefusalAsItDoesToday() {
        member("OWNER");
        doThrow(new OutsideScopeException()).when(guard).requireAdmission(any(), any(), any(), any(), any(),
                anyString());

        AdmissionFacts facts = as("OWNER", () -> adapter.admission(TENANT, ADMISSION)).orElseThrow();

        assertThat(facts.access()).isEqualTo(Access.OUTSIDE_SCOPE);
        verify(admissionService, never()).emrHeader(any(), any());
    }

    @Test
    void aVisitingDoctorANurseAndEveryOtherRoleAreForbiddenWithoutAScopeRead() {
        for (String role : new String[] {"VISITING_DOCTOR", "NURSE", "FRONT_DESK", "BILLING", "PHARMACIST",
                "COUNSELLOR"}) {
            member(role);
            assertThat(as(role, () -> adapter.admission(TENANT, ADMISSION)).orElseThrow().access())
                    .as(role).isEqualTo(Access.FORBIDDEN);
            assertThat(as(role, () -> adapter.emergencyCase(TENANT, CASE)).orElseThrow().access())
                    .as(role).isEqualTo(Access.FORBIDDEN);
        }
        verify(guard, never()).requireAdmission(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    void theRoleInTheTokenAndTheRoleOfTheMembershipMustBothAllow() {
        member("NURSE");
        assertThat(as("DOCTOR", () -> adapter.admission(TENANT, ADMISSION)).orElseThrow().access())
                .isEqualTo(Access.FORBIDDEN);
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.empty());
        assertThat(as("DOCTOR", () -> adapter.admission(TENANT, ADMISSION)).orElseThrow().access())
                .isEqualTo(Access.FORBIDDEN);
    }

    @Test
    void anAdmissionOrCaseOfAnotherClinicIsAbsent() {
        member("DOCTOR");
        UUID other = UUID.randomUUID();
        when(admissions.find(TENANT, other)).thenReturn(Optional.empty());
        when(cases.find(TENANT, other)).thenReturn(Optional.empty());

        assertThat(as("DOCTOR", () -> adapter.admission(TENANT, other))).isEmpty();
        assertThat(as("DOCTOR", () -> adapter.emergencyCase(TENANT, other))).isEmpty();
    }

    @Test
    void anEmergencyCaseHasNoPatientUntilAdmittedAndCarriesItsTriageLevelName() {
        member("DOCTOR");

        EmergencyCaseFacts facts = as("DOCTOR", () -> adapter.emergencyCase(TENANT, CASE)).orElseThrow();

        assertThat(facts.access()).isEqualTo(Access.ALLOWED);
        assertThat(facts.patientId()).isNull();
        assertThat(facts.triageLevel()).isEqualTo(1);
        assertThat(facts.triageLevelName()).isEqualTo("Red");
        assertThat(facts.status()).isEqualTo("WAITING");
        assertThat(facts.displayName()).isEqualTo("Unknown male, about 40");
        assertThat(facts.age()).isEqualTo(40);
        assertThat(facts.toString()).doesNotContain("Unknown male");
    }
}
