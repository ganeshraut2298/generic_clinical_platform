package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@link MedicineOrderRepository}, {@link OrderVersionRepository}, {@link OrderTimelineRepository} and
 * {@link OrderQueryRepository} for unit tests (no database). It keeps the rules the database keeps that matter to the
 * services: transitions only from the allowed statuses, the row counter counted up, the times stamped by the "database",
 * the verifier never the orderer, and the queue limited to admissions registered as admitted. {@code writes} counts every
 * insert and every update so a test can prove that a refusal or a replay wrote nothing. A version row is made on demand
 * for an order that was seeded as a fixture (SCHEDULED, REQUIRED, verified as the head says).
 */
class FakeMedicineOrders implements MedicineOrderRepository, OrderVersionRepository, OrderTimelineRepository,
        OrderQueryRepository {

    static final Instant START = Instant.parse("2026-11-11T05:00:00Z");

    final Map<UUID, OrderRow> rows = new HashMap<>();
    final Map<UUID, List<MedicineOrderVersion>> versionRows = new HashMap<>();
    final Map<UUID, List<OrderHoldInterval>> holdRows = new HashMap<>();
    final Map<UUID, List<QueryRow>> queryRows = new LinkedHashMap<>();
    /** admission id to {patient id, bed code}; the queue shows PENDING orders of these admissions only. */
    final Map<UUID, UUID> patientOfAdmission = new HashMap<>();
    final Map<UUID, String> bedOfAdmission = new HashMap<>();
    final List<UUID> discharged = new ArrayList<>();
    int writes;
    /** When set, every clock read adds "clock" (a test proves the clock is read after the lock). */
    List<String> trace;
    Instant now = START.plus(Duration.ofHours(1));
    private Instant nextOrderTime = START;

    @Override
    public Instant databaseNow() {
        if (trace != null) {
            trace.add("clock");
        }
        return now;
    }

    @Override
    public Optional<OrderRow> findById(UUID tenantId, UUID orderId) {
        return Optional.ofNullable(rows.get(orderId));
    }

    @Override
    public Optional<OrderRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return rows.values().stream().filter(o -> o.clientKey().equals(clientKey)).findFirst();
    }

    @Override
    public List<OrderRow> findByAdmission(UUID tenantId, UUID admissionId) {
        List<OrderRow> found = new ArrayList<>();
        for (OrderRow o : rows.values()) {
            if (o.admissionId().equals(admissionId)) {
                found.add(o);
            }
        }
        found.sort(chart());
        return found;
    }

    @Override
    public List<OrderRow> findLiveByAdmissions(UUID tenantId, Collection<UUID> admissionIds) {
        List<OrderRow> found = new ArrayList<>();
        for (OrderRow o : rows.values()) {
            if (admissionIds.contains(o.admissionId()) && o.live()) {
                found.add(o);
            }
        }
        found.sort(Comparator.comparing(OrderRow::admissionId).thenComparing(chart()));
        return found;
    }

    @Override
    public OrderRow insert(UUID tenantId, NewOrder order) {
        writes++;
        nextOrderTime = nextOrderTime.plusSeconds(60);
        OrderRow row = new OrderRow(UUID.randomUUID(), order.admissionId(), order.clientKey(), order.drugKey(),
                order.frequencyKey(), order.status(), order.orderedBy(), nextOrderTime, order.overrideReasonKey(),
                order.warnings(), null, null, null, null, null, null, null, 0, 1, order.priority(), null);
        rows.put(row.id(), row);
        return row;
    }

    /** Adds an order of any frequency with the given verification time (a fixture), without counting a write. */
    OrderRow seedAt(UUID admissionId, String drugKey, String frequencyKey, String status, UUID orderedBy,
                    Instant verifiedAt) {
        OrderRow base = seed(admissionId, drugKey, status, orderedBy);
        OrderRow row = new OrderRow(base.id(), base.admissionId(), base.clientKey(), base.drugKey(), frequencyKey,
                base.status(), base.orderedBy(), base.orderedAt(), base.overrideReasonKey(), base.warnings(),
                base.verifiedBy(), verifiedAt, base.rejectedBy(), base.rejectedAt(), base.rejectReasonKey(),
                base.stoppedBy(), base.stoppedAt(), base.version());
        rows.put(row.id(), row);
        return row;
    }

    /** Adds an order directly (a fixture), without counting a write. */
    OrderRow seed(UUID admissionId, String drugKey, String status, UUID orderedBy) {
        nextOrderTime = nextOrderTime.plusSeconds(60);
        UUID by = UUID.randomUUID();
        boolean verified = MedicineOrderRepository.ACTIVE.equals(status)
                || MedicineOrderRepository.ON_HOLD.equals(status);
        OrderRow row = new OrderRow(UUID.randomUUID(), admissionId, UUID.randomUUID(), drugKey, "od", status,
                orderedBy, nextOrderTime, null, List.of(), verified ? by : null,
                verified ? nextOrderTime.plusSeconds(5) : null,
                MedicineOrderRepository.REJECTED.equals(status) ? by : null,
                MedicineOrderRepository.REJECTED.equals(status) ? nextOrderTime.plusSeconds(5) : null,
                MedicineOrderRepository.REJECTED.equals(status) ? "WRONG_DOSE" : null,
                MedicineOrderRepository.STOPPED.equals(status) ? by : null,
                MedicineOrderRepository.STOPPED.equals(status) ? nextOrderTime.plusSeconds(5) : null, 0);
        rows.put(row.id(), row);
        return row;
    }

    /** Replaces the head of an order with this one (a fixture), without counting a write. */
    void put(OrderRow row) {
        rows.put(row.id(), row);
    }

    // ---- head transitions ----

    private void update(OrderRow o, String status, UUID verifiedBy, Instant verifiedAt, UUID rejectedBy,
                        Instant rejectedAt, String rejectReason, UUID stoppedBy, Instant stoppedAt, int currentVersion,
                        String priority, String stopReason) {
        writes++;
        rows.put(o.id(), new OrderRow(o.id(), o.admissionId(), o.clientKey(), o.drugKey(), o.frequencyKey(), status,
                o.orderedBy(), o.orderedAt(), o.overrideReasonKey(), o.warnings(), verifiedBy, verifiedAt, rejectedBy,
                rejectedAt, rejectReason, stoppedBy, stoppedAt, o.version() + 1, currentVersion, priority, stopReason));
    }

    @Override
    public boolean markActive(UUID tenantId, UUID orderId, UUID verifiedBy) {
        OrderRow o = rows.get(orderId);
        if (o == null || !PENDING.equals(o.status())) {
            return false;
        }
        if (verifiedBy.equals(o.orderedBy())) {
            throw new IllegalStateException("database: ck_medicine_order_self_verify");
        }
        update(o, ACTIVE, verifiedBy, now, null, null, null, null, null, o.currentVersion(), o.priority(), null);
        return true;
    }

    @Override
    public boolean markRejected(UUID tenantId, UUID orderId, UUID rejectedBy, String reasonKey) {
        OrderRow o = rows.get(orderId);
        if (o == null || !(PENDING.equals(o.status()) || QUERIED.equals(o.status()))) {
            return false;
        }
        update(o, REJECTED, o.verifiedBy(), o.verifiedAt(), rejectedBy, now, reasonKey, null, null,
                o.currentVersion(), o.priority(), null);
        return true;
    }

    @Override
    public boolean markStopped(UUID tenantId, UUID orderId, UUID stoppedBy, String reason) {
        OrderRow o = rows.get(orderId);
        if (o == null || !o.live()) {
            return false;
        }
        update(o, STOPPED, o.verifiedBy(), o.verifiedAt(), null, null, null, stoppedBy, now, o.currentVersion(),
                o.priority(), reason);
        return true;
    }

    private boolean move(UUID orderId, String from, String to) {
        OrderRow o = rows.get(orderId);
        if (o == null || !from.equals(o.status())) {
            return false;
        }
        update(o, to, o.verifiedBy(), o.verifiedAt(), null, null, null, null, null, o.currentVersion(), o.priority(),
                null);
        return true;
    }

    @Override
    public boolean markQueried(UUID tenantId, UUID orderId) {
        return move(orderId, PENDING, QUERIED);
    }

    @Override
    public boolean markReplied(UUID tenantId, UUID orderId) {
        return move(orderId, QUERIED, PENDING);
    }

    @Override
    public boolean markHeld(UUID tenantId, UUID orderId) {
        return move(orderId, ACTIVE, ON_HOLD);
    }

    @Override
    public boolean markResumed(UUID tenantId, UUID orderId) {
        return move(orderId, ON_HOLD, ACTIVE);
    }

    @Override
    public boolean markChanged(UUID tenantId, UUID orderId, int newVersion, String newStatus, String priority) {
        OrderRow o = rows.get(orderId);
        if (o == null || !(PENDING.equals(o.status()) || QUERIED.equals(o.status()) || ACTIVE.equals(o.status()))
                || o.currentVersion() != newVersion - 1) {
            return false;
        }
        update(o, newStatus, o.verifiedBy(), o.verifiedAt(), null, null, null, null, null, newVersion, priority,
                null);
        return true;
    }

    // ---- the queue ----

    @Override
    public List<PendingRow> findPending(UUID tenantId, Position after, int limit) {
        List<OrderRow> pending = pendingOrders(true);
        List<PendingRow> found = new ArrayList<>();
        for (OrderRow o : pending) {
            boolean past = after == null || o.orderedAt().isAfter(after.orderedAt())
                    || (o.orderedAt().equals(after.orderedAt()) && o.id().compareTo(after.id()) > 0);
            if (past && found.size() < limit) {
                found.add(new PendingRow(o, patientOfAdmission.get(o.admissionId()),
                        bedOfAdmission.getOrDefault(o.admissionId(), "101")));
            }
        }
        return found;
    }

    @Override
    public List<WaitingKey> findWaitingKeys(UUID tenantId) {
        findWaitingKeysCalls++;
        List<WaitingKey> keys = new ArrayList<>();
        for (OrderRow o : pendingOrders(true)) {
            keys.add(new WaitingKey(o.id(), o.version()));
        }
        return keys;
    }

    int countWaitingCalls;
    int findWaitingKeysCalls;

    @Override
    public int countWaiting(UUID tenantId) {
        countWaitingCalls++;
        return pendingOrders(false).size();
    }

    @Override
    public int countQueried(UUID tenantId) {
        int count = 0;
        for (OrderRow o : pendingOrders(true)) {
            count += QUERIED.equals(o.status()) ? 1 : 0;
        }
        return count;
    }

    private List<OrderRow> pendingOrders(boolean withQueried) {
        List<OrderRow> pending = new ArrayList<>();
        for (OrderRow o : rows.values()) {
            boolean waiting = PENDING.equals(o.status()) || (withQueried && QUERIED.equals(o.status()));
            if (waiting && patientOfAdmission.containsKey(o.admissionId()) && !discharged.contains(o.admissionId())) {
                pending.add(o);
            }
        }
        pending.sort(chart());
        return pending;
    }

    private static Comparator<OrderRow> chart() {
        return Comparator.comparing(OrderRow::orderedAt).thenComparing(OrderRow::id);
    }

    // ---- versions and holds ----

    /** The versions of an order; a fixture order gets its version 1 from the head the first time it is asked for. */
    List<MedicineOrderVersion> versionsOf(UUID orderId) {
        return versionRows.computeIfAbsent(orderId, id -> {
            OrderRow o = rows.get(id);
            List<MedicineOrderVersion> list = new ArrayList<>();
            list.add(new MedicineOrderVersion(id, 1, Kind.SCHEDULED, o.frequencyKey() == null ? "od" : o.frequencyKey(),
                    null, null, "ORAL", o.orderedAt(), null, null, OrderPriority.valueOf(o.priority()), null, null,
                    null, o.orderedBy(), o.orderedAt(), null, Verification.REQUIRED, o.verifiedBy(), o.verifiedAt()));
            return list;
        });
    }

    @Override
    public Instant insert(UUID tenantId, NewVersion v) {
        writes++;
        List<MedicineOrderVersion> list = versionRows.computeIfAbsent(v.orderId(), id -> new ArrayList<>());
        if (v.versionNo() != list.size() + 1) {
            throw new IllegalStateException("database: medicine order version must be the next number");
        }
        list.add(new MedicineOrderVersion(v.orderId(), v.versionNo(), v.kind(), v.frequencyKey(), v.doseAmount(),
                v.doseUnit(), v.routeKey(), v.startAt(), v.endAt(), v.indication(), v.priority(), v.minIntervalMin(),
                v.maxPer24h(), v.changeReason(), v.createdBy(), now, null, v.verification(), null, null));
        return now;
    }

    private void replace(UUID orderId, int versionNo, java.util.function.UnaryOperator<MedicineOrderVersion> change) {
        List<MedicineOrderVersion> list = versionsOf(orderId);
        list.set(versionNo - 1, change.apply(list.get(versionNo - 1)));
    }

    @Override
    public void markSuperseded(UUID tenantId, UUID orderId, int versionNo) {
        writes++;
        replace(orderId, versionNo, v -> new MedicineOrderVersion(v.orderId(), v.versionNo(), v.kind(),
                v.frequencyKey(), v.doseAmount(), v.doseUnit(), v.routeKey(), v.startAt(), v.endAt(), v.indication(),
                v.priority(), v.minIntervalMin(), v.maxPer24h(), v.changeReason(), v.createdBy(), v.createdAt(), now,
                v.verification(), v.verifiedBy(), v.verifiedAt()));
    }

    @Override
    public Instant markVerified(UUID tenantId, UUID orderId, int versionNo, UUID verifiedBy) {
        writes++;
        if (verifiedBy.equals(rows.get(orderId).orderedBy())) {
            throw new IllegalStateException("database: medicine order version cannot be verified by the orderer");
        }
        replace(orderId, versionNo, v -> new MedicineOrderVersion(v.orderId(), v.versionNo(), v.kind(),
                v.frequencyKey(), v.doseAmount(), v.doseUnit(), v.routeKey(), v.startAt(), v.endAt(), v.indication(),
                v.priority(), v.minIntervalMin(), v.maxPer24h(), v.changeReason(), v.createdBy(), v.createdAt(),
                v.supersededAt(), v.verification(), verifiedBy, now));
        return now;
    }

    @Override
    public Instant openHold(UUID tenantId, UUID orderId, UUID heldBy, String reason) {
        writes++;
        holdRows.computeIfAbsent(orderId, id -> new ArrayList<>())
                .add(new OrderHoldInterval(now, heldBy, reason, null, null));
        return now;
    }

    @Override
    public boolean closeHold(UUID tenantId, UUID orderId, UUID resumedBy) {
        List<OrderHoldInterval> list = holdRows.get(orderId);
        if (list == null || list.isEmpty() || !list.get(list.size() - 1).isOpen()) {
            return false;
        }
        writes++;
        OrderHoldInterval h = list.get(list.size() - 1);
        list.set(list.size() - 1, new OrderHoldInterval(h.heldAt(), h.heldBy(), h.holdReason(), now, resumedBy));
        return true;
    }

    @Override
    public Map<UUID, OrderTimeline> findTimelines(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, OrderTimeline> timelines = new LinkedHashMap<>();
        for (OrderRow o : rows.values()) {
            if (admissionIds.contains(o.admissionId())) {
                timelines.put(o.id(), new OrderTimeline(o.id(), o.admissionId(), OrderStatus.valueOf(o.status()),
                        versionsOf(o.id()), holdRows.getOrDefault(o.id(), List.of()), o.stoppedAt()));
            }
        }
        return timelines;
    }

    // ---- queries ----

    @Override
    public QueryRow insert(UUID tenantId, UUID orderId, int orderVersion, String question, UUID askedBy) {
        writes++;
        QueryRow row = new QueryRow(UUID.randomUUID(), orderId, orderVersion, question, askedBy, now, null, null, null,
                null, null);
        queryRows.computeIfAbsent(orderId, id -> new ArrayList<>()).add(row);
        return row;
    }

    @Override
    public Optional<QueryRow> findOpen(UUID tenantId, UUID orderId) {
        List<QueryRow> list = queryRows.getOrDefault(orderId, List.of());
        return list.stream().filter(QueryRow::open).findFirst();
    }

    @Override
    public Map<UUID, QueryRow> findLatest(UUID tenantId, Collection<UUID> orderIds) {
        Map<UUID, QueryRow> latest = new HashMap<>();
        for (UUID id : orderIds) {
            List<QueryRow> list = queryRows.getOrDefault(id, List.of());
            if (!list.isEmpty()) {
                latest.put(id, list.get(list.size() - 1));
            }
        }
        return latest;
    }

    @Override
    public boolean close(UUID tenantId, UUID orderId, String closedBy, String reply, UUID repliedBy) {
        List<QueryRow> list = queryRows.get(orderId);
        if (list == null || list.isEmpty() || !list.get(list.size() - 1).open()) {
            return false;
        }
        writes++;
        QueryRow q = list.get(list.size() - 1);
        list.set(list.size() - 1, new QueryRow(q.id(), q.orderId(), q.orderVersion(), q.question(), q.askedBy(),
                q.askedAt(), reply, repliedBy, reply == null ? null : now, closedBy, now));
        return true;
    }
}
