package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-row-at-a-time check behind "every protected request re-checks the membership is
 * ACTIVE" (SAH-3 technical design, SAH-6). Called with {@code app.tenant_id} already bound by
 * the request's own authentication, so it reads only the caller's own clinic.
 */
@Service
public class ActiveMembershipQueryService {

    private final MembershipRepository membershipRepository;

    public ActiveMembershipQueryService(MembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Transactional
    public boolean isActive(UUID tenantId, UUID userId) {
        return membershipRepository.findByTenantAndUser(tenantId, userId)
                .map(membership -> membership.status() == MembershipStatus.ACTIVE)
                .orElse(false);
    }
}
