package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.DoseRepository.GivenAt;
import com.sahaj.ipd.application.DoseRepository.MarHead;
import com.sahaj.ipd.application.DoseRepository.OpenNotice;
import com.sahaj.ipd.application.DoseViews.Viewer;
import com.sahaj.ipd.application.MarViews.Counters;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MarViews.HoldView;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MarViews.MarOrderView;
import com.sahaj.ipd.application.MarViews.NoticeCountView;
import com.sahaj.ipd.application.MarViews.OrderDetailsView;
import com.sahaj.ipd.application.MarViews.PrnView;
import com.sahaj.ipd.application.MarViews.SecondNurseOption;
import com.sahaj.ipd.application.MarViews.SlotView;
import com.sahaj.ipd.application.MarViews.Windows;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.SafetyRows.ActorNames;
import com.sahaj.ipd.domain.DoseOutcome;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.DoseSlots.LiveDose;
import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.DoseSlots.State;
import com.sahaj.ipd.domain.DoseUnit;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.Frequency;
import com.sahaj.ipd.domain.InErrorReason;
import com.sahaj.ipd.domain.NoticeClock;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.ipd.domain.PrnRules;
import com.sahaj.ipd.domain.RouteKey;
import com.sahaj.ipd.domain.ScanOverrideReason;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Verification;
import com.sahaj.ipd.domain.order.OrderHoldInterval;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-32 and SAH-76 builds the MAR (the tab "Medicines (MAR)") and the tab count from rows already read (ADR-011 D2, D3,
 * D12; ADR-027 D2). Every slot state comes from {@link DoseSlots} against the one {@code asOf} it is given, which is the
 * database clock, with the windows of the ward profile or the clinic. The MAR lists the orders PENDING, QUERIED, ACTIVE,
 * ON_HOLD and those STOPPED or REJECTED today, in the fold order of the contract. Staff names are looked up in one batch
 * per answer. Everything it builds is shown on screen only and never logged.
 */
@Component
class MarAssembler {

    private final DoseRepository doses;
    private final MedicineAssembler medicines;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final OrderTimelineRepository timelines;
    private final NursingSettingsReader settingsReader;
    private final DrugCodeRepository codes;

    MarAssembler(DoseRepository doses, MedicineAssembler medicines, ActorDirectoryPort actors, TeamMemberPort team,
                 OrderTimelineRepository timelines, NursingSettingsReader settingsReader, DrugCodeRepository codes) {
        this.doses = doses;
        this.medicines = medicines;
        this.actors = actors;
        this.team = team;
        this.timelines = timelines;
        this.settingsReader = settingsReader;
        this.codes = codes;
    }

    /** Who is looking: the flags of the dose views depend on it. */
    Viewer viewer(UUID actor, String role, String memberRole, ModuleEntitlement ent, boolean admitted, Instant asOf) {
        boolean writable = "trial".equals(ent.ipdState()) || "on".equals(ent.ipdState());
        boolean roleOk = DoseRules.GIVE_ROLES.contains(role) && DoseRules.GIVE_ROLES.contains(memberRole);
        return new Viewer(actor, roleOk, admitted && writable, asOf);
    }

