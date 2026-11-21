package com.sahaj.pharmacy.integration;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TP-4 (SAH-80-13, 80-14, 80-17 read; ADR-030 S12): IPD medicine orders that passed the IPD pharmacist check, and discharges
 * that were started. Owned by the pharmacy module; the only implementation in phase 1 is {@link NoOpIpdOrderSource}. A real one
 * needs a read port in the IPD module and a human approval first (DEC-072 Q2). Runs in the caller's transaction.
 */
public interface IpdOrderSource {

    boolean connected();

    /** IPD is on and not read-only for the clinic. */
    boolean ipdUsable(UUID tenantId);

    /** ACTIVE orders, orders STOPPED since the last supply, and ON_HOLD orders. */
    List<OrderRow> openOrders(UUID tenantId);

    List<OrderRow> ordersOf(UUID tenantId, UUID admissionId);

    /** The current version of each order, to detect an order that changed after it was picked. */
    Map<UUID, Integer> currentVersions(UUID tenantId, Collection<UUID> orderIds);

    /** Empty when the feed does not report discharges (TP-7 is information only). */
    List<Discharge> dischargesStarted(UUID tenantId);

    record OrderRow(UUID orderId, int version, UUID admissionId, String admissionNo, UUID patientId, UUID wardId,
                    String wardName, String bedLabel, UUID drugId, String drugText, String orderText, String priority,
                    String status, String pharmacistCheck, String verifiedBy, Instant orderedAt, String prescriberName) {

        @Override
        public String toString() {
            return "OrderRow[orderId=" + orderId + ", version=" + version + "]";
        }
    }

    record Discharge(UUID admissionId, Instant startedAt, Instant expectedAt) {
    }
}
