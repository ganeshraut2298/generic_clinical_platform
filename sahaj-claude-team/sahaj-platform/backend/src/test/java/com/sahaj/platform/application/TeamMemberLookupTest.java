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
}
