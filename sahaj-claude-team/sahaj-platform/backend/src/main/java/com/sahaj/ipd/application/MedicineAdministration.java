package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DoseRepository.DoseRow;
import com.sahaj.ipd.application.DoseRepository.GivenAt;
import com.sahaj.ipd.application.DoseRepository.NewDose;
import com.sahaj.ipd.application.DoseRepository.Stored;
import com.sahaj.ipd.application.DoseRules.Body;
import com.sahaj.ipd.application.DoseRules.ScanOverride;
import com.sahaj.ipd.application.DoseViews.Viewer;
import com.sahaj.ipd.application.MarViews.DoseInput;
import com.sahaj.ipd.application.MarViews.DoseRecorded;
import com.sahaj.ipd.application.MarViews.DoseView;
import com.sahaj.ipd.application.MarViews.Mar;
import com.sahaj.ipd.application.MedicineOrderRepository.OrderRow;
import com.sahaj.ipd.domain.DoseOutcome;
import com.sahaj.ipd.domain.DoseSlots;
import com.sahaj.ipd.domain.DoseSlots.LiveDose;
import com.sahaj.ipd.domain.DoseSlots.Slot;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import com.sahaj.ipd.domain.MedicineCodeCheck;
import com.sahaj.ipd.domain.NotGivenReason;
import com.sahaj.ipd.domain.PrnRules;
import com.sahaj.ipd.domain.WristbandCheck;
import com.sahaj.ipd.domain.order.MedicineOrderVersion;
import com.sahaj.ipd.domain.order.MedicineOrderVersion.Kind;
import com.sahaj.ipd.domain.order.OrderPriority;
import com.sahaj.ipd.domain.order.OrderRef;
import com.sahaj.ipd.domain.order.OrderStatus;
import com.sahaj.ipd.domain.order.OrderTimeline;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidFieldsException;
import com.sahaj.platform.api.InvalidFieldsException.FieldMessage;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-32 and SAH-76 the two IPD transactions of giving a dose or recording that it was not given (ADR-011 D4 to D9,
 * ADR-027 D7). The non-transactional {@link DoseService} calls them with the second nurse's PIN check between them, the
 * shape of verify (ADR-009 B1): the PIN is checked outside any transaction and never with an admission lock held.
 *
 * <ul>
 *   <li>{@link #prepare}: every check, no lock. Read-write only so that it can write the deny entry of a wristband or
 *       medicine mismatch, the audit row of a refusal, and the record-access row of a replay; every other refusal writes
 *       nothing.</li>
 *   <li>{@link #recordLocked}: the role, state and membership again, then the bare admission row
 *       {@code for no key update} (the only lock), the database clock read after the lock, every check again, the
 *       dose, the acknowledgement of the order's open notices, the review item of a STAT dose before the pharmacist
 *       check, one clinical event and one audit row.</li>
 * </ul>
 *
 * <p>A refusal by the unique index, a database check, the guard trigger or a foreign key on the insert is a code
 * fault: it is never swallowed, the whole transaction rolls back and the caller sees a generic 500. The scanned text,
 * the medicine code, the notes, the PIN, names and allergy text are never logged or audited; the audit rows hold ids and
 * fixed keys.
 */
@Service
public class MedicineAdministration {

    static final String ENTITY_DOSE = "IPD_DOSE";
    static final String ENTITY_ORDER = "IPD_MEDICINE_ORDER";
    static final String NURSE_ROLE = "NURSE";
    private static final Logger log = LoggerFactory.getLogger(MedicineAdministration.class);

    private final MedicineOrderRepository orders;
    private final DoseRepository doses;
    private final AdmissionRepository admissions;
    private final ClinicalEventRepository events;
    private final PatientClinicalPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final TeamMemberPort team;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final MedicineAssembler medicines;
    private final MarAssembler marAssembler;
    private final ScopeGuard guard;
    private final OrderTimelineRepository timelines;
    private final NursingSettingsReader settingsReader;
    private final DrugCodeRepository drugCodes;
    private final OrderNotices notices;
    private final PharmacistReviews reviews;
    private final MedicineCatalogue catalogue;

    MedicineAdministration(MedicineOrderRepository orders, DoseRepository doses, AdmissionRepository admissions,
                           ClinicalEventRepository events, PatientClinicalPort patients,
                           PatientAccessPort patientAccess, ActorDirectoryPort actors, TeamMemberPort team,
                           IpdAudit audit, IpdGate gate, MedicineAssembler medicines, MarAssembler marAssembler,
                           ScopeGuard guard, OrderTimelineRepository timelines, NursingSettingsReader settingsReader,
                           DrugCodeRepository drugCodes, OrderNotices notices, PharmacistReviews reviews,
                           MedicineCatalogue catalogue) {
        this.guard = guard;
        this.orders = orders;
        this.doses = doses;
        this.admissions = admissions;
        this.events = events;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.team = team;
        this.audit = audit;
        this.gate = gate;
        this.medicines = medicines;
        this.marAssembler = marAssembler;
        this.timelines = timelines;
        this.settingsReader = settingsReader;
        this.drugCodes = drugCodes;
        this.notices = notices;
        this.reviews = reviews;
        this.catalogue = catalogue;
    }

    // ---- results ----

    /** What the first transaction decided. */
    public enum PrepareKind {
        /** Every check passed; the dose can be recorded (after the PIN check, for a high-alert GIVEN). */
        READY,
        /** A repeat of a recorded dose: the answer is {@link Prepared#replay()}, 200. */
        REPLAY,
        /** A refusal whose deny entry or audit row was written and committed; the caller answers 409. */
        REFUSED
    }

    /** A 409 answer that is thrown by the caller after the transaction that wrote its entries has committed. */
    public record Refusal(String problem, String title, String detail) {
    }

    /**
     * The first transaction's answer. {@code pin} is the second nurse's PIN to check and {@code secondNurseId} the
     * membership to check it for, both null unless this is a high-alert GIVEN. The PIN is hidden from toString.
     */
    public record Prepared(PrepareKind kind, DoseRecorded replay, UUID secondNurseId, String pin, Refusal refusal) {

        public boolean pinRequired() {
            return pin != null;
        }

        @Override
        public String toString() {
            return "Prepared[kind=" + kind + "]";
        }
    }

    /** What the locked transaction decided: a new dose (201), a replay (200) or a refusal (409, entries committed). */
    public enum RecordKind { CREATED, REPLAYED, REFUSED }

    public record Recorded(RecordKind kind, DoseRecorded answer, Refusal refusal) {

        @Override
        public String toString() {
            return "Recorded[kind=" + kind + "]";
        }
    }

    // ---- the first transaction ----

    /**
     * Check order of the contract: token role (403), module Off (403), Read-only (409), body format (400), the role
     * read again from the membership (403), order and patient (404), then {@link #decide}. Takes no lock.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Prepared prepare(UUID tenantId, UUID actor, String role, UUID orderId, DoseInput input) {
        Caller caller = caller(tenantId, actor, role);
        Body body = DoseRules.body(input);
        caller = member(tenantId, actor, caller);
        OrderRow order = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        AdmissionRow admission = admissions.find(tenantId, order.admissionId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        PatientClinical patient = patient(tenantId, admission);
        // SAH-34 (step 3): after the 404 checks, before any state check, PIN check or entry. A refused request checks no PIN.
        guard.requireAdmission(tenantId, actor, role, caller.memberRole(), admission.id(), "dose-record");
        Instant asOf = orders.databaseNow();

        Decision decision = decide(tenantId, actor, caller, order, admission, patient, body, input, asOf, false);
        if (decision.replay != null) {
            return new Prepared(PrepareKind.REPLAY, replayAnswer(tenantId, actor, role, caller, admission, patient,
                    decision.replay), null, null, null);
        }
        if (decision.refusal != null) {
            return new Prepared(PrepareKind.REFUSED, null, null, null, decision.refusal);
        }
        boolean pin = decision.highAlertGiven;
        return new Prepared(PrepareKind.READY, null, pin ? decision.secondNurse.membershipId() : null,
                pin ? input.secondNursePin() : null, null);
    }

    // ---- the locked transaction ----

    /**
     * The second transaction, after the second nurse's PIN was accepted (or none was needed). The request is the
     * original one without the PIN. {@code colleagueUserId} is the second nurse's user id from the PIN check, null
     * unless this is a high-alert GIVEN.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Recorded recordLocked(UUID tenantId, UUID actor, String role, UUID orderId, DoseInput input,
                                 UUID colleagueUserId) {
        Caller caller = caller(tenantId, actor, role);
        Body body = DoseRules.body(input);
        caller = member(tenantId, actor, caller);

        OrderRow first = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        AdmissionRow admission = admissions.findForNoKeyUpdate(tenantId, first.admissionId())
                .orElseThrow(() -> new NotFoundException("Order not found"));
        OrderRow order = orders.findById(tenantId, orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        PatientClinical patient = patient(tenantId, admission);
        // SAH-34 (step 5): again under the admission lock, on locked data and the database clock (an expiry that raced).
        guard.requireAdmissionLocked(tenantId, actor, role, caller.memberRole(), admission.id(), "dose-record");
        // The clock is read after the lock: the due rule uses the time the dose is really recorded.
        Instant asOf = orders.databaseNow();

        Decision decision = decide(tenantId, actor, caller, order, admission, patient, body, input, asOf, true);
        if (decision.replay != null) {
            return new Recorded(RecordKind.REPLAYED, replayAnswer(tenantId, actor, role, caller, admission, patient,
                    decision.replay), null);
        }
        if (decision.refusal != null) {
            return new Recorded(RecordKind.REFUSED, null, decision.refusal);
        }
        if (decision.highAlertGiven) {
            if (colleagueUserId == null) {
                throw new IllegalStateException("A high-alert dose reached the lock without a second check");
            }
            if (colleagueUserId.equals(actor)) {
                throw new InvalidInputException("secondNurseId", DoseRules.DIFFERENT_NURSE);
            }
        }
        return new Recorded(RecordKind.CREATED, store(tenantId, actor, role, caller, order, admission, patient, body,
                input, decision, colleagueUserId), null);
    }

    // ---- the checks (the same code in both transactions) ----

    /** The outcome of the checks: exactly one of a replay, a refusal or a go-ahead with the values to store. */
    private static final class Decision {
        DoseRow replay;
        Refusal refusal;
        boolean highAlertGiven;
        Drug drug;
        TeamMember secondNurse;
        NotGivenReason reason;
        String notGivenNote;
        String scan;
        String medicineScan;
        ScanOverride override;
        MedicineOrderVersion version;
        Instant slotAt;
        int attempt = 1;
        UUID supersedes;
        boolean beforeVerification;
    }

    /**
     * ADR-027 D7 steps 2 to 7. The PIN itself is checked by the caller between the two transactions
     * ({@code pinChecked} is true under the lock, where the PIN is not available any more). A refusal that must leave a
     * trace writes its deny entry and audit row and returns, so the surrounding transaction commits them.
     */
    private Decision decide(UUID tenantId, UUID actor, Caller caller, OrderRow order, AdmissionRow admission,
                            PatientClinical patient, Body body, DoseInput input, Instant asOf, boolean pinChecked) {
        Decision decision = new Decision();
        Drug drug = medicines.drug(order.drugKey());
        decision.drug = drug;

        DoseRow repeat = doses.findByClientKey(tenantId, body.clientKey()).orElse(null);
        if (repeat != null) {
            decision.replay = checkRepeat(tenantId, actor, repeat, order, body, input);
            return decision;
        }
        if (!"ADMITTED".equals(admission.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted", DoseRules.NOT_ADMITTED);
        }
        OrderTimeline timeline = timelines.findTimelines(tenantId, List.of(admission.id())).get(order.id());
        if (timeline == null) {
            throw new IllegalStateException("An order has no timeline");
        }
        MedicineOrderVersion current = timeline.current();
        decision.version = current;
        OrderStatus status = timeline.status();

        // Step 3: the order state.
        if (status == OrderStatus.STOPPED) {
            throw new ConflictException(MedicineOrderService.STATE_PROBLEM, "Order stopped", DoseRules.STOPPED);
        }
        if (status == OrderStatus.REJECTED) {
            throw new ConflictException(MedicineOrderService.STATE_PROBLEM, "Order rejected", DoseRules.REJECTED);
        }
        if (status == OrderStatus.ON_HOLD) {
            return refuse(decision, tenantId, actor, order, admission, "order-on-hold", "Order on hold",
                    DoseRules.ON_HOLD, asOf);
        }
        if (status == OrderStatus.QUERIED) {
            return refuse(decision, tenantId, actor, order, admission, "order-not-verified", "Order not checked",
                    DoseRules.NOT_VERIFIED, asOf);
        }
        if (status == OrderStatus.PENDING) {
            boolean stat = current.kind() == Kind.ONCE && current.priority() == OrderPriority.STAT;
            if (!stat || doses.hasLiveGiven(tenantId, order.id())) {
                return refuse(decision, tenantId, actor, order, admission, "order-not-verified", "Order not checked",
                        DoseRules.NOT_VERIFIED, asOf);
            }
            decision.beforeVerification = true;
        }
        // Step 4: the version the nurse saw.
        if (body.orderVersion() != current.versionNo()) {
            return refuse(decision, tenantId, actor, order, admission, "order-changed", "Order changed",
                    DoseRules.ORDER_CHANGED, asOf);
        }
        // Step 5: the slot.
        EffectiveNursingSettings settings = settingsReader.effectiveFor(tenantId, admission.id());
        decision.slotAt = slotFor(tenantId, order, admission, timeline, body, settings, asOf);
        DoseRow earlier = doses.findByOrderAndSlot(tenantId, order.id(), decision.slotAt).orElse(null);
        boolean prnSlotTaken = false;
        if (earlier != null) {
            if (earlier.live()) {
                if (current.kind() == Kind.PRN && body.given()) {
                    // A PRN slot is the database minute, not a dose time: the PRN rules answer (too soon or the daily
                    // maximum, audited). Only if they allow it (no limit applies) is the minute itself the conflict.
                    prnSlotTaken = true;
                } else {
                    throw recorded();
                }
            } else {
                decision.attempt = earlier.attempt() + 1;
                decision.supersedes = earlier.id();
            }
        }

        if (!body.given()) {
            decision.reason = DoseRules.reason(input);
            if (decision.reason == null) {
                throw new InvalidInputException("reasonKey", DoseRules.CHOOSE_REASON);
            }
            if (decision.reason.needsNote()) {
                decision.notGivenNote = DoseRules.otherNote(input);
            }
            return decision;
        }

        // Step 6 (contract 0.4.3, ADR-027 A9): wristband, medicine code, override fields, override after a mismatch,
        // identityConfirmed, second nurse, PRN limits; one message at a time. The wristband is always required.
        WristbandCheck.Result wristband = WristbandCheck.check(input.wristband(), patient.uhid());
        if (wristband == WristbandCheck.Result.EMPTY) {
            throw new InvalidInputException("wristband", DoseRules.SCAN_FIRST);
        }
        if (wristband == WristbandCheck.Result.MISMATCH) {
            denyAtWristband(tenantId, actor, order, admission, patient, drug, decision.slotAt, input);
            doses.insertScanStop(tenantId, admission.id(), order.id(), decision.slotAt, DoseRepository.STOP_WRISTBAND,
                    actor);
            decision.refusal = new Refusal("wristband-mismatch", DoseService.MISMATCH_TITLE, DoseRules.MISMATCH);
            return decision;
        }
        decision.scan = WristbandCheck.normalise(input.wristband());
        MedicineCodeCheck.Result code = MedicineCodeCheck.check(input.medicineCode(), drugCodes.codesOf(drug.key()),
                scanned -> MedicineCodeCheck.looksLikeCode(scanned) && drugCodes.drugKeyOf(scanned).isPresent());
        boolean override = DoseRules.hasOverride(input);
        if (code == MedicineCodeCheck.Result.EMPTY && !override) {
            throw new InvalidInputException("medicineCode", DoseRules.SCAN_MEDICINE);
        }
        if (code == MedicineCodeCheck.Result.NO_CODE_LOADED) {
            // DEC-065: not a mismatch. One IPD_DOSE_REFUSED audit, no deny entry, no scan-stop record, nothing typed stored.
            return refuse(decision, tenantId, actor, order, admission, DoseRules.NO_CODE_LOADED_PROBLEM,
                    DoseRules.NO_CODE_LOADED_TITLE, DoseRules.NO_CODE_LOADED, asOf);
        }
        if (code == MedicineCodeCheck.Result.MISMATCH) {
            denyAtMedicine(tenantId, actor, order, admission, drug, decision.slotAt, input);
            doses.insertScanStop(tenantId, admission.id(), order.id(), decision.slotAt, DoseRepository.STOP_MEDICINE,
                    actor);
            decision.refusal = new Refusal("medicine-mismatch", "Wrong medicine", wrongMedicine(input, drug));
            return decision;
        }
        if (code == MedicineCodeCheck.Result.MATCH) {
            // A code of the ordered drug: method SCAN; an override sent with it is ignored (not checked, not stored).
            decision.medicineScan = MedicineCodeCheck.normalise(input.medicineCode());
        } else {
            // Empty code with an override: the override fields (400), then the 15 minutes after the caller's own stop.
            decision.override = DoseRules.override(input);
            Instant stopSlot = current.kind() == Kind.PRN ? null : decision.slotAt;
            if (doses.hasScanStopWithin15Minutes(tenantId, order.id(), actor, stopSlot)) {
                return refuse(decision, tenantId, actor, order, admission, "override-after-mismatch",
                        DoseRules.OVERRIDE_AFTER_MISMATCH_TITLE, DoseRules.OVERRIDE_AFTER_MISMATCH, asOf);
            }
        }
        if (!DoseRules.confirmed(input)) {
            throw new InvalidInputException("identityConfirmed", DoseRules.CONFIRM);
        }
        if (drug.highAlert() != null) {
            decision.highAlertGiven = true;
            decision.secondNurse = checkSecondNurse(tenantId, caller, input, pinChecked);
        }
        if (current.kind() == Kind.PRN) {
            PrnRules.Decision prn = prnDecision(tenantId, order, admission, current, asOf);
            if (!prn.allowed()) {
                String text = DoseRules.clockText(prn.nextAllowedAt());
                boolean tooSoon = prn.lock() == PrnRules.Lock.TOO_SOON;
                return refuse(decision, tenantId, actor, order, admission, tooSoon ? "prn-too-soon" : "prn-max-reached",
                        tooSoon ? "Too soon" : "Daily maximum reached",
                        (tooSoon ? "Too soon. Next dose allowed at " : "Daily maximum reached. Next dose allowed at ")
                                + text + ".", asOf);
            }
            if (prnSlotTaken) {
                throw recorded();
            }
        }
        return decision;
    }

    /**
     * The slot of the dose. A scheduled or STAT order needs the slot of the body to be one of today's slots of the order,
     * given now; a PRN order has no slot (the body must not send one) and records the database minute.
     */
    private Instant slotFor(UUID tenantId, OrderRow order, AdmissionRow admission, OrderTimeline timeline, Body body,
                            EffectiveNursingSettings settings, Instant asOf) {
        MedicineOrderVersion current = timeline.current();
        if (current.kind() == Kind.PRN) {
            if (body.slotAt() != null) {
                throw new InvalidInputException("slotAt", MedicineRules.RELOAD_MESSAGE);
            }
            // ADR-027 A9: a PRN dose (either outcome) before the version starts or at or after it ends is not due. Not audited.
            if (asOf.isBefore(current.startAt()) || (current.endAt() != null && !asOf.isBefore(current.endAt()))) {
                throw new ConflictException("dose-not-due", "Dose cannot be given now", DoseRules.CANNOT_NOW);
            }
            return asOf.truncatedTo(ChronoUnit.MINUTES);
        }
        if (body.slotAt() == null) {
            throw new InvalidInputException("slotAt", MedicineRules.RELOAD_MESSAGE);
        }
        List<LiveDose> live = new ArrayList<>();
        for (DoseRow d : doses.findBetween(tenantId, admission.id(), DoseSlots.dayStart(asOf), DoseSlots.dayEnd(asOf))) {
            if (d.orderId().equals(order.id()) && d.live() && !"PRN".equals(d.doseKind())) {
                live.add(new LiveDose(d.id(), d.slotAt(), DoseOutcome.ofKey(d.outcome())
                        .orElseThrow(() -> new IllegalStateException("A dose has an unknown outcome"))));
            }
        }
        Slot slot = null;
        for (Slot s : DoseSlots.slots(timeline, null, live, true, settings, asOf)) {
            if (s.slotAt().equals(body.slotAt())) {
                slot = s;
            }
        }
        if (slot == null) {
            throw new InvalidInputException("slotAt", DoseRules.NOT_A_DOSE_TIME);
        }
        switch (slot.state()) {
            case GIVEN:
            case HELD:
            case REFUSED:
                throw recorded();
            case BEFORE_START:
                throw new ConflictException("dose-before-start", "Dose before the order started",
                        DoseRules.BEFORE_START);
            case LATER:
                throw new ConflictException("dose-not-due", "Dose not due yet",
                        DoseRules.notDue(settings.windowBeforeMin()));
            case DUE:
            case LATE:
                break;
            default:
                throw new ConflictException("dose-not-due", "Dose cannot be given now", DoseRules.CANNOT_NOW);
        }
        if (!slot.current()) {
            throw new ConflictException("dose-not-due", "Dose cannot be given now", DoseRules.CANNOT_NOW);
        }
        return slot.slotAt();
    }

    private PrnRules.Decision prnDecision(UUID tenantId, OrderRow order, AdmissionRow admission,
                                          MedicineOrderVersion version, Instant asOf) {
        List<Instant> given = new ArrayList<>();
        for (GivenAt g : doses.findGivenSince(tenantId, admission.id(), asOf.minus(Duration.ofHours(24)))) {
            if (g.orderId().equals(order.id())) {
                given.add(g.recordedAt());
            }
        }
        // A PRN order always carries both limits (database check); an old row without them cannot reach here.
        return PrnRules.decide(given, version.minIntervalMin(), version.maxPer24h(), asOf);
    }

    /** A 409 whose audit row must stay although the answer is 409 (ADR-027 D11, Q11). */
    private Decision refuse(Decision decision, UUID tenantId, UUID actor, OrderRow order, AdmissionRow admission,
                            String problem, String title, String detail, Instant asOf) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", order.id());
        after.put("admissionId", admission.id());
        after.put("refusal", problem);
        audit.record("IPD_DOSE_REFUSED", ENTITY_ORDER, order.id(), null, after);
        decision.refusal = new Refusal(problem, title, detail);
        return decision;
    }

    /**
     * High-alert GIVEN: the second nurse and the PIN format (400), the same person (400), not an active NURSE of the
     * clinic (400). The PIN is not read when {@code pinChecked}.
     */
    private TeamMember checkSecondNurse(UUID tenantId, Caller caller, DoseInput input, boolean pinChecked) {
        UUID secondId = DoseRules.secondNurse(input);
        boolean missing = input.secondNurseId() == null || input.secondNurseId().isBlank();
        boolean pinBad = !pinChecked && !DoseRules.pinFormat(input.secondNursePin());
        if (missing || pinBad) {
            List<FieldMessage> errors = new ArrayList<>();
            if (missing) {
                errors.add(new FieldMessage("secondNurseId", DoseRules.HIGH_ALERT));
            }
            if (pinBad) {
                errors.add(new FieldMessage("secondNursePin", DoseRules.HIGH_ALERT));
            }
            throw new InvalidFieldsException(errors, true);
        }
        if (secondId == null) {
            throw new InvalidInputException("secondNurseId", DoseRules.CHOOSE_NURSE);
        }
        if (secondId.equals(caller.membershipId())) {
            throw new InvalidInputException("secondNurseId", DoseRules.DIFFERENT_NURSE);
        }
        Optional<TeamMember> second = team.find(tenantId, secondId);
        if (second.isEmpty() || !second.get().active() || !NURSE_ROLE.equals(second.get().role())) {
            throw new InvalidInputException("secondNurseId", DoseRules.CHOOSE_NURSE);
        }
        return second.get();
    }

    /**
     * A repeat of a client key (ADR-011 D8): another order's key is a 400; any different value is a 409; the same
     * values are a replay. The second nurse of a high-alert dose is compared by display name, the one thing the
     * stored user id and the membership id sent have in common through {@code platform.api}. A PRN dose has no slot: the
     * body must not send one.
     */
    private DoseRow checkRepeat(UUID tenantId, UUID actor, DoseRow stored, OrderRow order, Body body, DoseInput input) {
        if (!stored.orderId().equals(order.id())) {
            throw new InvalidInputException("clientKey", MedicineRules.RELOAD_MESSAGE);
        }
        // ADR-027 A9 (security L1): the key of a dose recorded by someone else tells the caller nothing about it. Nothing is
        // written: the exception rolls back the transaction (no record-access row, no audit).
        if (!actor.equals(stored.recordedBy())) {
            throw new ConflictException("client-key-reused", "Client key already used", DoseRules.KEY_REUSED);
        }
        boolean prn = "PRN".equals(stored.doseKind());
        boolean same = prn ? body.slotAt() == null : stored.slotAt().equals(body.slotAt());
        if (same && body.given()) {
            same = DoseRepository.GIVEN.equals(stored.outcome());
            if (same && stored.highAlert()) {
                same = sameSecondNurse(tenantId, stored, input);
            }
        } else if (same) {
            NotGivenReason reason = DoseRules.reason(input);
            same = !DoseRepository.GIVEN.equals(stored.outcome()) && reason != null
                    && reason.key().equals(stored.reasonKey());
        }
        if (!same) {
            throw new ConflictException("client-key-reused", "Client key already used", DoseRules.KEY_REUSED);
        }
        return stored;
    }

    private boolean sameSecondNurse(UUID tenantId, DoseRow stored, DoseInput input) {
        UUID sent = DoseRules.secondNurse(input);
        if (sent == null || stored.secondCheckBy() == null) {
            return false;
        }
        Optional<String> sentName = team.find(tenantId, sent).map(TeamMember::name);
        Optional<String> storedName = actors.displayName(tenantId, stored.secondCheckBy());
        return sentName.isPresent() && sentName.equals(storedName);
    }

    /** The deny entry and the audit row of a wristband mismatch (D4). Neither holds a name or the typed text. */
    private void denyAtWristband(UUID tenantId, UUID actor, OrderRow order, AdmissionRow admission,
                                 PatientClinical patient, Drug drug, Instant slotAt, DoseInput input) {
        events.insert(tenantId, admission.id(), ClinicalEventRepository.KIND_DENY,
                DoseRules.deniedText(WristbandCheck.scanForEvent(input.wristband()), patient.uhid(), drug), actor);
        audit.record("IPD_DOSE_WRISTBAND_MISMATCH", ENTITY_ORDER, order.id(), null,
                mismatchAudit(order, admission, drug, slotAt));
    }

    /**
     * The deny entry and the audit row of a medicine code mismatch (ADR-027 D7). The entry holds the code only when it
     * is a code of the drug master; otherwise the fixed words. Neither holds a name or the typed text.
     */
    private void denyAtMedicine(UUID tenantId, UUID actor, OrderRow order, AdmissionRow admission, Drug drug,
                                Instant slotAt, DoseInput input) {
        events.insert(tenantId, admission.id(), ClinicalEventRepository.KIND_DENY,
                DoseRules.medicineDeniedText(MedicineCodeCheck.codeForEvent(input.medicineCode(), knownCode(input)),
                        drug), actor);
        audit.record("IPD_DOSE_MEDICINE_MISMATCH", ENTITY_ORDER, order.id(), null,
                mismatchAudit(order, admission, drug, slotAt));
    }

    private static Map<String, Object> mismatchAudit(OrderRow order, AdmissionRow admission, Drug drug, Instant slotAt) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", order.id());
        after.put("admissionId", admission.id());
        after.put("drugKey", drug.key());
        after.put("slotAt", slotAt == null ? null : slotAt.toString());
        return after;
    }

    /** True when the box is a code of the drug master (any drug). */
    private boolean knownCode(DoseInput input) {
        String box = input.medicineCode();
        if (box == null || box.length() > MedicineCodeCheck.MAX_BOX) {
            return false;
        }
        String code = MedicineCodeCheck.normalise(box);
        return MedicineCodeCheck.looksLikeCode(code) && drugCodes.drugKeyOf(code).isPresent();
    }

    /** "Wrong medicine. This is NAME, not NAME." (the name of the drug of that code, else "an item not on this order"). */
    private String wrongMedicine(DoseInput input, Drug ordered) {
        String box = input.medicineCode();
        String found = null;
        if (box != null && box.length() <= MedicineCodeCheck.MAX_BOX) {
            String code = MedicineCodeCheck.normalise(box);
            if (MedicineCodeCheck.looksLikeCode(code)) {
                found = drugCodes.drugKeyOf(code).flatMap(key -> catalogue.catalogue().find(key)).map(Drug::name)
                        .orElse(null);
            }
        }
        return "Wrong medicine. This is " + (found == null ? "an item not on this order" : found) + ", not "
                + ordered.name() + ".";
    }

    // ---- the write ----

    private DoseRecorded store(UUID tenantId, UUID actor, String role, Caller caller, OrderRow order,
                               AdmissionRow admission, PatientClinical patient, Body body, DoseInput input,
                               Decision decision, UUID colleagueUserId) {
        Drug drug = decision.drug;
        boolean given = body.given();
        boolean highAlert = drug.highAlert() != null;
        UUID secondCheckBy = given && highAlert ? colleagueUserId : null;
        DoseOutcome outcome = given ? DoseOutcome.GIVEN : decision.reason.outcome();
        MedicineOrderVersion version = decision.version;
        NewDose dose = new NewDose(admission.id(), order.id(), body.clientKey(), decision.slotAt, outcome.name(),
                given ? null : decision.reason.key(), actor, secondCheckBy, highAlert, given ? decision.scan : null,
                given ? Boolean.TRUE : null, decision.attempt, decision.supersedes, version.versionNo(),
                version.kind().name(), given ? (decision.override != null ? "OVERRIDE" : "SCAN") : null,
                given && decision.override != null ? decision.override.reason().key() : null,
                given && decision.override != null ? decision.override.note() : null,
                given ? decision.medicineScan : null, decision.beforeVerification, decision.notGivenNote);
        Stored stored;
        try {
            stored = doses.insert(tenantId, dose);
        } catch (DataAccessException e) {
            // The admission lock and the checks above answered first; a refusal means the state or the values are
            // wrong. Only the SQL state is logged: the message can quote the row.
            log.error("Dose insert refused tenantId={} orderId={} sqlState={}", tenantId, order.id(), sqlState(e));
            throw new IllegalStateException("The dose could not be stored", e);
        }
        OrderRef ref = OrderRef.medicine(order.id(), version.versionNo());
        // OQ-76-5: a recorded dose, given or not, acknowledges every open notice of the order.
        notices.acknowledgeByDose(tenantId, order.id(), actor);
        if (given && decision.beforeVerification) {
            reviews.openForDose(tenantId, admission.id(), ref, stored.id());
        }

        String text = given
                ? DoseRules.gaveText(drug, decision.slotAt, highAlert ? decision.secondNurse.name() : null,
                        decision.override == null ? null : decision.override.reason(), decision.beforeVerification)
                : DoseRules.notGivenText(drug, decision.slotAt, decision.reason);
        events.insert(tenantId, admission.id(), ClinicalEventRepository.KIND_GIVE, text, actor);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("doseId", stored.id());
        after.put("orderId", order.id());
        after.put("admissionId", admission.id());
        after.put("drugKey", drug.key());
        after.put("slotAt", decision.slotAt.toString());
        after.put("outcome", outcome.name());
        after.put("orderVersion", version.versionNo());
        after.put("doseKind", version.kind().name());
        if (given) {
            after.put("highAlert", highAlert);
            after.put("secondCheckUserId", secondCheckBy);
            after.put("method", decision.override != null ? "OVERRIDE" : "SCAN");
            after.put("overrideReasonKey", decision.override == null ? null : decision.override.reason().key());
            after.put("notePresent", decision.override != null);
            after.put("beforeVerification", decision.beforeVerification);
        } else {
            after.put("reasonKey", decision.reason.key());
            after.put("notePresent", decision.notGivenNote != null);
        }
        audit.record(given ? "IPD_DOSE_GIVEN" : "IPD_DOSE_NOT_GIVEN", ENTITY_DOSE, stored.id(), null, after);

        DoseRow row = new DoseRow(stored.id(), admission.id(), order.id(), body.clientKey(), decision.slotAt,
                outcome.name(), dose.reasonKey(), actor, secondCheckBy, highAlert, stored.recordedAt(),
                decision.attempt, version.versionNo(), version.kind().name(), dose.scanMethod(),
                dose.overrideReasonKey(), decision.beforeVerification, stored.undoUntil(), null);
        // The clock is read again after the writes, so asOf is never before the recorded time.
        Instant now = orders.databaseNow();
        Viewer viewer = marAssembler.viewer(actor, role, caller.memberRole(), caller.entitlement(), true, now);
        DoseView view = marAssembler.view(tenantId, row, viewer);
        Mar mar = marNow(tenantId, actor, role, caller, admission, patient, now);
        return new DoseRecorded(false, view, mar);
    }

    /** A replay answers the stored dose and the MAR now; it writes one record-access row and nothing else. */
    private DoseRecorded replayAnswer(UUID tenantId, UUID actor, String role, Caller caller, AdmissionRow admission,
                                      PatientClinical patient, DoseRow stored) {
        patientAccess.recordOpened(tenantId, admission.patientId(), actor);
        Instant now = orders.databaseNow();
        Viewer viewer = marAssembler.viewer(actor, role, caller.memberRole(), caller.entitlement(),
                "ADMITTED".equals(admission.status()), now);
        return new DoseRecorded(true, marAssembler.view(tenantId, stored, viewer),
                marNow(tenantId, actor, role, caller, admission, patient, now));
    }

    private Mar marNow(UUID tenantId, UUID actor, String role, Caller caller, AdmissionRow admission,
                       PatientClinical patient, Instant now) {
        return marAssembler.mar(tenantId, actor, caller.membershipId(), role, caller.memberRole(), caller.entitlement(),
                admission, patient, now);
    }

    // ---- the caller ----

    /** Who is asking: the token role, then (after the body check) the role and membership read again. */
    private record Caller(String tokenRole, ModuleEntitlement entitlement, String memberRole, UUID membershipId) {
    }

    /** Steps 1: token role (403), module Off (403), Read-only (409). */
    private Caller caller(UUID tenantId, UUID actor, String role) {
        MedicineOrderService.requireRole(role, DoseRules.GIVE_ROLES, DoseRules.ONLY_GIVERS);
        ModuleEntitlement ent = gate.check(tenantId, true);
        return new Caller(role, ent, null, null);
    }

    /** Step 3: the role and membership read again from the active membership (403). */
    private Caller member(UUID tenantId, UUID actor, Caller caller) {
        TeamMember member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active)
                .filter(m -> DoseRules.GIVE_ROLES.contains(m.role()))
                .orElseThrow(() -> MedicineOrderService.forbidden(DoseRules.ONLY_GIVERS));
        return new Caller(caller.tokenRole(), caller.entitlement(), member.role(), member.membershipId());
    }

    private PatientClinical patient(UUID tenantId, AdmissionRow admission) {
        return patients.find(tenantId, admission.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    private static ConflictException recorded() {
        return new ConflictException("dose-recorded", "Dose already recorded", DoseRules.RECORDED);
    }

    private static String sqlState(DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        return root instanceof java.sql.SQLException sql ? sql.getSQLState() : "-";
    }

}
