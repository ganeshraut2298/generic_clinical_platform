package com.sahaj.pharmacy.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The pharmacy-owned default of TP-2: not connected, nothing signed, nothing read. */
@Component
public class NoOpOpdPrescriptionSource implements OpdPrescriptionSource {

    @Override
    public boolean connected() {
        return false;
    }

    @Override
    public List<RxHeader> signedOn(UUID tenantId, LocalDate istDay) {
        return List.of();
    }

    @Override
    public Optional<Rx> read(UUID tenantId, UUID recordId) {
        return Optional.empty();
    }
}
