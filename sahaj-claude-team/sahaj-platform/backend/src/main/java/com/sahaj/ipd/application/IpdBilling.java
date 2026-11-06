package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.ChargeRepository.ChargeRow;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.TransferRepository.TransferRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-25 loads what the running bill needs (category rates, settings, charges) and hands out a {@link Batch} that
 * computes the bill of any admission. The Admitted list and the bed board load once for all admitted patients
 * (one query for the charges and one for the bed transfers (SAH-26); the deposit sums come with the admission rows),
 * so there is no query per row.
 */
@Component
class IpdBilling {

    private final ChargeRepository charges;
    private final TransferRepository transfers;
    private final IpdCatalog catalog;

    IpdBilling(ChargeRepository charges, TransferRepository transfers, IpdCatalog catalog) {
        this.charges = charges;
        this.transfers = transfers;
        this.catalog = catalog;
    }

    /** For every admitted patient of the clinic. */
    Batch forAdmitted(UUID tenantId, Instant now, int alertPercent) {
        return forAdmitted(tenantId, now, alertPercent, catalog.categories(tenantId), catalog.config(tenantId));
    }

    /** As above, for a caller that has already loaded the categories and settings (code review 6). */
    Batch forAdmitted(UUID tenantId, Instant now, int alertPercent, Map<String, CategoryRow> categories,
                      ConfigRow config) {
        Map<UUID, List<ChargeRow>> chargesByAdmission = new HashMap<>();
        for (ChargeRow row : charges.findForAdmitted(tenantId)) {
            chargesByAdmission.computeIfAbsent(row.admissionId(), k -> new ArrayList<>()).add(row);
        }
        Map<UUID, List<TransferRow>> transfersByAdmission = new HashMap<>();
        for (TransferRow row : transfers.findForAdmitted(tenantId)) {
            transfersByAdmission.computeIfAbsent(row.admissionId(), k -> new ArrayList<>()).add(row);
        }
        return new Batch(categories, config, chargesByAdmission, transfersByAdmission, now, alertPercent);
    }

    /** For one admission. */
    Batch forOne(UUID tenantId, UUID admissionId, Instant now, int alertPercent) {
        Map<UUID, List<ChargeRow>> chargesByAdmission = new HashMap<>();
        chargesByAdmission.put(admissionId, new ArrayList<>(charges.findByAdmission(tenantId, admissionId)));
        Map<UUID, List<TransferRow>> transfersByAdmission = new HashMap<>();
        transfersByAdmission.put(admissionId, new ArrayList<>(transfers.findByAdmission(tenantId, admissionId)));
        return new Batch(catalog.categories(tenantId), catalog.config(tenantId), chargesByAdmission,
                transfersByAdmission, now, alertPercent);
    }

    static final class Batch {
        private final Map<String, CategoryRow> categories;
        private final ConfigRow config;
        private final Map<UUID, List<ChargeRow>> charges;
        private final Map<UUID, List<TransferRow>> transfers;
        private final Instant now;
        private final int alertPercent;

        private Batch(Map<String, CategoryRow> categories, ConfigRow config, Map<UUID, List<ChargeRow>> charges,
                      Map<UUID, List<TransferRow>> transfers, Instant now, int alertPercent) {
            this.categories = categories;
            this.config = config;
            this.charges = charges;
            this.transfers = transfers;
            this.now = now;
            this.alertPercent = alertPercent;
        }

        int alertPercent() {
            return alertPercent;
        }

        RunningBill bill(AdmissionRow row, String doctorName) {
            return RunningBill.compute(row.admittedAt(), now, config, row.categoryKey(), categories, doctorName,
                    charges.getOrDefault(row.id(), List.of()), transfers.getOrDefault(row.id(), List.of()),
                    row.depositTotalPaise(), alertPercent);
        }
    }
}
