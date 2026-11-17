package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineRoundRepository.ActiveOrder;
import com.sahaj.ipd.application.MedicineRoundRepository.TodayDose;
import com.sahaj.ipd.domain.DoseOutcome;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.MedicineRound;
import com.sahaj.ipd.domain.MedicineRound.Recorded;
import com.sahaj.ipd.domain.MedicineRound.RoundOrder;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SAH-35 and SAH-76 reads the Medicine round for one answer (ADR-012 D1, ADR-027 D2): query 1 (ACTIVE orders, and PENDING STAT
 * orders, of ADMITTED admissions), query 2 (today's live dose records of those admissions, the IST day of {@code asOf}),
 * the order timelines (versions, holds, stop: one set read each) and the settings in force (one statement for every
 * patient), then the one pure function {@link MedicineRound#compute}. A fixed number of statements (one when there is no
 * order), whatever the number of patients. The caller runs it inside its own read-only transaction, against its own {@code asOf}. The drug name and
 * high-alert class come from the cached catalogue, never from a join.
 *
 * <p>One damaged order must not blank the Safety board, the summary poll, the banner and the Admitted list of the whole
 * clinic (code review R35-1): an order whose drug is not in the catalogue or that has no timeline is left out of
 * the round, with one warning that holds the clinic id and the order id only. The high-alert class is never guessed.
 * The single-patient medicine sheet and MAR keep their loud failure. A dose record with an unknown outcome is still a
 * fault and fails loudly. No name, drug text or key is ever logged.
 */
@Component
class MedicineRoundReader {

    private static final Logger log = LoggerFactory.getLogger(MedicineRoundReader.class);

    private final MedicineRoundRepository repository;
    private final MedicineCatalogue catalogue;
    private final SkippedOrderWarnings warnings;
    private final OrderTimelineRepository timelines;
    private final NursingSettingsReader settingsReader;

    MedicineRoundReader(MedicineRoundRepository repository, MedicineCatalogue catalogue, Clock clock,
                        OrderTimelineRepository timelines, NursingSettingsReader settingsReader) {
        this.repository = repository;
        this.catalogue = catalogue;
        this.warnings = new SkippedOrderWarnings(clock);
        this.timelines = timelines;
        this.settingsReader = settingsReader;
    }

    MedicineRound.Result read(UUID tenantId, Instant asOf) {
        return read(tenantId, asOf, null);
    }

    /**
     * SAH-34: the round of a caller's scope. {@code visibleAdmissionIds} are the admissions the caller may reach, or null
     * for every admitted patient. The rows of other admissions are dropped before any count, so the counts, the rows and the
     * change token of a scoped caller agree and hold nothing of another ward.
     */
    MedicineRound.Result read(UUID tenantId, Instant asOf, Set<UUID> visibleAdmissionIds) {
        List<ActiveOrder> active = repository.findActiveOfAdmitted(tenantId);
        if (visibleAdmissionIds != null) {
            List<ActiveOrder> inScope = new ArrayList<>();
            for (ActiveOrder order : active) {
                if (visibleAdmissionIds.contains(order.admissionId())) {
                    inScope.add(order);
                }
            }
            active = inScope;
        }
        if (active.isEmpty()) {
            return MedicineRound.empty();
        }
        Set<UUID> allAdmissions = new LinkedHashSet<>();
        for (ActiveOrder o : active) {
            allAdmissions.add(o.admissionId());
        }
        Map<UUID, OrderTimeline> timelineOf = timelines.findTimelines(tenantId, allAdmissions);
        Map<UUID, EffectiveNursingSettings> settingsOf = settingsReader.effectiveForAll(tenantId, allAdmissions);
        Set<UUID> admissionIds = new LinkedHashSet<>();
        List<RoundOrder> orders = new ArrayList<>();
        for (ActiveOrder o : active) {
            Optional<Drug> drug = catalogue.catalogue().find(o.drugKey());
            if (drug.isEmpty()) {
                skipped(tenantId, o, "DRUG_NOT_IN_CATALOGUE");
                continue;
            }
            OrderTimeline timeline = timelineOf.get(o.orderId());
            if (timeline == null) {
                skipped(tenantId, o, "TIMELINE_MISSING");
                continue;
            }
            admissionIds.add(o.admissionId());
            orders.add(new RoundOrder(o.orderId(), o.admissionId(), o.patientId(), o.bedCode(), drug.get().name(),
                    drug.get().highAlert(), OrderStatus.valueOf(o.status()), timeline,
                    settingsOf.get(o.admissionId())));
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
    private void skipped(UUID tenantId, ActiveOrder order, String cause) {
        if (warnings.shouldWarn(tenantId, order.orderId())) {
            log.warn("medicine-round order-skipped tenantId={} orderId={} cause={}", tenantId, order.orderId(), cause);
        }
    }
}
