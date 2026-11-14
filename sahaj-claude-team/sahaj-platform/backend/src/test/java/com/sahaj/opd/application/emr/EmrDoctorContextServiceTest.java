package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.opd.application.emr.EmrDepartmentResolver.Resolution;
import com.sahaj.opd.application.emr.EmrLayouts.Department;
import com.sahaj.opd.application.emr.EmrLayouts.Published;
import com.sahaj.opd.application.emr.EmrViews.DepartmentRef;
import com.sahaj.opd.application.emr.EmrViews.DoctorContext;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmrDoctorContextServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final ClinicUser DOCTOR = new ClinicUser(USER, TENANT, "DOCTOR");

    private final EmrAccess access = mock(EmrAccess.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final EmrDepartmentResolver resolver = mock(EmrDepartmentResolver.class);
    private EmrDoctorContextService service;
    private Resolution resolution;

    @BeforeEach
    void setUp() {
        when(access.requireConsultationWrite(DOCTOR)).thenReturn(new TeamMember(MEMBER, "Dr Asha", "DOCTOR", true, 0));
        Department gm = new Department(null, "gm", "General Medicine", "GM", "#1D5FA8", "gm", "STARTER", null,
                List.of(), 0, 0, null);
        resolution = new Resolution(gm, new Published("PUBLISHED", 6, UUID.randomUUID(), null, List.of()),
                "DOCTOR_SPECIALTY");
        when(resolver.chooseForSpecialty(TENANT, MEMBER, "General Physician")).thenReturn(resolution);
        when(resolver.assignedRefs(TENANT, MEMBER)).thenReturn(List.of(new DepartmentRef("gm", "General Medicine")));
        service = new EmrDoctorContextService(access, team, resolver);
    }

    @Test
    void theDoctorGetsTheirOwnDetailsTheMissingFieldsAndTheDepartmentChoice() {
        when(team.doctorDetails(TENANT, MEMBER)).thenReturn(Optional.of(new DoctorDetails(MEMBER, "Dr Asha", "DOCTOR",
                "ACTIVE", "General Physician", "MBBS, MD", null)));

        DoctorContext context = service.me(DOCTOR);

        assertThat(context.membershipId()).isEqualTo(MEMBER);
        assertThat(context.specialty()).isEqualTo("General Physician");
        assertThat(context.registrationNo()).isNull();
        assertThat(context.missing()).containsExactly("REGISTRATION_NO");
        assertThat(context.department().departmentKey()).isEqualTo("gm");
        assertThat(context.department().layoutSource()).isEqualTo("PUBLISHED");
        assertThat(context.department().versionNo()).isEqualTo(6);
        assertThat(context.department().choiceSource()).isEqualTo("DOCTOR_SPECIALTY");
        assertThat(context.departments()).extracting(DepartmentRef::departmentKey).containsExactly("gm");
    }

    @Test
    void aDoctorWithNoDoctorProfileRowIsStillServedWithEveryDetailMissing() {
        when(team.doctorDetails(TENANT, MEMBER)).thenReturn(Optional.empty());
        when(resolver.chooseForSpecialty(TENANT, MEMBER, null)).thenReturn(resolution);

        DoctorContext context = service.me(DOCTOR);

        assertThat(context.specialty()).isNull();
        assertThat(context.missing()).containsExactly("DEGREE", "REGISTRATION_NO");
    }

    @Test
    void anOwnerWithoutDoctorDetailsAndOtherRolesAreRefusedBeforeAnythingIsRead() {
        ClinicUser owner = new ClinicUser(USER, TENANT, "OWNER");
        when(access.requireConsultationWrite(owner)).thenThrow(new ForbiddenException("doctor-details-missing",
                "Doctor details are missing", "Add your doctor details in Team and access to write consultations."));

        assertThatThrownBy(() -> service.me(owner)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(resolver);
    }
}
