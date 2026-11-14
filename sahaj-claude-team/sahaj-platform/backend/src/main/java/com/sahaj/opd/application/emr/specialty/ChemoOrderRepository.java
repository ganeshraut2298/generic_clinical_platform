package com.sahaj.opd.application.emr.specialty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code opd.chemo_order} (ADR-023 D6): one order per oncology OPD visit record. Row level security limits every
 * statement to the bound clinic and the tenant id is in each statement too. Updates are compare-and-set on the revision
 * and only touch the columns sahaj_app may update; the database refuses a SENT row without a confirmation equal to the
 * fingerprint, a checker who is the prescriber or the last editor, and any change of a SENT or HELD row.
 */
public interface ChemoOrderRepository {

    /** One page of the checker's list. */
    record Waiting(ChemoOrderRow row, long total) {
    }

    Optional<ChemoOrderRow> findByRecord(UUID tenantId, UUID recordId);

    /** The order locked until the end of the transaction (the order row lock of every write step). */
    Optional<ChemoOrderRow> findByRecordForUpdate(UUID tenantId, UUID recordId);

    Optional<ChemoOrderRow> findById(UUID tenantId, UUID orderId);

    Optional<ChemoOrderRow> findByIdForUpdate(UUID tenantId, UUID orderId);

    void insert(UUID tenantId, ChemoOrderRow row, UUID actorUserId);

    /** Writes every updatable column of the row, {@code revision + 1}. False when the revision moved. */
    boolean update(UUID tenantId, ChemoOrderRow row, long expectedRevision, UUID actorUserId);

    /** The patient's SENT orders, oldest first (cycle 1 weight, lifetime dose, consent per protocol, history). */
    List<ChemoOrderRow> sentForPatient(UUID tenantId, UUID patientId);

    /** DRAFT orders that name this membership as checker and are not confirmed, oldest naming first, one page. */
    List<ChemoOrderRow> waitingFor(UUID tenantId, UUID checkerMembershipId, int offset, int limit);

    long countWaitingFor(UUID tenantId, UUID checkerMembershipId);
}
