package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-27 {@code TeamMemberPort.doctorSpecialty}: empty when not set, not a doctor, or another clinic's id. */
class TeamMemberLookupTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();

    private TeamRepository teamRepository;
    private TeamMemberLookup lookup;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        lookup = new TeamMemberLookup(teamRepository);
    }

    private static TeamRow row(MembershipRole role, boolean hasProfile, String specialty) {
        return new TeamRow(MEMBER, UUID.randomUUID(), "Dr Asha Rao", "9000000001", role, MembershipStatus.ACTIVE, null,
                hasProfile, specialty, null, null, 50_000L);
    }

    @Test
    void aDoctorWithASpecialtyReturnsIt() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(row(MembershipRole.DOCTOR, true, "Cardiology")));
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).contains("Cardiology");

        when(teamRepository.findById(TENANT, MEMBER))
                .thenReturn(Optional.of(row(MembershipRole.VISITING_DOCTOR, true, "  Surgery ")));
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).contains("Surgery");
    }

    @Test
    void aMissingOrBlankSpecialtyIsEmpty() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(row(MembershipRole.DOCTOR, true, null)));
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).isEmpty();

        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(row(MembershipRole.DOCTOR, true, "  ")));
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).isEmpty();

        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(row(MembershipRole.DOCTOR, false, null)));
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).isEmpty();
    }

    // ---- SAH-33 R8: findActiveByUserId is one single-row query, never the whole team ----

    @Test
    void findActiveByUserIdAsksTheSingleRowQueryAndNeverLoadsTheTeam() {
        UUID user = UUID.randomUUID();
        when(teamRepository.findActiveByUser(TENANT, user)).thenReturn(Optional.of(row(MembershipRole.NURSE, false, null)));

        var found = lookup.findActiveByUserId(TENANT, user);

        assertThat(found).isPresent();
        assertThat(found.get().role()).isEqualTo("NURSE");
        assertThat(found.get().active()).isTrue();
        verify(teamRepository).findActiveByUser(TENANT, user);
        verify(teamRepository, never()).findAll(any());
        verify(teamRepository, never()).findByUser(any(), any());
    }

    @Test
    void findActiveByUserIdIsEmptyWhenNoActiveRowExists() {
        UUID user = UUID.randomUUID();
        when(teamRepository.findActiveByUser(TENANT, user)).thenReturn(Optional.empty());

        assertThat(lookup.findActiveByUserId(TENANT, user)).isEmpty();
        verify(teamRepository, never()).findAll(any());
    }

    @Test
    void aMemberWhoIsNotADoctorOrAnUnknownIdIsEmpty() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(row(MembershipRole.NURSE, true, "Cardiology")));
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).isEmpty();

        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.empty());
        assertThat(lookup.doctorSpecialty(TENANT, MEMBER)).isEmpty();
    }

    // ---- SAH-43 (ADR-019 D7): doctorDetails ----

    private static TeamRow detailRow(MembershipRole role, MembershipStatus status, boolean hasProfile,
                                     String specialty, String degree, String registrationNo) {
        return new TeamRow(MEMBER, UUID.randomUUID(), "Dr Test Person", "9000000001", role, status, null, hasProfile,
                specialty, degree, registrationNo, 0L);
    }

    @Test
    void doctorDetailsReturnsTheSavedValuesAsTheyAre() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(
                detailRow(MembershipRole.DOCTOR, MembershipStatus.ACTIVE, true, "General Physician", "MBBS, MD", "REG-1")));

        var details = lookup.doctorDetails(TENANT, MEMBER).orElseThrow();

        assertThat(details.membershipId()).isEqualTo(MEMBER);
        assertThat(details.name()).isEqualTo("Dr Test Person");
        assertThat(details.role()).isEqualTo("DOCTOR");
        assertThat(details.membershipStatus()).isEqualTo("ACTIVE");
        assertThat(details.specialty()).isEqualTo("General Physician");
        assertThat(details.degree()).isEqualTo("MBBS, MD");
        assertThat(details.registrationNo()).isEqualTo("REG-1");
    }

    @Test
    void doctorDetailsReturnsBlankFieldsAsNull() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(
                detailRow(MembershipRole.VISITING_DOCTOR, MembershipStatus.ACTIVE, true, "  ", "", null)));

        var details = lookup.doctorDetails(TENANT, MEMBER).orElseThrow();

        assertThat(details.specialty()).isNull();
        assertThat(details.degree()).isNull();
        assertThat(details.registrationNo()).isNull();
    }

    @Test
    void doctorDetailsIsEmptyForAnOwnerWithNoDoctorRowAndForAnotherClinicsOrUnknownId() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(
                detailRow(MembershipRole.OWNER, MembershipStatus.ACTIVE, false, null, null, null)));
        assertThat(lookup.doctorDetails(TENANT, MEMBER)).isEmpty();

        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.empty());
        assertThat(lookup.doctorDetails(TENANT, MEMBER)).isEmpty();
    }

    @Test
    void doctorDetailsOfAnOwnerWhoIsADoctorAndOfARemovedMemberAreReturned() {
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(
                detailRow(MembershipRole.OWNER, MembershipStatus.ACTIVE, true, "Dental", null, null)));
        assertThat(lookup.doctorDetails(TENANT, MEMBER)).isPresent();

        MembershipStatus other = java.util.Arrays.stream(MembershipStatus.values())
                .filter(status -> status != MembershipStatus.ACTIVE).findFirst().orElseThrow();
        when(teamRepository.findById(TENANT, MEMBER)).thenReturn(Optional.of(
                detailRow(MembershipRole.DOCTOR, other, true, "Dental", "BDS", "R-2")));
        var removed = lookup.doctorDetails(TENANT, MEMBER).orElseThrow();
        assertThat(removed.membershipStatus()).isEqualTo(other.name());
        assertThat(removed.registrationNo()).isEqualTo("R-2");
    }
}
