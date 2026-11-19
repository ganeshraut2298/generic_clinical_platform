package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.application.PharmacyViews.SetupSummary;
import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-78-1: the live subtitles of the setup tabs ("7 stores · 24 staff mapped", "12 active suppliers"), counted by the
 * server for the caller's unit. Writes nothing.
 */
@Service
public class SetupSummaryService {

    private final PharmacyGate gate;
    private final SetupCountRepository counts;
    private final StoreStaffRepository staff;
    private final TeamMemberPort team;

    public SetupSummaryService(PharmacyGate gate, SetupCountRepository counts, StoreStaffRepository staff,
                               TeamMemberPort team) {
        this.gate = gate;
        this.counts = counts;
        this.staff = staff;
        this.team = team;
    }

    @Transactional(readOnly = true)
    public SetupSummary get(UUID tenantId, UUID userId) {
        gate.require(tenantId, userId, AccessLevel.SETUP_VIEW);
        List<UUID> mapped = staff.mappedMembersOfActiveStores(tenantId);
        int signIn = 0;
        if (!mapped.isEmpty()) {
            Map<UUID, TeamMember> members = team.findAll(tenantId, new HashSet<>(mapped));
            signIn = (int) mapped.stream().filter(id -> members.containsKey(id) && members.get(id).active()).count();
        }
        return new SetupSummary(counts.activeStores(tenantId), signIn, counts.activeVendors(tenantId));
    }
}
