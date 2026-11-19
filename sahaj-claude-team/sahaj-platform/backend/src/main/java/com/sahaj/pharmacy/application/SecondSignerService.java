package com.sahaj.pharmacy.application;

import com.sahaj.pharmacy.domain.AccessLevel;
import com.sahaj.pharmacy.domain.StoreCapability;
import com.sahaj.pharmacy.domain.TeamRoles;
import com.sahaj.platform.api.RoleLabels;
import com.sahaj.platform.api.TeamMemberPort;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-79-5 (docs/api/pharmacy.yaml 0.3.2, pharmacyListSecondSigners): the people who can be the second signer of a Schedule X
 * handling (rule 19): every ACTIVE member whose team role is PHARMACIST or OWNER, except the caller, the same set
 * {@link ScheduleXSignature} accepts. The store guard asks for the Schedule X capability (a PHARMACIST mapped to the store; an
 * owner or store staff is refused 403). Writes nothing; logs the count only.
 */
@Service
public class SecondSignerService {

    private static final Logger log = LoggerFactory.getLogger(SecondSignerService.class);
    private static final int MAX_ITEMS = 500;

    private final PharmacyGate gate;
    private final StoreAccessGuard guard;
    private final TeamMemberPort team;

    public SecondSignerService(PharmacyGate gate, StoreAccessGuard guard, TeamMemberPort team) {
        this.gate = gate;
        this.guard = guard;
        this.team = team;
    }

    @Transactional(readOnly = true)
    public StockViews.SecondSignerCandidates list(UUID tenantId, UUID userId, String rawStoreId) {
        PharmacyCaller caller = gate.require(tenantId, userId, AccessLevel.PHARMACY_ACCESS);
        UUID storeId = PharmacyParams.pathId(rawStoreId);
        guard.require(caller, storeId, StoreCapability.RECEIVE_SCHEDULE_X);
        List<StockViews.SecondSignerCandidate> items = team.findActiveMembers(tenantId).stream()
                .filter(m -> m.active() && !m.membershipId().equals(caller.actorId())
                        && (TeamRoles.PHARMACIST.equals(m.role()) || TeamRoles.OWNER.equals(m.role())))
                .sorted(Comparator.comparing((TeamMemberPort.TeamMember m) -> m.name().toLowerCase(Locale.ROOT))
                        .thenComparing(TeamMemberPort.TeamMember::membershipId))
                .limit(MAX_ITEMS)
                .map(m -> new StockViews.SecondSignerCandidate(m.membershipId(), m.name(), RoleLabels.of(m.role()))).toList();
        log.info("Second signers listed storeId={} count={}", storeId, items.size());
        return new StockViews.SecondSignerCandidates(items);
    }
}
