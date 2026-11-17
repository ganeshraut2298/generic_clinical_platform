package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
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
 * the services: the unique order, slot and attempt, the unique client key, the checks between outcome, reason, second
 * nurse, scan method and override, {@code recorded_at} and {@code undo_until} stamped by the "database", and the append-only
 * corrections (undo and in error). {@code writes} counts inserts so that a test can prove a refusal or a replay wrote
 * nothing; {@code scans} keeps the scanned text of each insert for assertions.
 */
class FakeDoses implements DoseRepository {

    final List<DoseRow> rows = new ArrayList<>();
    final List<CorrectionRow> corrections = new ArrayList<>();
    final Map<UUID, String> scans = new HashMap<>();
    final List<NewDose> inserted = new ArrayList<>();
    final List<OpenNotice> notices = new ArrayList<>();
    int writes;
    /** When set, every insert adds "insert". */
    List<String> trace;
    /** When set, the insert throws it (a database refusal). */
    RuntimeException failWith;
    Instant recordedAt = Instant.parse("2026-11-11T06:00:00Z");
    int undoSeconds = 10;
    /** The orders the MAR lists (set by the test base). */
    FakeMedicineOrders orders;

    @Override
    public Optional<DoseRow> findByClientKey(UUID tenantId, UUID clientKey) {
        return rows.stream().filter(d -> d.clientKey().equals(clientKey)).findFirst();
    }

    @Override
    public Optional<DoseRow> findByOrderAndSlot(UUID tenantId, UUID orderId, Instant slotAt) {
        return rows.stream().filter(d -> d.orderId().equals(orderId) && d.slotAt().equals(slotAt))
                .max(Comparator.comparingInt(DoseRow::attempt));
    }

    @Override
    public Optional<DoseRow> findById(UUID tenantId, UUID doseId) {
        return rows.stream().filter(d -> d.id().equals(doseId)).findFirst();
    }

    @Override
    public List<DoseRow> findBetween(UUID tenantId, UUID admissionId, Instant from, Instant to) {
        List<DoseRow> found = new ArrayList<>();
        for (DoseRow d : rows) {
            if (d.admissionId().equals(admissionId) && !d.slotAt().isBefore(from) && d.slotAt().isBefore(to)) {
                found.add(d);
            }
        }
        found.sort(Comparator.comparing(DoseRow::slotAt).thenComparingInt(DoseRow::attempt)
                .thenComparing(DoseRow::id));
        return found;
    }

    @Override
    public List<DoseRow> findLastPerOrder(UUID tenantId, UUID admissionId) {
        Map<UUID, DoseRow> last = new HashMap<>();
        for (DoseRow d : rows) {
            if (d.admissionId().equals(admissionId) && d.live()) {
                last.merge(d.orderId(), d, (a, b) -> a.slotAt().isAfter(b.slotAt()) ? a : b);
            }
        }
        return new ArrayList<>(last.values());
    }

