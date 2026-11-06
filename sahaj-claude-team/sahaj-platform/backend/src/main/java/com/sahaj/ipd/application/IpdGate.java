package com.sahaj.ipd.application;

import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The IPD state check every IPD call starts with (Off answers 403 module-off; a write in Read-only answers 409
 * ipd-readonly) and the clock rounded to what the database stores. Shared by {@link IpdService},
 * {@link AdmissionService} and {@link BedBoardService}.
 */
@Component
class IpdGate {

    private final ModuleEntitlementPort entitlementPort;
    private final Clock clock;

    IpdGate(ModuleEntitlementPort entitlementPort, Clock clock) {
        this.entitlementPort = entitlementPort;
        this.clock = clock;
    }

    ModuleEntitlement check(UUID tenantId, boolean write) {
        ModuleEntitlement ent = entitlementPort.forTenant(tenantId);
        String state = ent.ipdState();
        if (!"trial".equals(state) && !"on".equals(state) && !"readonly".equals(state)) {
            throw new ModuleOffException("In-patients (IPD) is not switched on for your clinic.");
        }
        if (write && "readonly".equals(state)) {
            throw new ConflictException("ipd-readonly", "IPD is read-only", "IPD is read-only. Nothing can be changed.");
        }
        return ent;
    }

    /**
     * SAH-27: the Read-only refusal on its own, for a write whose contract checks it after other answers (for
     * example a discharge answers 404 and not-admitted before ipd-readonly). {@link #check} with write true is the
     * same refusal for every other write.
     */
    void requireWritable(ModuleEntitlement ent) {
        if ("readonly".equals(ent.ipdState())) {
            throw new ConflictException("ipd-readonly", "IPD is read-only", "IPD is read-only. Nothing can be changed.");
        }
    }

    /** The current instant, cut to microseconds (what PostgreSQL keeps). */
    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
