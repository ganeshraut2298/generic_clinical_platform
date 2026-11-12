package com.sahaj.opd.application.emr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.DoctorDetails;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** ADR-019 D8: the two fixed capabilities, decided from a fresh membership read and not from the token's role. */
class EmrAccessTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private TeamMemberPort team;
    private OwnerGuard ownerGuard;
    private EmrAccess access;

    @BeforeEach
    void setUp() {
        team = mock(TeamMemberPort.class);
        ownerGuard = mock(OwnerGuard.class);
        access = new EmrAccess(team, ownerGuard);
    }

    private static ClinicUser user(String tokenRole) {
        return new ClinicUser(USER, TENANT, tokenRole);
    }

    private void membership(String role) {
        when(team.findActiveByUserId(TENANT, USER))
                .thenReturn(Optional.of(new TeamMember(MEMBERSHIP, "Test Person", role, true, 0L)));
    }

    @Test
    void aDoctorAndAVisitingDoctorMayWriteTheEmr() {
        membership("DOCTOR");
        assertThat(access.requireConsultationWrite(user("DOCTOR")).membershipId()).isEqualTo(MEMBERSHIP);

        membership("VISITING_DOCTOR");
        assertThat(access.requireConsultationWrite(user("VISITING_DOCTOR")).membershipId()).isEqualTo(MEMBERSHIP);
    }

    @Test
    void noOtherRoleMayWriteTheEmr() {
        for (String role : new String[] {"NURSE", "FRONT_DESK", "BILLING", "PHARMACIST", "COUNSELLOR"}) {
            membership(role);
            assertThatThrownBy(() -> access.requireConsultationWrite(user(role)))
                    .as(role).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void anOwnerWithDoctorDetailsMayWriteTheEmr() {
        membership("OWNER");
        when(team.doctorDetails(TENANT, MEMBERSHIP)).thenReturn(Optional.of(
                new DoctorDetails(MEMBERSHIP, "Test Person", "OWNER", "ACTIVE", null, null, null)));

        assertThat(access.requireConsultationWrite(user("OWNER")).membershipId()).isEqualTo(MEMBERSHIP);
    }

    @Test
    void anOwnerWithoutDoctorDetailsIsToldWhy() {
        membership("OWNER");
        when(team.doctorDetails(TENANT, MEMBERSHIP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireConsultationWrite(user("OWNER")))
                .isInstanceOfSatisfying(ForbiddenException.class, ex -> {
                    assertThat(ex.problemType()).isEqualTo("doctor-details-missing");
                    assertThat(ex.getMessage()).isEqualTo(
                            "Add your doctor details in Team and access to write consultations.");
                });
    }

    @Test
    void theTokenRoleIsNotTrustedSoADemotedDoctorIsRefused() {
        membership("NURSE");
        assertThatThrownBy(() -> access.requireConsultationWrite(user("DOCTOR")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aPersonWhoIsNoLongerActiveIsRefused() {
        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireConsultationWrite(user("DOCTOR")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void setupEditGoesThroughTheOwnerGuard() {
        access.requireSetupEdit(user("OWNER"));
        verify(ownerGuard).requireOwner(TENANT, USER);

        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(any(), any());
        assertThatThrownBy(() -> access.requireSetupEdit(user("OWNER"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void canEditSetupIsTrueOnlyForAFreshlyReadOwner() {
        membership("OWNER");
        assertThat(access.canEditSetup(user("DOCTOR"))).isTrue();

        membership("DOCTOR");
        assertThat(access.canEditSetup(user("OWNER"))).isFalse();

        when(team.findActiveByUserId(TENANT, USER)).thenReturn(Optional.empty());
        assertThat(access.canEditSetup(user("OWNER"))).isFalse();
    }
}
