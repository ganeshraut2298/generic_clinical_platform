package com.sahaj.opd.application.emr.specialty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@code opd.chemo_order} that refuses what the database refuses (docs/database/SAH-49-71.md): a checker who
 * is the prescriber or the last editor, a confirmation that is not by the named checker or not equal to the fingerprint,
 * SENT without a current confirmation, a hold without its fields, and any change of a SENT or HELD row. A test that passes
 * against this fake cannot rely on a rule only the application knows. Compare-and-set on the revision like the real one.
 */
class FakeChemoOrders implements ChemoOrderRepository {

    final Map<UUID, ChemoOrderRow> rows = new LinkedHashMap<>();
    int updates;

    @Override
    public Optional<ChemoOrderRow> findByRecord(UUID tenantId, UUID recordId) {
        return rows.values().stream().filter(r -> r.recordId.equals(recordId)).findFirst().map(FakeChemoOrders::copy);
    }

    @Override
    public Optional<ChemoOrderRow> findByRecordForUpdate(UUID tenantId, UUID recordId) {
        return findByRecord(tenantId, recordId);
    }

    @Override
    public Optional<ChemoOrderRow> findById(UUID tenantId, UUID orderId) {
        return Optional.ofNullable(rows.get(orderId)).map(FakeChemoOrders::copy);
    }

    @Override
    public Optional<ChemoOrderRow> findByIdForUpdate(UUID tenantId, UUID orderId) {
        return findById(tenantId, orderId);
    }

    @Override
    public void insert(UUID tenantId, ChemoOrderRow row, UUID actorUserId) {
        ChemoOrderRow stored = copy(row);
        stored.status = "DRAFT";
        stored.revision = 0;
        stored.createdAt = java.time.Instant.parse("2026-11-12T06:30:00Z");
        check(stored);
        rows.put(stored.id, stored);
    }

    @Override
    public boolean update(UUID tenantId, ChemoOrderRow row, long expectedRevision, UUID actorUserId) {
        ChemoOrderRow old = rows.get(row.id);
        if (old == null || old.revision != expectedRevision) {
            return false;
        }
        if (!old.draft()) {
            throw new IllegalStateException("opd.chemo_order is immutable once sent or held (UPDATE refused)");
        }
        ChemoOrderRow stored = copy(row);
        stored.revision = expectedRevision + 1;
        check(stored);
        rows.put(stored.id, stored);
        updates++;
        return true;
    }

    @Override
    public List<ChemoOrderRow> sentForPatient(UUID tenantId, UUID patientId) {
        List<ChemoOrderRow> sent = new ArrayList<>();
        for (ChemoOrderRow row : rows.values()) {
            if (patientId.equals(row.patientId) && "SENT".equals(row.status)) {
                sent.add(copy(row));
            }
        }
        sent.sort(Comparator.comparing(r -> r.sentAt));
        return sent;
    }

    @Override
    public List<ChemoOrderRow> waitingFor(UUID tenantId, UUID checkerMembershipId, int offset, int limit) {
        List<ChemoOrderRow> waiting = new ArrayList<>();
        for (ChemoOrderRow row : rows.values()) {
            if (checkerMembershipId.equals(row.checkerMembershipId) && row.draft() && row.confirmedAt == null) {
                waiting.add(copy(row));
            }
        }
        waiting.sort(Comparator.comparing((ChemoOrderRow r) -> r.checkerNamedAt).thenComparing(r -> r.id));
        return waiting.subList(Math.min(offset, waiting.size()), Math.min(offset + limit, waiting.size()));
    }

    @Override
    public long countWaitingFor(UUID tenantId, UUID checkerMembershipId) {
        return rows.values().stream().filter(r -> checkerMembershipId.equals(r.checkerMembershipId) && r.draft()
                && r.confirmedAt == null).count();
    }

