package com.sahaj.pharmacy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-79-5, 0.3.2: the second signers are the other ACTIVE pharmacists and owners, sorted by name, never the caller. */
class SecondSignerServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID STORE = UUID.randomUUID();

    private final PharmacyGate gate = mock(PharmacyGate.class);
    private final StoreAccessGuard guard = mock(StoreAccessGuard.class);
    private final TeamMemberPort team = mock(TeamMemberPort.class);
    private final SecondSignerService service = new SecondSignerService(gate, guard, team);

    private static TeamMember member(UUID id, String name, String role, boolean active) {
        return new TeamMember(id, name, role, active, 0L);
    }

    @Test
    void listsOtherActivePharmacistsAndOwnersSortedByNameIgnoringCaseAndAsksTheScheduleXCapability() {
        PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist One", "PHARMACIST", List.of());
        when(gate.require(TENANT, USER, AccessLevel.PHARMACY_ACCESS)).thenReturn(caller);
        UUID b = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        when(team.findActiveMembers(TENANT)).thenReturn(List.of(member(ME, "Test Pharmacist One", "PHARMACIST", true),
                member(b, "test owner two", "OWNER", true), member(a, "Test Pharmacist Alpha", "PHARMACIST", true),
                member(UUID.randomUUID(), "Test Nurse", "NURSE", true),
                member(UUID.randomUUID(), "Test Pharmacist Off", "PHARMACIST", false)));

        StockViews.SecondSignerCandidates result = service.list(TENANT, USER, STORE.toString());

        org.mockito.Mockito.verify(guard).require(eq(caller), eq(STORE), eq(StoreCapability.RECEIVE_SCHEDULE_X));
        assertThat(result.items()).extracting(StockViews.SecondSignerCandidate::membershipId).containsExactly(b, a);
        assertThat(result.items()).extracting(StockViews.SecondSignerCandidate::roleLabel).doesNotContainNull();
    }

    @Test
    void noOtherEligiblePersonIsAnEmptyList() {
        PharmacyCaller caller = new PharmacyCaller(TENANT, USER, ME, "Test Pharmacist One", "PHARMACIST", List.of());
        when(gate.require(any(), any(), any())).thenReturn(caller);
        when(team.findActiveMembers(TENANT)).thenReturn(List.of(member(ME, "Test Pharmacist One", "PHARMACIST", true)));

        assertThat(service.list(TENANT, USER, STORE.toString()).items()).isEmpty();
    }
}
