package com.sahaj.ipd.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@link MedicineOrderRepository} for unit tests (no database). It keeps the rules the database keeps
 * that matter to the services: transitions only from the allowed statuses, the version counted up, the times stamped
 * by the "database", and the queue limited to admissions registered as admitted. {@code writes} counts every insert and
 * every update so a test can prove that a refusal or a replay wrote nothing.
 */
class FakeMedicineOrders implements MedicineOrderRepository {

    static final Instant START = Instant.parse("2026-11-11T05:00:00Z");

    final Map<UUID, OrderRow> rows = new HashMap<>();
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
                order.frequencyKey(), PENDING, order.orderedBy(), nextOrderTime, order.overrideReasonKey(),
                order.warnings(), null, null, null, null, null, null, null, 0);
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
        OrderRow row = new OrderRow(UUID.randomUUID(), admissionId, UUID.randomUUID(), drugKey, "od", status,
                orderedBy, nextOrderTime, null, List.of(),
                MedicineOrderRepository.ACTIVE.equals(status) ? by : null,
                MedicineOrderRepository.ACTIVE.equals(status) ? nextOrderTime.plusSeconds(5) : null,
                MedicineOrderRepository.REJECTED.equals(status) ? by : null,
                MedicineOrderRepository.REJECTED.equals(status) ? nextOrderTime.plusSeconds(5) : null,
                MedicineOrderRepository.REJECTED.equals(status) ? "WRONG_DOSE" : null,
                MedicineOrderRepository.STOPPED.equals(status) ? by : null,
                MedicineOrderRepository.STOPPED.equals(status) ? nextOrderTime.plusSeconds(5) : null, 0);
        rows.put(row.id(), row);
        return row;
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
        writes++;
        rows.put(orderId, new OrderRow(o.id(), o.admissionId(), o.clientKey(), o.drugKey(), o.frequencyKey(), ACTIVE,
                o.orderedBy(), o.orderedAt(), o.overrideReasonKey(), o.warnings(), verifiedBy, now, null, null, null,
                null, null, o.version() + 1));
        return true;
    }

    @Override
    public boolean markRejected(UUID tenantId, UUID orderId, UUID rejectedBy, String reasonKey) {
        OrderRow o = rows.get(orderId);
        if (o == null || !PENDING.equals(o.status())) {
            return false;
        }
        writes++;
        rows.put(orderId, new OrderRow(o.id(), o.admissionId(), o.clientKey(), o.drugKey(), o.frequencyKey(),
                REJECTED, o.orderedBy(), o.orderedAt(), o.overrideReasonKey(), o.warnings(), null, null, rejectedBy,
                now, reasonKey, null, null, o.version() + 1));
        return true;
    }

    @Override
    public boolean markStopped(UUID tenantId, UUID orderId, UUID stoppedBy) {
        OrderRow o = rows.get(orderId);
        if (o == null || !o.live()) {
            return false;
        }
        writes++;
        rows.put(orderId, new OrderRow(o.id(), o.admissionId(), o.clientKey(), o.drugKey(), o.frequencyKey(),
                STOPPED, o.orderedBy(), o.orderedAt(), o.overrideReasonKey(), o.warnings(), o.verifiedBy(),
                o.verifiedAt(), null, null, null, stoppedBy, now, o.version() + 1));
        return true;
    }

    @Override
    public List<PendingRow> findPending(UUID tenantId, Position after, int limit) {
        List<OrderRow> pending = pendingOrders();
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
        for (OrderRow o : pendingOrders()) {
            keys.add(new WaitingKey(o.id(), o.version()));
        }
        return keys;
    }

    int countWaitingCalls;
    int findWaitingKeysCalls;

    @Override
    public int countWaiting(UUID tenantId) {
        countWaitingCalls++;
        return pendingOrders().size();
    }

    private List<OrderRow> pendingOrders() {
        List<OrderRow> pending = new ArrayList<>();
        for (OrderRow o : rows.values()) {
            if (PENDING.equals(o.status()) && patientOfAdmission.containsKey(o.admissionId())
                    && !discharged.contains(o.admissionId())) {
                pending.add(o);
            }
        }
        pending.sort(chart());
        return pending;
    }

    private static Comparator<OrderRow> chart() {
        return Comparator.comparing(OrderRow::orderedAt).thenComparing(OrderRow::id);
    }
}
