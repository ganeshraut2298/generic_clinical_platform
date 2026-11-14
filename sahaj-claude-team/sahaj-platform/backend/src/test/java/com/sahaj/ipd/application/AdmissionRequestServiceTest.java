package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmittedRef;
import com.sahaj.ipd.application.AdmissionRequestService.Row;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.opd.api.EmrAdmissionRequestPort;
import com.sahaj.opd.api.EmrAdmissionRequestPort.OpenRequest;
import com.sahaj.opd.api.TreatmentCoursePort;
import com.sahaj.opd.api.TreatmentCoursePort.RequestedSitting;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-67-3 (ADR-025 D10, H16): the "Admission requests" list: the doctors' open requests and the course sittings waiting for
 * a bed, newest first, for the admit roles only. A patient already admitted shows the IP number and no Admit.
 */
@SuppressWarnings("unchecked")
class AdmissionRequestServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-13T05:00:00Z");

    private final EmrAdmissionRequestPort requests = mock(EmrAdmissionRequestPort.class);
    private final TreatmentCoursePort courses = mock(TreatmentCoursePort.class);
    private final PatientLookupPort patients = mock(PatientLookupPort.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final EmergencyCaseRepository cases = mock(EmergencyCaseRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private AdmissionRequestService service;

    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @BeforeEach
    void setUp() {
        service = new AdmissionRequestService(provider(requests), provider(courses), patients, team, cases, admissions,
                new IpdGate(entitlements, Clock.fixed(NOW, ZoneOffset.UTC)));
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("on", false, 10));
        when(team.find(TENANT, DOCTOR)).thenReturn(Optional.of(new TeamMember(DOCTOR, "Dr Rao", "DOCTOR", true, 0)));
        when(patients.find(TENANT, PATIENT)).thenReturn(Optional.of(new PatientSummary(PATIENT, "Asha Rao", 30, "F",
                "9000000000", false)));
        when(admissions.findAdmittedOf(TENANT, PATIENT)).thenReturn(Optional.empty());
    }

    private OpenRequest request(UUID patient, UUID caseId, Instant at) {
        return new OpenRequest(UUID.randomUUID(), UUID.randomUUID(), patient, caseId, "planned", 3, DOCTOR, "Appendicitis",
                null, "Near nurses", at);
    }

    @Test
    void emrRequestsAndCourseSittingsAreMergedNewestFirstWithNamesAndTypes() {
        when(requests.listOpen(TENANT)).thenReturn(List.of(request(PATIENT, null, NOW.minusSeconds(100)),
                request(null, CASE, NOW.minusSeconds(10))));
        when(cases.find(TENANT, CASE)).thenReturn(Optional.of(new CaseRow(CASE, null, "Unknown male", 40, "M", null, "x",
                false, 1, "B1", "SEEN", NOW, DOCTOR, null, null, null, null, null, UUID.randomUUID(), 0)));
        when(courses.openAdmissionSittings(TENANT)).thenReturn(List.of(new RequestedSitting(UUID.randomUUID(), "TC-0412",
                UUID.randomUUID(), 2, PATIENT, "DAY_CARE", LocalDate.of(2026, 11, 13), DOCTOR)));

        AdmissionRequestService.RequestList list = service.list(TENANT, "FRONT_DESK");

        assertThat(list.canAdmit()).isTrue();
        assertThat(list.items()).extracting(Row::kind).containsExactly("EMR", "EMR", "COURSE_SITTING");
        assertThat(list.items().get(0).patientName()).isEqualTo("Unknown male");
        assertThat(list.items().get(0).emergencyCaseId()).isEqualTo(CASE);
        assertThat(list.items().get(0).patientId()).isNull();
        assertThat(list.items().get(2).admissionType()).isEqualTo("daycare");
        assertThat(list.items().get(2).courseNo()).isEqualTo("TC-0412");
        assertThat(list.items().get(1).patientName()).isEqualTo("Asha Rao");
        assertThat(list.items().get(1).doctorName()).isEqualTo("Dr Rao");
        assertThat(list.items().get(1).toString()).doesNotContain("Asha").doesNotContain("Appendicitis");
    }

    @Test
    void diagnosesAndTheNoteGoToTheOwnerAndTheNurseOnlyAndTheFrontDeskGetsNullForBoth() {
        when(requests.listOpen(TENANT)).thenReturn(List.of(request(PATIENT, null, NOW)));
        when(courses.openAdmissionSittings(TENANT)).thenReturn(List.of());

        for (String role : List.of("OWNER", "NURSE")) {
            Row row = service.list(TENANT, role).items().get(0);
            assertThat(row.diagnoses()).isEqualTo("Appendicitis");
            assertThat(row.note()).isEqualTo("Near nurses");
        }
        Row desk = service.list(TENANT, "FRONT_DESK").items().get(0);
        assertThat(desk.diagnoses()).isNull();
        assertThat(desk.note()).isNull();
        assertThat(desk.patientName()).isEqualTo("Asha Rao");
        assertThat(desk.admissionType()).isEqualTo("planned");
        assertThat(desk.plannedStayDays()).isEqualTo(3);
        assertThat(desk.doctorName()).isEqualTo("Dr Rao");
    }

    @Test
    void aPatientAlreadyAdmittedCarriesTheIpNumber() {
        UUID admission = UUID.randomUUID();
        when(requests.listOpen(TENANT)).thenReturn(List.of(request(PATIENT, null, NOW)));
        when(courses.openAdmissionSittings(TENANT)).thenReturn(List.of());
        when(admissions.findAdmittedOf(TENANT, PATIENT)).thenReturn(Optional.of(new AdmittedRef(admission, "IP/26-27/0007")));

        Row row = service.list(TENANT, "NURSE").items().get(0);

        assertThat(row.openAdmission().ipNumber()).isEqualTo("IP/26-27/0007");
        assertThat(row.openAdmission().admissionId()).isEqualTo(admission);
    }

    @Test
    void readOnlyHidesAdmitAndOtherRolesAndAnOffModuleAreRefused() {
        when(requests.listOpen(TENANT)).thenReturn(List.of());
        when(courses.openAdmissionSittings(TENANT)).thenReturn(List.of());
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("readonly", false, 10));
        assertThat(service.list(TENANT, "OWNER").canAdmit()).isFalse();

        for (String role : java.util.Arrays.asList("DOCTOR", "BILLING", "PHARMACIST", null)) {
            assertThatThrownBy(() -> service.list(TENANT, role)).isInstanceOf(AccessDeniedException.class);
        }
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 0));
        assertThatThrownBy(() -> service.list(TENANT, "OWNER")).isInstanceOf(ModuleOffException.class);
        verify(requests, never()).lockOpen(any(), any());
    }
}
