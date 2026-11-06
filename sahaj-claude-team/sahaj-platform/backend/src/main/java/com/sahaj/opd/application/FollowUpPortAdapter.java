package com.sahaj.opd.application;

import com.sahaj.opd.api.FollowUpPort;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-27 (ADR-004 D1): implements {@link FollowUpPort} for {@code ipd}. It joins the caller's transaction and never
 * starts its own, so the follow-up and the discharge commit or roll back together.
 */
@Component
class FollowUpPortAdapter implements FollowUpPort {

    private final FollowUpService followUps;

    FollowUpPortAdapter(FollowUpService followUps) {
        this.followUps = followUps;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public FeeRule feeRule(UUID tenantId) {
        return followUps.feeRule(tenantId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createForDischarge(UUID tenantId, UUID actorUserId, DischargeFollowUp followUp) {
        return followUps.createForDischarge(tenantId, actorUserId, followUp);
    }
}
