package com.sahaj.pharmacy.integration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The pharmacy-owned default of TP-4: not connected, IPD not usable, no orders and no discharges. */
@Component
public class NoOpIpdOrderSource implements IpdOrderSource {

    @Override
    public boolean connected() {
        return false;
    }

    @Override
    public boolean ipdUsable(UUID tenantId) {
        return false;
    }

    @Override
    public List<OrderRow> openOrders(UUID tenantId) {
        return List.of();
    }

    @Override
    public List<OrderRow> ordersOf(UUID tenantId, UUID admissionId) {
        return List.of();
    }

    @Override
    public Map<UUID, Integer> currentVersions(UUID tenantId, Collection<UUID> orderIds) {
        return Map.of();
    }

    @Override
    public List<Discharge> dischargesStarted(UUID tenantId) {
        return List.of();
    }
}
