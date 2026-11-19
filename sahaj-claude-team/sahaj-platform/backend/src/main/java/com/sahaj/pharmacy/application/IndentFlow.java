package com.sahaj.pharmacy.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What an indent's screen reads about its dispatches, returns and shortages (SAH-79-11 to 79-13): the dispatches ("IND-6012 / 1"),
 * the units still waiting for the Central Store to accept, the units short and not yet settled, and the Schedule X lines that wait
 * for a pharmacist. Reads only, in the caller's transaction.
 */
@Component
class IndentFlow {

    private final DispatchRepository dispatches;
    private final ReturnItemRepository returns;
    private final ShortageRepository shortages;

    IndentFlow(DispatchRepository dispatches, ReturnItemRepository returns, ShortageRepository shortages) {
        this.dispatches = dispatches;
        this.returns = returns;
        this.shortages = shortages;
    }

    List<IndentViews.DispatchRef> dispatchRefs(UUID tenantId, UUID indentId) {
        return dispatches.byIndent(tenantId, indentId).stream()
                .map(d -> new IndentViews.DispatchRef(d.id(), d.reference(), d.status(), d.dispatchedAt())).toList();
    }

    /** The quantity still waiting for the Central Store to accept (chips.rejectedWaitingCs, 0.3.5). */
    int rejectedWaitingCs(UUID tenantId, UUID indentId) {
        return returns.waitingQtyForIndent(tenantId, indentId);
    }

    /** The shortage quantity not yet settled (chips.shortInvestigating, 0.3.5). */
    int shortInvestigating(UUID tenantId, UUID indentId) {
        return shortages.unsettledQtyForIndent(tenantId, indentId);
    }

    /** The Schedule X dispatch lines not yet received (chips.scheduleXWaiting, 0.3.4). */
    int scheduleXWaiting(UUID tenantId, UUID indentId) {
        return dispatches.scheduleXWaiting(tenantId, indentId);
    }
}