    @Override
    public Map<UUID, Instant> findLastGiven(UUID tenantId, UUID admissionId) {
        Map<UUID, Instant> last = new HashMap<>();
        for (DoseRow d : rows) {
            if (d.admissionId().equals(admissionId) && d.live() && d.given()) {
                last.merge(d.orderId(), d.recordedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return last;
    }

    @Override
    public List<GivenAt> findGivenSince(UUID tenantId, UUID admissionId, Instant since) {
        List<GivenAt> found = new ArrayList<>();
        for (DoseRow d : rows) {
            if (d.admissionId().equals(admissionId) && d.live() && d.given() && !d.recordedAt().isBefore(since)) {
                found.add(new GivenAt(d.orderId(), d.recordedAt()));
            }
        }
        return found;
    }

    @Override
    public boolean hasLiveGiven(UUID tenantId, UUID orderId) {
        return rows.stream().anyMatch(d -> d.orderId().equals(orderId) && d.live() && d.given());
    }

    @Override
    public Stored insert(UUID tenantId, NewDose dose) {
        if (trace != null) {
            trace.add("insert");
        }
        if (failWith != null) {
            throw failWith;
        }
        if (rows.stream().anyMatch(d -> d.orderId().equals(dose.orderId()) && d.slotAt().equals(dose.slotAt())
                && d.attempt() == dose.attempt())) {
            throw new DuplicateKeyException("uq_dose_administration_order_slot");
        }
        if (findByClientKey(tenantId, dose.clientKey()).isPresent()) {
            throw new DuplicateKeyException("uq_dose_administration_tenant_client_key");
        }
        boolean given = GIVEN.equals(dose.outcome());
        boolean override = "OVERRIDE".equals(dose.scanMethod());
        if ((given && dose.reasonKey() != null) || (!given && dose.reasonKey() == null)
                || (given && dose.identityScan() == null)
                || (override && dose.medicineScan() != null)
                || "WRISTBAND_DAMAGED".equals(dose.overrideReasonKey()) || (!given && dose.identityScan() != null)
                || (given && !Boolean.TRUE.equals(dose.identityConfirmed())) || (!given && dose.identityConfirmed() != null)
                || (!given && dose.secondCheckBy() != null) || (dose.highAlert() && given && dose.secondCheckBy() == null)
                || (dose.secondCheckBy() != null && dose.secondCheckBy().equals(dose.recordedBy()))
                || ("REFUSED".equals(dose.outcome()) != "PATIENT_REFUSED".equals(dose.reasonKey()))
                || (dose.identityScan() != null && dose.identityScan().length() > 40)
                || (given && dose.scanMethod() == null) || (!given && dose.scanMethod() != null)
                || (override != (dose.overrideReasonKey() != null)) || (override != (dose.overrideNote() != null))
                || (dose.attempt() > 1 && dose.supersedesDoseId() == null)
                || ("OTHER".equals(dose.reasonKey()) && dose.notGivenNote() == null)) {
            throw new IllegalStateException("database: ck_dose_administration");
        }
        writes++;
        recordedAt = recordedAt.plusSeconds(1);
        UUID id = UUID.randomUUID();
        Instant undoUntil = given ? recordedAt.plusSeconds(undoSeconds) : null;
        rows.add(new DoseRow(id, dose.admissionId(), dose.orderId(), dose.clientKey(), dose.slotAt(), dose.outcome(),
                dose.reasonKey(), dose.recordedBy(), dose.secondCheckBy(), dose.highAlert(), recordedAt,
                dose.attempt(), dose.orderVersion(), dose.doseKind(), dose.scanMethod(), dose.overrideReasonKey(),
                dose.beforeVerification(), undoUntil, null));
        scans.put(id, dose.identityScan());
        inserted.add(dose);
        return new Stored(id, recordedAt, undoUntil);
    }

    /** One scan stop as the fake keeps it (the database sets the time; here the fake order store's clock). */
    record Stop(UUID admissionId, UUID orderId, Instant slotAt, String checkKind, UUID actor, Instant stoppedAt) {
    }

    final List<Stop> stops = new ArrayList<>();

    @Override
    public void insertScanStop(UUID tenantId, UUID admissionId, UUID orderId, Instant slotAt, String checkKind,
                               UUID actor) {
        if (trace != null) {
            trace.add("scan-stop");
        }
        stops.add(new Stop(admissionId, orderId, slotAt, checkKind, actor, orders.databaseNow()));
    }

    @Override
    public boolean hasScanStopWithin15Minutes(UUID tenantId, UUID orderId, UUID actor, Instant slotAtOrNull) {
        Instant from = orders.databaseNow().minusSeconds(15 * 60);
        return stops.stream().anyMatch(s -> s.orderId().equals(orderId) && s.actor().equals(actor)
                && s.stoppedAt().isAfter(from) && (slotAtOrNull == null || s.slotAt().equals(slotAtOrNull)));
    }

    @Override
    public Instant insertCorrection(UUID tenantId, NewCorrection correction) {
        if (trace != null) {
            trace.add("correction");
        }
        if (failWith != null) {
            throw failWith;
        }
        DoseRow dose = findById(tenantId, correction.doseId()).orElseThrow();
        if (dose.correction() != null) {
            throw new DuplicateKeyException("uq_dose_correction_dose");
        }
        writes++;
        recordedAt = recordedAt.plusSeconds(1);
        CorrectionRow row = new CorrectionRow(dose.id(), correction.kind(), correction.reasonKey(),
                correction.correctedBy(), recordedAt, correction.clientKey());
        corrections.add(row);
        rows.replaceAll(d -> d.id().equals(dose.id()) ? d.corrected(row) : d);
        return recordedAt;
    }

    @Override
    public Optional<CorrectionRow> findCorrectionByClientKey(UUID tenantId, UUID clientKey) {
        return corrections.stream().filter(c -> c.clientKey().equals(clientKey)).findFirst();
    }

    @Override
    public List<MarHead> findMarHeads(UUID tenantId, UUID admissionId, Instant from, Instant to) {
        List<MarHead> heads = new ArrayList<>();
        if (orders == null) {
            return heads;
        }
        for (OrderRow o : orders.findByAdmission(tenantId, admissionId)) {
            boolean live = !"STOPPED".equals(o.status()) && !"REJECTED".equals(o.status());
            boolean stoppedToday = "STOPPED".equals(o.status()) && o.stoppedAt() != null && !o.stoppedAt().isBefore(from)
                    && o.stoppedAt().isBefore(to);
            boolean rejectedToday = "REJECTED".equals(o.status()) && o.rejectedAt() != null
                    && !o.rejectedAt().isBefore(from) && o.rejectedAt().isBefore(to);
            if (live || stoppedToday || rejectedToday) {
                heads.add(new MarHead(o.id(), o.drugKey(), o.status(), o.orderedBy(), o.orderedAt(), 1, o.stoppedBy(),
                        o.stoppedAt()));
            }
        }
        return heads;
    }

    @Override
    public List<OpenNotice> findOpenNotices(UUID tenantId, UUID admissionId) {
        return List.copyOf(notices);
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
