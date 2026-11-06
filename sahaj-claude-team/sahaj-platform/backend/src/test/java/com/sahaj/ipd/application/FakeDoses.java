package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * An in-memory {@link DoseRepository} for unit tests (no database). It keeps the rules the database keeps that matter to
 * the services: the unique order and slot, the unique client key, the checks between outcome, reason, second nurse and
 * scan, and {@code recorded_at} stamped by the "database". {@code writes} counts inserts so that a test can prove a
 * refusal or a replay wrote nothing; {@code scans} keeps the scanned text of each insert for assertions.
 */
class FakeDoses implements DoseRepository {

    final List<DoseRow> rows = new ArrayList<>();
    final Map<UUID, String> scans = new HashMap<>();
    final List<NewDose> inserted = new ArrayList<>();
    int writes;
    /** When set, every insert adds "insert". */
    List<String> trace;
    /** When set, the insert throws it (a database refusal). */
    RuntimeException failWith;
    Instant recordedAt = Instant.parse("2026-11-11T06:00:00Z");

    @Override
    public Optional<DoseRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return rows.stream().filter(d -> d.clientKey().equals(clientKey)).findFirst();
    }

    @Override
    public Optional<DoseRow> findByOrderAndSlot(UUID tenantId, UUID orderId, Instant slotAt) {
        return rows.stream().filter(d -> d.orderId().equals(orderId) && d.slotAt().equals(slotAt)).findFirst();
    }

    @Override
    public List<DoseRow> findBetween(UUID tenantId, UUID admissionId, Instant from, Instant to) {
        List<DoseRow> found = new ArrayList<>();
        for (DoseRow d : rows) {
            if (d.admissionId().equals(admissionId) && !d.slotAt().isBefore(from) && d.slotAt().isBefore(to)) {
                found.add(d);
            }
        }
        found.sort(Comparator.comparing(DoseRow::slotAt).thenComparing(DoseRow::id));
        return found;
    }

    @Override
    public List<DoseRow> findLastPerOrder(UUID tenantId, UUID admissionId) {
        Map<UUID, DoseRow> last = new HashMap<>();
        for (DoseRow d : rows) {
            if (d.admissionId().equals(admissionId)) {
                last.merge(d.orderId(), d, (a, b) -> a.slotAt().isAfter(b.slotAt()) ? a : b);
            }
        }
        return new ArrayList<>(last.values());
    }

    @Override
    public Stored insert(UUID tenantId, NewDose dose) {
        if (trace != null) {
            trace.add("insert");
        }
        if (failWith != null) {
            throw failWith;
        }
        if (findByOrderAndSlot(tenantId, dose.orderId(), dose.slotAt()).isPresent()) {
            throw new DuplicateKeyException("uq_dose_administration_order_slot");
        }
        if (findByClientKey(tenantId, dose.clientKey()).isPresent()) {
            throw new DuplicateKeyException("uq_dose_administration_tenant_client_key");
        }
        boolean given = GIVEN.equals(dose.outcome());
        if ((given && dose.reasonKey() != null) || (!given && dose.reasonKey() == null)
                || (given && dose.identityScan() == null) || (!given && dose.identityScan() != null)
                || (given && !Boolean.TRUE.equals(dose.identityConfirmed())) || (!given && dose.identityConfirmed() != null)
                || (!given && dose.secondCheckBy() != null) || (dose.highAlert() && given && dose.secondCheckBy() == null)
                || (dose.secondCheckBy() != null && dose.secondCheckBy().equals(dose.recordedBy()))
                || ("REFUSED".equals(dose.outcome()) != "PATIENT_REFUSED".equals(dose.reasonKey()))
                || (dose.identityScan() != null && dose.identityScan().length() > 40)) {
            throw new IllegalStateException("database: ck_dose_administration");
        }
        writes++;
        recordedAt = recordedAt.plusSeconds(1);
        UUID id = UUID.randomUUID();
        rows.add(new DoseRow(id, dose.admissionId(), dose.orderId(), dose.clientKey(), dose.slotAt(), dose.outcome(),
                dose.reasonKey(), dose.recordedBy(), dose.secondCheckBy(), dose.highAlert(), recordedAt));
        scans.put(id, dose.identityScan());
        inserted.add(dose);
        return new Stored(id, recordedAt);
    }

    /** Adds a dose directly (a fixture), without counting a write. */
    DoseRow seed(UUID admissionId, UUID orderId, Instant slotAt, String outcome, String reasonKey, UUID recordedBy,
                 UUID secondCheckBy, boolean highAlert) {
        DoseRow row = new DoseRow(UUID.randomUUID(), admissionId, orderId, UUID.randomUUID(), slotAt, outcome,
                reasonKey, recordedBy, secondCheckBy, highAlert, slotAt.plusSeconds(240));
        rows.add(row);
        return row;
    }
}
