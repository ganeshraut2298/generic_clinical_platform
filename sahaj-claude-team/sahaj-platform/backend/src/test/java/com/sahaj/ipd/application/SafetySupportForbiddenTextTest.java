package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * SAH-37 (code review finding 2): the web layer answers every refusal with the one shared forbidden problem, so the
 * checks of the card setup and of {@code cardFacts=all} carry the shared text and no text of their own.
 */
class SafetySupportForbiddenTextTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void theSharedTextIsTheOneTheForbiddenProblemSays() {
        assertThat(SafetySupport.FORBIDDEN).isEqualTo("You do not have access to this.");
    }

    @Test
    void aRoleOutsideTheSetIsRefusedWithTheSharedText() {
        assertThatThrownBy(() -> SafetySupport.requireRole("NURSE", Set.of("OWNER")))
                .isInstanceOf(AccessDeniedException.class).hasMessage(SafetySupport.FORBIDDEN);
        assertThatThrownBy(() -> SafetySupport.requireRole(null, Set.of("OWNER")))
                .isInstanceOf(AccessDeniedException.class).hasMessage(SafetySupport.FORBIDDEN);
        SafetySupport.requireRole("OWNER", Set.of("OWNER"));
    }

    @Test
    void aMembershipOutsideTheSetOrWithoutOneIsRefusedWithTheSharedText() {
        TeamMemberPort team = mock(TeamMemberPort.class);
        when(team.findActiveByUserId(any(), any())).thenReturn(
                Optional.of(new TeamMember(UUID.randomUUID(), "Member", "BILLING", true, 0L)));

        assertThatThrownBy(() -> SafetySupport.membershipRole(team, TENANT, ACTOR, Set.of("OWNER")))
                .isInstanceOf(AccessDeniedException.class).hasMessage(SafetySupport.FORBIDDEN);
        when(team.findActiveByUserId(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> SafetySupport.membershipRole(team, TENANT, ACTOR, Set.of("OWNER")))
                .isInstanceOf(AccessDeniedException.class).hasMessage(SafetySupport.FORBIDDEN);
        when(team.findActiveByUserId(any(), any())).thenReturn(
                Optional.of(new TeamMember(UUID.randomUUID(), "Member", "OWNER", true, 0L)));
        assertThat(SafetySupport.membershipRole(team, TENANT, ACTOR, Set.of("OWNER"))).isEqualTo("OWNER");
    }
}
