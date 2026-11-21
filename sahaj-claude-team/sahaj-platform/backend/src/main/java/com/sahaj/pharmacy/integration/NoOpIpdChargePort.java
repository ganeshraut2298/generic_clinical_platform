package com.sahaj.pharmacy.integration;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The pharmacy-owned default of TP-5 and TP-6: not connected. A charge or a credit is never posted; the caller keeps the line as
 * "Charge pending" or "Credit pending" and "Post waiting charges" answers 409 {@code not-connected} while this is the port. It
 * answers ADMISSION_NOT_FOUND so that no caller can mistake it for a posting.
 */
@Component
public class NoOpIpdChargePort implements IpdChargePort {

    @Override
    public boolean connected() {
        return false;
    }

    @Override
    public Outcome charge(UUID tenantId, ChargeLine line) {
        return new Outcome(Result.ADMISSION_NOT_FOUND, null);
    }

    @Override
    public Outcome credit(UUID tenantId, ChargeLine line) {
        return new Outcome(Result.ADMISSION_NOT_FOUND, null);
    }
}