    /**
     * The MAR. {@code canGive} needs the role in the token and the role of the membership both NURSE or OWNER, the
     * admission ADMITTED and IPD Trial or On. The list "Checked by" is the other active nurses of the clinic and is
     * empty when {@code canGive} is false.
     *
     * @param callerMembershipId the signed-in person's own membership (never offered as the second nurse)
     */
    Mar mar(UUID tenantId, UUID actor, UUID callerMembershipId, String role, String memberRole, ModuleEntitlement ent,
            AdmissionRow admission, PatientClinical patient, Instant asOf) {
        boolean admitted = "ADMITTED".equals(admission.status());
        String state = ent.ipdState();
        boolean writableState = "trial".equals(state) || "on".equals(state);
        boolean roleCanGive = DoseRules.GIVE_ROLES.contains(role) && DoseRules.GIVE_ROLES.contains(memberRole);
        boolean canGive = roleCanGive && admitted && writableState;
        String lockReason = canGive ? null : !roleCanGive ? "ROLE" : !writableState ? "IPD_READONLY" : "NOT_ADMITTED";
        Viewer viewer = viewer(actor, role, memberRole, ent, admitted, asOf);

        EffectiveNursingSettings settings = settingsReader.effectiveFor(tenantId, admission.id());
        Instant dayStart = DoseSlots.dayStart(asOf);
        Instant dayEnd = DoseSlots.dayEnd(asOf);
        List<MarHead> heads = doses.findMarHeads(tenantId, admission.id(), dayStart, dayEnd);
        Map<UUID, OrderTimeline> timelineOf = heads.isEmpty() ? Map.of()
                : timelines.findTimelines(tenantId, List.of(admission.id()));
        List<DoseRow> today = heads.isEmpty() ? List.of() : doses.findBetween(tenantId, admission.id(), dayStart, dayEnd);
        Map<UUID, Instant> lastGiven = heads.isEmpty() ? Map.of() : doses.findLastGiven(tenantId, admission.id());
        List<GivenAt> givenSince = heads.isEmpty() ? List.of()
                : doses.findGivenSince(tenantId, admission.id(), asOf.minus(Duration.ofHours(24)));
        List<OpenNotice> notices = doses.findOpenNotices(tenantId, admission.id());
        Set<UUID> noticeOrders = new HashSet<>();
        for (OpenNotice n : notices) {
            noticeOrders.add(n.orderId());
        }
        Set<String> drugKeys = new HashSet<>();
        for (MarHead h : heads) {
            drugKeys.add(h.drugKey());
        }
        Set<String> keysWithCodes = codes.drugKeysWithCodes(drugKeys);

        Map<UUID, List<DoseRow>> byOrder = new HashMap<>();
        for (DoseRow d : today) {
            byOrder.computeIfAbsent(d.orderId(), k -> new ArrayList<>()).add(d);
        }
        ActorNames names = ActorNames.forIds(actors, tenantId, staffOf(heads, timelineOf, today));

        List<Built> built = new ArrayList<>();
        int late = 0;
        int dueNow = 0;
        for (MarHead head : heads) {
            OrderTimeline timeline = timelineOf.get(head.orderId());
            if (timeline == null) {
                throw new IllegalStateException("An order has no timeline");
            }
            Drug drug = medicines.drug(head.drugKey());
            OrderStatus status = OrderStatus.valueOf(head.status());
            List<DoseRow> mine = byOrder.getOrDefault(head.orderId(), List.of());
            List<Slot> slots = DoseSlots.slots(timeline, head.stoppedBy(), liveScheduled(mine), admitted, settings, asOf);
            List<SlotView> slotViews = new ArrayList<>();
            int worstLate = 0;
            boolean actionable = false;
            boolean later = false;
            for (Slot slot : slots) {
                boolean givable = DoseSlots.givable(slot, status);
                if (givable) {
                    actionable = true;
                    if (slot.state() == State.LATE) {
                        late++;
                        worstLate = Math.max(worstLate, slot.lateMinutes() == null ? 0 : slot.lateMinutes());
                    } else {
                        dueNow++;
                    }
                }
                later = later || (slot.current() && slot.state() == State.LATER);
                slotViews.add(slotView(slot, givable && canGive, mine, names, viewer));
            }
            MedicineOrderVersion current = timeline.current();
            boolean prn = current.kind() == Kind.PRN;
            PrnView prnView = prn ? prn(current, status, canGive, givenOf(head.orderId(), givenSince), lastGiven.get(head.orderId()),
                    asOf) : null;
            built.add(new Built(order(head, drug, timeline, current, status, slotViews, prnView, names,
                    keysWithCodes.contains(head.drugKey()), lastGiven.get(head.orderId()),
                    !noticeOrders.contains(head.orderId()), actionable, later, prn), prn, actionable, later, worstLate));
        }
        int given = 0;
        for (DoseRow d : today) {
            if (d.live() && d.given()) {
                given++;
            }
        }
        List<MarOrderView> ordered = fold(built);
        int folded = 0;
        for (MarOrderView v : ordered) {
            folded += v.folded() ? 1 : 0;
        }
        return new Mar(admission.id(), admission.status(), state, asOf, medicines.patient(patient, admission.bedCode()),
                late + dueNow, canGive, lockReason, List.copyOf(ordered),
                canGive ? secondNurses(tenantId, callerMembershipId) : List.of(), notGivenReasons(),
                new Counters(late, dueNow, given), folded,
                new Windows(settings.windowBeforeMin(), settings.windowAfterMin(), settings.statWindowMin(),
                        settings.undoSeconds()),
                overrideReasons(), inErrorReasons(), noticeCount(notices, settings, asOf));
    }