    /** The table's check constraints. */
    private static void check(ChemoOrderRow r) {
        if (r.checkerMembershipId != null && (r.checkerMembershipId.equals(r.prescriberMembershipId)
                || r.checkerMembershipId.equals(r.lastEditedByMembershipId))) {
            throw new IllegalStateException("ck_chemo_order_checker_independent");
        }
        boolean allSet = r.confirmedByMembershipId != null && r.confirmedByUserId != null && r.confirmedVia != null
                && r.confirmedAt != null && r.confirmedFingerprint != null;
        boolean noneSet = r.confirmedByMembershipId == null && r.confirmedByUserId == null && r.confirmedVia == null
                && r.confirmedAt == null && r.confirmedFingerprint == null;
        if (!allSet && !noneSet) {
            throw new IllegalStateException("ck_chemo_order_confirmation_pair");
        }
        if (allSet && !r.confirmedByMembershipId.equals(r.checkerMembershipId)) {
            throw new IllegalStateException("ck_chemo_order_confirmer_is_checker");
        }
        if (allSet && !r.confirmedFingerprint.equals(r.fingerprint)) {
            throw new IllegalStateException("ck_chemo_order_confirmation_current");
        }
        if (r.confirmationClearedAt != null && r.confirmedAt != null) {
            throw new IllegalStateException("ck_chemo_order_cleared_has_no_confirmation");
        }
        if ("SENT".equals(r.status) && !(r.confirmedAt != null && r.confirmedFingerprint.equals(r.fingerprint)
                && r.sentAt != null && r.sentBy != null)) {
            throw new IllegalStateException("ck_chemo_order_sent");
        }
        if ("SENT".equals(r.status) && r.levelPct != 100 && r.reductionReasonKey == null
                && r.reductionReasonText == null) {
            throw new IllegalStateException("ck_chemo_order_sent_reason");
        }
        if ("HELD".equals(r.status) && (r.holdKind == null || r.heldAt == null)) {
            throw new IllegalStateException("ck_chemo_order_held");
        }
        if (!"HELD".equals(r.status) && (r.holdKind != null || r.holdReason != null || r.heldAt != null)) {
            throw new IllegalStateException("ck_chemo_order_held");
        }
    }

    static ChemoOrderRow copy(ChemoOrderRow r) {
        ChemoOrderRow c = new ChemoOrderRow();
        c.id = r.id;
        c.recordId = r.recordId;
        c.recordCreatedAt = r.recordCreatedAt;
        c.patientId = r.patientId;
        c.courseId = r.courseId;
        c.cycleNo = r.cycleNo;
        c.status = r.status;
        c.protocolKey = r.protocolKey;
        c.levelPct = r.levelPct;
        c.reductionReasonKey = r.reductionReasonKey;
        c.reductionReasonText = r.reductionReasonText;
        c.inputs = r.inputs;
        c.snapshot = r.snapshot;
        c.fingerprint = r.fingerprint;
        c.doxorubicinMgM2 = r.doxorubicinMgM2;
        c.priorOutsideMgM2 = r.priorOutsideMgM2;
        c.prescriberMembershipId = r.prescriberMembershipId;
        c.lastEditedByMembershipId = r.lastEditedByMembershipId;
        c.checkerMembershipId = r.checkerMembershipId;
        c.checkerRole = r.checkerRole;
        c.checkerNamedAt = r.checkerNamedAt;
        c.checkerNamedBy = r.checkerNamedBy;
        c.confirmedByMembershipId = r.confirmedByMembershipId;
        c.confirmedByUserId = r.confirmedByUserId;
        c.confirmedVia = r.confirmedVia;
        c.confirmedAt = r.confirmedAt;
        c.confirmedFingerprint = r.confirmedFingerprint;
        c.confirmationClearedAt = r.confirmationClearedAt;
        c.sentAt = r.sentAt;
        c.sentBy = r.sentBy;
        c.holdKind = r.holdKind;
        c.holdReason = r.holdReason;
        c.heldAt = r.heldAt;
        c.revision = r.revision;
        c.createdAt = r.createdAt;
        return c;
    }
}
