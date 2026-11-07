package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineRoundRepository.ActiveOrder;
import com.sahaj.ipd.application.MedicineRoundRepository.TodayDose;
import com.sahaj.ipd.domain.DoseOutcome;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.MedicineRound.Recorded;
import com.sahaj.ipd.domain.MedicineRound.RoundOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SAH-35 reads the Medicine round for one answer (ADR-012 D1): query 1 (ACTIVE orders of ADMITTED admissions), query 2
 * (today's dose records of those admissions, the IST day of {@code asOf}), then the one pure function
 * {@link MedicineRound#compute}. Always exactly these two statements (one when there is no order), whatever the number
 * of patients. The caller runs it inside its own read-only transaction, against its own {@code asOf}. The drug name and
 * high-alert class come from the cached catalogue, never from a join.
 *
 * <p>One damaged order must not blank the Safety board, the summary poll, the banner and the Admitted list of the whole
 * clinic (code review R35-1): an order whose drug is not in the catalogue or whose frequency is unknown is left out of
 * the round, with one warning that holds the clinic id and the order id only. The high-alert class is never guessed.
 * The single-patient medicine sheet and MAR keep their loud failure. A dose record with an unknown outcome is still a
 * fault and fails loudly. No name, drug text or key is ever logged.
 */
@Component
class MedicineRoundReader {

    private static final Logger log = LoggerFactory.getLogger(MedicineRoundReader.class);

    private final MedicineRoundRepository repository;
    private final MedicineCatalogue catalogue;

    MedicineRoundReader(MedicineRoundRepository repository, MedicineCatalogue catalogue) {
        this.repository = repository;
        this.catalogue = catalogue;
    }

    MedicineRound.Result read(UUID tenantId, Instant asOf) {
        List<ActiveOrder> active = repository.findActiveOfAdmitted(tenantId);
        if (active.isEmpty()) {
            return MedicineRound.empty();
        }
        Set<UUID> admissionIds = new LinkedHashSet<>();
        List<RoundOrder> orders = new ArrayList<>();
        for (ActiveOrder o : active) {
            Optional<Drug> drug = catalogue.catalogue().find(o.drugKey());
            if (drug.isEmpty()) {
                skipped(tenantId, o, "DRUG_NOT_IN_CATALOGUE");
                continue;
            }
            Optional<Frequency> frequency = Frequency.ofKey(o.frequencyKey());
            if (frequency.isEmpty()) {
                skipped(tenantId, o, "FREQUENCY_UNKNOWN");
                continue;
            }
            admissionIds.add(o.admissionId());
            orders.add(new RoundOrder(o.orderId(), o.admissionId(), o.patientId(), o.bedCode(), drug.get().name(),
                    drug.get().highAlert(), frequency.get(), o.verifiedAt()));
        }
        if (orders.isEmpty()) {
            return MedicineRound.empty();
        }
        List<Recorded> recorded = new ArrayList<>();
        for (TodayDose d : repository.findDosesBetween(tenantId, admissionIds, DoseSlots.dayStart(asOf),
                DoseSlots.dayEnd(asOf))) {
            DoseOutcome outcome = DoseOutcome.ofKey(d.outcome())
                    .orElseThrow(() -> new IllegalStateException("A dose has an unknown outcome"));
            recorded.add(new Recorded(d.orderId(), d.slotAt(), outcome));
        }
        return MedicineRound.compute(orders, recorded, asOf);
    }

    /** The clinic id, the order id and a fixed cause word: nothing a person or a drug could be read from. */
    private static void skipped(UUID tenantId, ActiveOrder order, String cause) {
        log.warn("medicine-round order-skipped tenantId={} orderId={} cause={}", tenantId, order.orderId(), cause);
    }
}