    /**
     * The count on the tab: DUE and LATE slots of the current versions of the orders that may be given (ACTIVE, and the
     * STAT slot of a PENDING order) of an ADMITTED admission (0 otherwise; the MAR's own {@code doseCount} is the same
     * rule). A fixed number of statements.
     */
    int doseCount(UUID tenantId, UUID admissionId, boolean admitted, Instant asOf) {
        if (!admitted) {
            return 0;
        }
        Instant dayStart = DoseSlots.dayStart(asOf);
        Instant dayEnd = DoseSlots.dayEnd(asOf);
        List<MarHead> heads = new ArrayList<>();
        for (MarHead h : doses.findMarHeads(tenantId, admissionId, dayStart, dayEnd)) {
            if (OrderStatus.ACTIVE.name().equals(h.status()) || OrderStatus.PENDING.name().equals(h.status())) {
                heads.add(h);
            }
        }
        if (heads.isEmpty()) {
            return 0;
        }
        Map<UUID, OrderTimeline> timelineOf = timelines.findTimelines(tenantId, List.of(admissionId));
        EffectiveNursingSettings settings = settingsReader.effectiveFor(tenantId, admissionId);
        Map<UUID, List<DoseRow>> byOrder = new HashMap<>();
        for (DoseRow d : doses.findBetween(tenantId, admissionId, dayStart, dayEnd)) {
            byOrder.computeIfAbsent(d.orderId(), k -> new ArrayList<>()).add(d);
        }
        int count = 0;
        for (MarHead h : heads) {
            OrderTimeline timeline = timelineOf.get(h.orderId());
            if (timeline == null) {
                throw new IllegalStateException("An order has no timeline");
            }
            OrderStatus status = OrderStatus.valueOf(h.status());
            for (Slot slot : DoseSlots.slots(timeline, h.stoppedBy(), liveScheduled(byOrder.getOrDefault(h.orderId(),
                    List.of())), true, settings, asOf)) {
                if (DoseSlots.givable(slot, status)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The badge of the Orders tab for one admission (the Nursing sheet, the MAR): open notices, STAT, overdue. */
    NoticeCountView noticeCount(UUID tenantId, UUID admissionId, EffectiveNursingSettings settings, Instant asOf) {
        return noticeCount(doses.findOpenNotices(tenantId, admissionId), settings, asOf);
    }

    /** One recorded dose on screen, with the names of the people it names and the action flags for {@code viewer}. */
    DoseView view(UUID tenantId, DoseRow dose, Viewer viewer) {
        Set<UUID> staff = new HashSet<>();
        DoseViews.collectStaff(dose, staff);
        return DoseViews.of(dose, ActorNames.forIds(actors, tenantId, staff), viewer);
    }

    // ---- building blocks ----

    /** An order view with the data the fold needs. */
    private record Built(MarOrderView view, boolean prn, boolean actionable, boolean later, int worstLate) {
    }

    /**
     * The live (not corrected) scheduled doses: the one input of the slot rule. A corrected dose (undone or recorded in
     * error) and a PRN dose make no slot recorded. Shared with {@link ExecutionSlots} so the EMR line and the MAR agree.
     */
    static List<LiveDose> liveScheduled(List<DoseRow> rows) {
        List<LiveDose> live = new ArrayList<>();
        for (DoseRow d : rows) {
            if (d.live() && !"PRN".equals(d.doseKind())) {
                live.add(new LiveDose(d.id(), d.slotAt(), DoseOutcome.ofKey(d.outcome())
                        .orElseThrow(() -> new IllegalStateException("A dose has an unknown outcome"))));
            }
        }
        return live;
    }

    private SlotView slotView(Slot slot, boolean canGive, List<DoseRow> mine, ActorNames names, Viewer viewer) {
        DoseView dose = null;
        List<DoseView> history = new ArrayList<>();
        for (DoseRow d : mine) {
            if (!d.slotAt().equals(slot.slotAt())) {
                continue;
            }
            if (d.live()) {
                dose = DoseViews.of(d, names, viewer);
            } else if (DoseRepository.KIND_IN_ERROR.equals(d.correction().kind())) {
                history.add(DoseViews.of(d, names, viewer));
            }
        }
        String by = slot.stateBy() == null ? null : names.of(slot.stateBy());
        return new SlotView(slot.slotAt(), slot.state().name(), slot.state() == State.LATE ? slot.lateHours() : null,
                canGive, dose, slot.versionNo(), slot.state() == State.LATE ? slot.lateMinutes() : null,
                slot.stateBy() == null && slot.stateAt() == null ? null : by, slot.stateAt(), List.copyOf(history),
                slot.beforeVerification());
    }

    private MarOrderView order(MarHead head, Drug drug, OrderTimeline timeline, MedicineOrderVersion current,
                               OrderStatus status, List<SlotView> slots, PrnView prn, ActorNames names,
                               boolean hasCode, Instant lastGivenAt, boolean acknowledged, boolean actionable,
                               boolean later, boolean isPrn) {
        String frequencyKey = current.kind() == Kind.SCHEDULED ? current.frequencyKey()
                : current.kind() == Kind.PRN ? Frequency.PRN.key() : null;
        String frequencyLabel = frequencyLabel(current);
        HoldView hold = null;
        if (status == OrderStatus.ON_HOLD) {
            for (OrderHoldInterval h : timeline.holds()) {
                if (h.isOpen()) {
                    hold = new HoldView(h.heldAt(), names.of(h.heldBy()), null);
                }
            }
        }
        String route = RouteKey.ofKey(current.routeKey()).map(RouteKey::label).orElse(drug.route());
        boolean folded = !isPrn && !actionable && !later;
        return new MarOrderView(head.orderId(), drug.key(), drug.name(), route, drug.highAlert(), frequencyKey,
                frequencyLabel, names.of(head.orderedBy()), head.status(), List.copyOf(slots), current.versionNo(),
                current.versionNo() > 1, details(current, frequencyLabel), hold, prn, hasCode, lastGivenAt,
                acknowledged, folded);
    }

    private static String frequencyLabel(MedicineOrderVersion version) {
        if (version.kind() == Kind.ONCE) {
            return "STAT";
        }
        if (version.kind() == Kind.PRN) {
            return Frequency.PRN.label();
        }
        return Frequency.ofKey(version.frequencyKey()).map(Frequency::label)
                .orElseThrow(() -> new IllegalStateException("An order has an unknown frequency"));
    }

    static OrderDetailsView details(MedicineOrderVersion v, String frequencyLabel) {
        Integer days = null;
        if (v.endAt() != null) {
            days = (int) Math.max(1, (long) Math.ceil(Duration.between(v.startAt(), v.endAt()).toMinutes() / 1440.0));
        }
        String routeLabel = RouteKey.ofKey(v.routeKey()).map(RouteKey::label).orElse(v.routeKey());
        String unit = v.doseUnit() == null ? null : DoseUnit.ofKey(v.doseUnit()).map(DoseUnit::key).orElse(null);
        Boolean checked = v.verification() == Verification.NOT_REQUIRED ? Boolean.FALSE
                : v.isVerified() ? Boolean.TRUE : null;
        String frequencyKey = v.kind() == Kind.SCHEDULED ? v.frequencyKey()
                : v.kind() == Kind.PRN ? Frequency.PRN.key() : null;
        OrderPriority priority = v.priority();
        return new OrderDetailsView(v.kind().name(), v.doseAmount(), unit, v.routeKey(), routeLabel, frequencyKey,
                frequencyLabel, v.startAt(), v.endAt(), days, v.indication(), priority.name(), v.minIntervalMin(),
                v.maxPer24h(), checked);
    }

    private static List<Instant> givenOf(UUID orderId, List<GivenAt> since) {
        List<Instant> times = new ArrayList<>();
        for (GivenAt g : since) {
            if (g.orderId().equals(orderId)) {
                times.add(g.recordedAt());
            }
        }
        return times;
    }

    private static PrnView prn(MedicineOrderVersion version, OrderStatus status, boolean canGive, List<Instant> given,
                               Instant lastGivenAt, Instant asOf) {
        PrnRules.Decision decision = PrnRules.decide(given, version.minIntervalMin(), version.maxPer24h(), asOf);
        String lock = null;
        if (status == OrderStatus.STOPPED || status == OrderStatus.REJECTED) {
            lock = "STOPPED";
        } else if (status == OrderStatus.ON_HOLD) {
            lock = "ON_HOLD";
        } else if (status == OrderStatus.PENDING || status == OrderStatus.QUERIED) {
            lock = "NOT_VERIFIED";
        } else if (version.startAt() != null && asOf.isBefore(version.startAt())) {
            lock = "NOT_STARTED";
        } else if (version.endAt() != null && !asOf.isBefore(version.endAt())) {
            lock = "ENDED";
        } else if (decision.lock() != null) {
            lock = decision.lock().name();
        }
        boolean allowed = status == OrderStatus.ACTIVE && decision.allowed() && !"NOT_STARTED".equals(lock)
                && !"ENDED".equals(lock);
        return new PrnView(version.indication(), version.minIntervalMin(), version.maxPer24h(), decision.givenLast24h(),
                lastGivenAt, decision.nextAllowedAt(), lock, canGive && allowed);
    }

    /**
     * The fold order of the contract: blocks with a DUE or LATE slot (most late first), then PRN lines, then blocks with
     * a LATER slot, then the folded ones; chart order inside each group.
     */
    private static List<MarOrderView> fold(List<Built> built) {
        List<Built> sorted = new ArrayList<>(built);
        sorted.sort(Comparator.comparingInt(MarAssembler::group).thenComparing(b -> -b.worstLate()));
        List<MarOrderView> out = new ArrayList<>();
        for (Built b : sorted) {
            out.add(b.view());
        }
        return out;
    }

    private static int group(Built b) {
        if (b.prn()) {
            return 1;
        }
        if (b.actionable()) {
            return 0;
        }
        return b.later() ? 2 : 3;
    }

    private static Set<UUID> staffOf(List<MarHead> heads, Map<UUID, OrderTimeline> timelineOf, List<DoseRow> today) {
        Set<UUID> staff = new HashSet<>();
        for (MarHead h : heads) {
            staff.add(h.orderedBy());
            if (h.stoppedBy() != null) {
                staff.add(h.stoppedBy());
            }
            OrderTimeline timeline = timelineOf.get(h.orderId());
            if (timeline != null) {
                for (MedicineOrderVersion v : timeline.versions()) {
                    staff.add(v.createdBy());
                }
                for (OrderHoldInterval hold : timeline.holds()) {
                    staff.add(hold.heldBy());
                }
            }
        }
        for (DoseRow d : today) {
            DoseViews.collectStaff(d, staff);
        }
        return staff;
    }

    private static NoticeCountView noticeCount(List<OpenNotice> notices, EffectiveNursingSettings settings,
                                               Instant asOf) {
        int stat = 0;
        int overdue = 0;
        for (OpenNotice n : notices) {
            boolean isStat = "STAT".equals(n.priority());
            stat += isStat ? 1 : 0;
            overdue += NoticeClock.overdue(n.createdAt(), isStat, settings.unackMinutes(), settings.unackStatMinutes(),
                    asOf) ? 1 : 0;
        }
        return new NoticeCountView(notices.size(), stat, overdue);
    }

    static List<KeyLabel> notGivenReasons() {
        List<KeyLabel> reasons = new ArrayList<>();
        for (NotGivenReason r : NotGivenReason.values()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return List.copyOf(reasons);
    }

    static List<KeyLabel> overrideReasons() {
        List<KeyLabel> reasons = new ArrayList<>();
        for (ScanOverrideReason r : ScanOverrideReason.inputReasons()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return List.copyOf(reasons);
    }

    static List<KeyLabel> inErrorReasons() {
        List<KeyLabel> reasons = new ArrayList<>();
        for (InErrorReason r : InErrorReason.values()) {
            reasons.add(new KeyLabel(r.key(), r.label()));
        }
        return List.copyOf(reasons);
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
