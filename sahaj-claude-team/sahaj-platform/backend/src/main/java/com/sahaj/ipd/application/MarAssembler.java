package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MarViews.MarOrderView;
import com.sahaj.ipd.application.MarViews.SecondNurseOption;
import com.sahaj.ipd.application.MarViews.SlotView;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.DoseOutcome;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.Stage;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-32 builds the MAR (the tab "Medicines (MAR)") and the tab count from rows already read (ADR-011 D2, D3, D12).
 * Every slot state comes from {@link DoseSlots} against the one {@code asOf} it is given, which is the database
 * clock. The MAR lists the orders that are PENDING or ACTIVE only. Staff names are looked up in one batch per answer.
 * Everything it builds is shown on screen only and never logged.
 */
@Component
class MarAssembler {

    private final MedicineOrderRepository orders;
    private final DoseRepository doses;
    private final MedicineAssembler medicines;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;

    MarAssembler(MedicineOrderRepository orders, DoseRepository doses, MedicineAssembler medicines,
                 ActorDirectoryPort actors, TeamMemberPort team) {
        this.orders = orders;
        this.doses = doses;
        this.medicines = medicines;
        this.actors = actors;
        this.team = team;
    }

    /**
     * The MAR. {@code canGive} needs the role in the token and the role of the membership both NURSE or OWNER, the
     * admission ADMITTED and IPD Trial or On. The list "Checked by" is the other active nurses of the clinic and is
     * empty when {@code canGive} is false.
     *
     * @param callerMembershipId the signed-in person's own membership (never offered as the second nurse)
     */
    Mar mar(UUID tenantId, UUID callerMembershipId, String role, String memberRole, ModuleEntitlement ent,
            AdmissionRow admission, PatientClinical patient, Instant asOf) {
        boolean admitted = "ADMITTED".equals(admission.status());
        String state = ent.ipdState();
        boolean writableState = "trial".equals(state) || "on".equals(state);
        boolean roleCanGive = DoseRules.GIVE_ROLES.contains(role) && DoseRules.GIVE_ROLES.contains(memberRole);
        boolean canGive = roleCanGive && admitted && writableState;
        String lockReason = canGive ? null : !roleCanGive ? "ROLE" : !writableState ? "IPD_READONLY" : "NOT_ADMITTED";

        // Only PENDING and ACTIVE orders are read (review R7); chart order within the admission.
        List<OrderRow> live = orders.findLiveByAdmissions(tenantId, List.of(admission.id()));
        List<DoseRow> today = live.isEmpty() ? List.of()
                : doses.findBetween(tenantId, admission.id(), DoseSlots.dayStart(asOf), DoseSlots.dayEnd(asOf));
        Map<UUID, Map<Instant, DoseRow>> byOrder = byOrder(today);

        Set<UUID> staff = new HashSet<>();
        for (OrderRow o : live) {
            staff.add(o.orderedBy());
        }
        for (DoseRow d : today) {
            DoseViews.collectStaff(d, staff);
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staff);

        int count = 0;
        List<MarOrderView> views = new ArrayList<>();
        for (OrderRow o : live) {
            Drug drug = medicines.drug(o.drugKey());
            Frequency frequency = Frequency.ofKey(o.frequencyKey())
                    .orElseThrow(() -> new IllegalStateException("An order has an unknown frequency"));
            Map<Instant, DoseRow> recorded = byOrder.getOrDefault(o.id(), Map.of());
            boolean active = MedicineOrderRepository.ACTIVE.equals(o.status());
            List<Slot> slots = slotsOf(o, frequency, recorded, admitted, asOf);
            List<SlotView> slotViews = new ArrayList<>();
            for (Slot slot : slots) {
                DoseRow dose = recorded.get(slot.slotAt());
                slotViews.add(new SlotView(slot.slotAt(), slot.state().name(), slot.lateHours(),
                        slot.state().actionable() && active && canGive, dose == null ? null : DoseViews.of(dose, names)));
            }
            if (active) {
                count += DoseSlots.dueCount(slots);
            }
            views.add(new MarOrderView(o.id(), drug.key(), drug.name(), drug.route(), drug.highAlert(),
                    frequency.key(), frequency.label(), names.of(o.orderedBy()), o.status(), List.copyOf(slotViews)));
        }

        List<KeyLabel> reasons = new ArrayList<>();
        for (NotGivenReason r : NotGivenReason.values()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return new Mar(admission.id(), admission.status(), state, asOf, medicines.patient(patient, admission.bedCode()),
                count, canGive, lockReason, List.copyOf(views), canGive ? secondNurses(tenantId, callerMembershipId)
                        : List.of(), List.copyOf(reasons));
    }

    /**
     * The count on the tab: DUE and LATE slots of the ACTIVE orders of an ADMITTED admission (0 otherwise; the MAR's
     * own {@code doseCount} is the same rule). Two queries at most.
     */
    int doseCount(UUID tenantId, UUID admissionId, boolean admitted, Instant asOf) {
        if (!admitted) {
            return 0;
        }
        List<OrderRow> active = new ArrayList<>();
        for (OrderRow o : orders.findLiveByAdmissions(tenantId, List.of(admissionId))) {
            if (MedicineOrderRepository.ACTIVE.equals(o.status())) {
                active.add(o);
            }
        }
        if (active.isEmpty()) {
            return 0;
        }
        Map<UUID, Map<Instant, DoseRow>> byOrder = byOrder(
                doses.findBetween(tenantId, admissionId, DoseSlots.dayStart(asOf), DoseSlots.dayEnd(asOf)));
        int count = 0;
        for (OrderRow o : active) {
            Frequency frequency = Frequency.ofKey(o.frequencyKey())
                    .orElseThrow(() -> new IllegalStateException("An order has an unknown frequency"));
            count += DoseSlots.dueCount(slotsOf(o, frequency, byOrder.getOrDefault(o.id(), Map.of()), true, asOf));
        }
        return count;
    }

    /** One recorded dose on screen, with the names of the people it names. */
    DoseView view(UUID tenantId, DoseRow dose) {
        Set<UUID> staff = new HashSet<>();
        DoseViews.collectStaff(dose, staff);
        return DoseViews.of(dose, ActorNames.forIds(actors, tenantId, staff));
    }

    private static List<Slot> slotsOf(OrderRow order, Frequency frequency, Map<Instant, DoseRow> recorded,
                                      boolean admitted, Instant asOf) {
        Map<Instant, DoseOutcome> outcomes = new HashMap<>();
        for (Map.Entry<Instant, DoseRow> e : recorded.entrySet()) {
            outcomes.put(e.getKey(), DoseOutcome.ofKey(e.getValue().outcome())
                    .orElseThrow(() -> new IllegalStateException("A dose has an unknown outcome")));
        }
        Stage stage = MedicineOrderRepository.PENDING.equals(order.status()) ? Stage.PENDING : Stage.ACTIVE;
        return DoseSlots.slots(frequency, stage, order.verifiedAt(), outcomes, admitted, asOf);
    }

    private static Map<UUID, Map<Instant, DoseRow>> byOrder(List<DoseRow> rows) {
        Map<UUID, Map<Instant, DoseRow>> byOrder = new HashMap<>();
        for (DoseRow d : rows) {
            byOrder.computeIfAbsent(d.orderId(), k -> new HashMap<>()).put(d.slotAt(), d);
        }
        return byOrder;
    }

    /** The active NURSE members of the clinic, the signed-in person excluded, in the team's order. */
    private List<SecondNurseOption> secondNurses(UUID tenantId, UUID callerMembershipId) {
        List<SecondNurseOption> options = new ArrayList<>();
        for (TeamMember m : team.findActiveMembers(tenantId)) {
            if (m.active() && "NURSE".equals(m.role()) && !m.membershipId().equals(callerMembershipId)) {
                options.add(new SecondNurseOption(m.membershipId(), m.name()));
            }
        }
        return List.copyOf(options);
    }
}
