package com.sahaj.platform.application;

import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.application.ModuleEntitlementRepository.EntitlementRow;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements the public {@link ModuleEntitlementPort} for other modules (SAH-19). */
@Service
public class ModuleEntitlementAdapter implements ModuleEntitlementPort {

    private final ModuleEntitlementRepository repository;
    private final Clock clock;

    public ModuleEntitlementAdapter(ModuleEntitlementRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ModuleEntitlement forTenant(UUID tenantId) {
        EntitlementRow row = repository.find(tenantId).orElse(ModuleEntitlements.DEFAULTS);
        return new ModuleEntitlement(ModuleEntitlements.effectiveIpdState(row, clock.instant()), row.hraOn(),
                row.bedLimit());
    }
}
