package com.sahaj.opd.application.emr;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of the usage ranking (SAH-43-2, ADR-019 D10). There is no REST operation for it. The sign step of
 * SAH-51 calls it inside its own transaction, so the counts commit or roll back with the signature
 * ({@link Propagation#MANDATORY}: without a transaction it fails). Each distinct item counts once per visit (the
 * argument is a {@link Set}), items that are not in their master are ignored, drafts and addenda never call it, and
 * it holds no patient id, visit id or text. A convention test (EmrUsageRecorderConventionTest) fails the build when
 * any class other than the sign service depends on this one.
 */
@Service
public class EmrUsageRecorder {

    private static final Comparator<MasterItemRef> LOCK_ORDER = Comparator
            .comparing((MasterItemRef ref) -> ref.master().key())
            .thenComparing(MasterItemRef::itemKey);

    private final EmrUsageRepository repository;

    public EmrUsageRecorder(EmrUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * Counts one use of each item for the signing doctor, with the signature time as last used. Returns how many
     * items were counted. The items are taken in a fixed order, so two visits signed at the same moment that share
     * items lock the same rows in the same order and cannot deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recordSignedVisit(UUID tenantId, UUID doctorMembershipId, Instant signedAt, Set<MasterItemRef> items) {
        List<MasterItemRef> ordered = items.stream().sorted(LOCK_ORDER).toList();
        int counted = 0;
        for (MasterItemRef item : ordered) {
            counted += repository.recordUse(tenantId, doctorMembershipId, item.master(), item.itemKey(), signedAt);
        }
        return counted;
    }
}
