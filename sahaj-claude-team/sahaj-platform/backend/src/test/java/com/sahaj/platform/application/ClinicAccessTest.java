package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.platform.application.TeamRepository.TeamRow;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC4: only an active owner passes; the membership is read again, not taken from the token. */
class ClinicAccessTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final TeamRepository teamRepository = mock(TeamRepository.class);
    private final ClinicAccess access = new ClinicAccess(teamRepository);

    private void member(MembershipRole role, MembershipStatus status) {
        when(teamRepository.findByUser(TENANT_ID, USER_ID)).thenReturn(Optional.of(
                new TeamRow(UUID.randomUUID(), USER_ID, "Asha", "9876543210", role, status, null, false, null, null,
                        null, 0L)));
    }

    @Test
    void anActiveOwnerPasses() {
        member(MembershipRole.OWNER, MembershipStatus.ACTIVE);

        assertThatCode(() -> access.requireOwner(TENANT_ID, USER_ID)).doesNotThrowAnyException();
    }

    @Test
    void aDemotedSwitchedOffOrUnknownPersonIsRefused() {
        member(MembershipRole.FRONT_DESK, MembershipStatus.ACTIVE);
        assertThatThrownBy(() -> access.requireOwner(TENANT_ID, USER_ID)).isInstanceOf(AccessDeniedException.class);

        member(MembershipRole.OWNER, MembershipStatus.INACTIVE);
        assertThatThrownBy(() -> access.requireOwner(TENANT_ID, USER_ID)).isInstanceOf(AccessDeniedException.class);

        when(teamRepository.findByUser(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.requireOwner(TENANT_ID, USER_ID)).isInstanceOf(AccessDeniedException.class);
    }
}
