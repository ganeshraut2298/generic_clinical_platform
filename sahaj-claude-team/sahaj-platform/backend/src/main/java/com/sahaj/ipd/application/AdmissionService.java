package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionRepository.AdmittedRow;
import com.sahaj.ipd.application.AdmissionRepository.DepositRow;
import com.sahaj.ipd.application.AdmissionRepository.NewAdmission;
import com.sahaj.ipd.application.WardScopeRepository.UnreviewedMark;
import com.sahaj.ipd.application.AdmissionViews.AdmissionList;
import com.sahaj.ipd.application.AdmissionViews.AdmittedPatientRow;
import com.sahaj.ipd.application.AdmissionViews.BedOption;
import com.sahaj.ipd.application.AdmissionViews.CategoryAdds;
import com.sahaj.ipd.application.AdmissionViews.ChargeService;
import com.sahaj.ipd.application.AdmissionViews.DepositAmounts;
import com.sahaj.ipd.application.AdmissionViews.DepositEntry;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.AdmissionViews.DoctorOption;
import com.sahaj.ipd.application.AdmissionViews.Options;
import com.sahaj.ipd.application.AdmissionViews.Rules;
import com.sahaj.ipd.application.AdmissionViews.RulesInput;
import com.sahaj.ipd.application.AdmissionViews.Summary;
import com.sahaj.ipd.application.AdmissionViews.WardOption;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.EmergencyCaseRepository.CaseRow;
import com.sahaj.ipd.application.EmergencyViews.Counts;
import com.sahaj.ipd.application.EmergencyViews.Handoff;
import com.sahaj.ipd.application.DischargeRepository.DischargeRow;
import com.sahaj.ipd.application.DischargeRepository.FinalBillRow;
import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import com.sahaj.ipd.application.IpdBilling.Batch;
import com.sahaj.ipd.application.IpdSettingsRepository.CategoryRow;
import com.sahaj.ipd.application.IpdSettingsRepository.ConfigRow;
import com.sahaj.ipd.application.IpdSettingsRepository.DepositRules;
import com.sahaj.ipd.application.ScopeViews.AdmissionScope;
import com.sahaj.ipd.application.ScopeViews.AdmittedEmergencyAccess;
import com.sahaj.ipd.application.WardRepository.WardRow;
import com.sahaj.ipd.application.AdmittedCards.FactsPlan;
import com.sahaj.ipd.domain.card.CardAccessRule;
import com.sahaj.ipd.domain.card.CardCatalogue;
import com.sahaj.ipd.domain.card.ResolvedCardSetup;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientClinicalPort.PatientClinical;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-23 IPD admissions and deposits. Every call first checks the IPD state (Off answers 403 module-off; a
 * write in Read-only answers 409 ipd-readonly). Admitting takes the clinic's IPD advisory lock (the one SAH-22
 * writes use), then locks the bed row, so two front desks cannot give the same bed or the same IP number to two
 * people. Money is integer paise. The reason for admission, the patient's name and mobile are never logged and
 * never audited: audit rows carry ids, the IP number, type, payer, bed code, amounts and modes only.
 */
@Service
public class AdmissionService {

    static final long MAX_PAISE = 100_000_000L;
    static final long RUPEE = 100L;
    static final String DEFAULT_REASON = "Under evaluation";

    private static final Logger log = LoggerFactory.getLogger(AdmissionService.class);
    private static final String IP_NUMBER_CONSTRAINT = "uq_admission_tenant_ip_number";
    private static final int REASON_MAX = 200;
    private static final Set<String> TYPES = Set.of("planned", "emergency", "daycare", "maternity");
    private static final Set<String> PAYERS = Set.of("Self", "Insurance", "Corporate");
    private static final Set<String> MODES = Set.of("Cash", "UPI", "Card");
    /**
     * The people who work the floor: they admit, add charges, move patients and change bed state. The same three
     * roles are written as {@code ADMITTERS} in {@link com.sahaj.ipd.web.AdmissionController}, where an annotation
     * needs a constant string.
     */
    static final Set<String> FLOOR_ROLES = Set.of("OWNER", "FRONT_DESK", "NURSE");
    private static final Set<String> ADMIT_ROLES = FLOOR_ROLES;
    private static final Set<String> CHARGE_ROLES = FLOOR_ROLES;
    /** SAH-26: who may move a patient to another bed. */
    static final Set<String> TRANSFER_ROLES = FLOOR_ROLES;
    /** Security review M1: the mobile number is shown to these roles only. */
    private static final Set<String> MOBILE_ROLES = Set.of("OWNER", "FRONT_DESK");

    private final AdmissionRepository admissions;
    private final BedRepository beds;
    private final WardRepository wards;
    private final IpdSettingsRepository settings;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final TeamMemberPort team;
    private final PatientLookupPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final IpdGate gate;
    private final IpdCatalog catalog;
    private final ChargeRepository charges;
    private final IpdBilling billing;
    private final DischargeRepository discharges;
    private final EmergencyService emergency;
    private final VitalsRepository vitals;
    private final AdmittedCards cards;
    private final CardSetups cardSetups;
    private final ScopeGuard guard;
    private final WardScopeRepository scopeRepository;

    AdmissionService(AdmissionRepository admissions, BedRepository beds, WardRepository wards,
                     IpdSettingsRepository settings, OwnerGuard ownerGuard, AuditLog auditLog, TeamMemberPort team,
                     PatientLookupPort patients, PatientAccessPort patientAccess, ActorDirectoryPort actors,
                     IpdGate gate, IpdCatalog catalog, ChargeRepository charges, IpdBilling billing,
                     DischargeRepository discharges, EmergencyService emergency, VitalsRepository vitals,
                     AdmittedCards cards, CardSetups cardSetups, ScopeGuard guard, WardScopeRepository scopeRepository) {
        this.guard = guard;
        this.scopeRepository = scopeRepository;
        this.cardSetups = cardSetups;
        this.cards = cards;
        this.vitals = vitals;
        this.emergency = emergency;
        this.discharges = discharges;
        this.charges = charges;
        this.billing = billing;
        this.admissions = admissions;
        this.beds = beds;
        this.wards = wards;
        this.settings = settings;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.team = team;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.gate = gate;
        this.catalog = catalog;
    }

    // ---- deposit rules ----

    @Transactional(readOnly = true)
    public Rules rules(UUID tenantId) {
        gate.check(tenantId, false);
        return toRules(catalog.depositRules(tenantId));
    }

    @Transactional
    public Rules updateRules(UUID tenantId, UUID actor, RulesInput input) {
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        DepositRules current = catalog.depositRules(tenantId);
        DepositRules next = validate(input, current.confirmed());
        if (!next.equals(current)) {
            settings.upsertDepositRules(tenantId, next, actor, gate.now());
            auditLog.record("IPD_ADMISSION_RULES_CHANGED", "IPD_CONFIG", null, snapshot(current), snapshot(next));
        }
        return toRules(next);
    }

    @Transactional
    public Rules confirmRules(UUID tenantId, UUID actor) {
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        DepositRules current = catalog.depositRules(tenantId);
        if (current.confirmed()) {
            return toRules(current);
        }
        DepositRules next = withConfirmed(current);
        settings.upsertDepositRules(tenantId, next, actor, gate.now());
        auditLog.record("IPD_ADMISSION_RULES_CONFIRMED", "IPD_CONFIG", null, snapshot(current), snapshot(next));
        return toRules(next);
    }

    // ---- admit sheet data and lists ----

    /**
     * The data of the Admit sheet. SAH-28: with an emergency case id the answer also carries the hand-off data of the
     * case. Check order then: role (403), module Off (403), case not found (404), case closed (409 case-closed). It
     * changes no case and writes no record-access row (a case has no patient record before it is admitted); a 200
     * answer for a case writes one audit row IPD_ER_HANDOFF_OPENED (security review M3). One REPEATABLE READ
     * transaction, so the case, the setup and the beds come from one snapshot (read-write only for that audit row).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Options options(UUID tenantId, String role, UUID emergencyCaseId) {
        if (emergencyCaseId != null) {
            requireRole(role, ADMIT_ROLES, "You cannot admit a patient.");
        }
        gate.check(tenantId, false);
        CaseRow emergencyCase = emergencyCaseId == null ? null : emergency.findOpenCase(tenantId, emergencyCaseId);
        List<DoctorOption> doctors = new ArrayList<>();
        for (TeamMember doctor : team.findActiveDoctors(tenantId)) {
            doctors.add(new DoctorOption(doctor.membershipId(), doctor.name()));
        }
        Map<UUID, String> wardNames = new HashMap<>();
        for (WardRow ward : wards.findWards(tenantId)) {
            wardNames.put(ward.id(), ward.name());
        }
        Map<String, CategoryRow> categories = catalog.categories(tenantId);
        // SAH-26: the free beds come ordered by category (General to ICU), then room, then code.
        List<BedRow> freeBeds = new ArrayList<>();
        for (BedRow bed : beds.findAll(tenantId)) {
            if ("free".equals(bed.status())) {
                freeBeds.add(bed);
            }
        }
        freeBeds.sort(Comparator
                .comparingInt((BedRow b) -> IpdService.KEYS.indexOf(b.categoryKey()))
                .thenComparing(BedOrder.byRoomThenCode(wards.findRooms(tenantId))));
        List<BedOption> free = new ArrayList<>();
        for (BedRow bed : freeBeds) {
            CategoryRow category = categories.get(bed.categoryKey());
            free.add(new BedOption(bed.id(), bed.code(), wardNames.get(bed.wardId()), bed.categoryKey(),
                    IpdService.NAMES.get(bed.categoryKey()), category.ratePaise()));
        }
        Handoff handoff = emergencyCase == null ? null : emergency.handoff(tenantId, emergencyCase, free);
        return new Options(doctors, free, toRules(catalog.depositRules(tenantId)), handoff);
    }

    /**
     * The Admitted list (table and cards, SAH-36 ADR-013). Check order: IPD Off (403 module-off), then the role in the
     * token and the role read again from the active membership, both one of the four list roles (403 forbidden: a
     * deactivated member or a changed role applies on the next reload), then the status (400). One REPEATABLE READ
     * transaction, one {@code asOf} (the database clock). A fixed number of set queries whatever the number of patients,
     * no call per patient. Writes nothing: no record-access row, no audit row, no event (ADR-013 D10, D11).
     * {@code clinical} is on a row, and {@code safety} is set, only when the token role and the membership role are both
     * OWNER, DOCTOR or NURSE.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdmissionList list(UUID tenantId, UUID actor, String role, String status, String cardFacts) {
        ModuleEntitlement ent = gate.check(tenantId, false);
        SafetySupport.requireRole(role, AdmittedCardRules.LIST_ROLES, AdmittedCardRules.FORBIDDEN_MESSAGE);
        String memberRole = SafetySupport.membershipRole(team, tenantId, actor, AdmittedCardRules.LIST_ROLES,
                AdmittedCardRules.FORBIDDEN_MESSAGE);
        if (status != null && !"ADMITTED".equals(status)) {
            throw new InvalidInputException("status", "Only the Admitted list is available");
        }
        // SAH-34 (AC 1): the scope is built once, right after the membership re-read, from the signed-in user only.
        PatientScope scope = guard.scopeWithMembership(tenantId, actor, role, memberRole);
        boolean allFacts = allCardFacts(cardFacts, role, memberRole);
        // SAH-37 (ADR-015 D4, D6): the published setup is read in this snapshot (R7); it decides which new card facts
        // are sent.
        ResolvedCardSetup cardSetup = cardSetups.live(tenantId).resolved();
        FactsPlan plan = FactsPlan.of(allFacts ? Set.copyOf(CardCatalogue.fieldKeys()) : cardSetup.shownFields(), role,
                memberRole);
        Instant asOf = cards.asOf();
        List<BedRow> allBeds = beds.findAll(tenantId);
        int occupied = (int) allBeds.stream().filter(b -> "occ".equals(b.status())).count();
        int available = (int) allBeds.stream().filter(b -> "free".equals(b.status())).count();
        DepositRules rules = catalog.depositRules(tenantId);
        ConfigRow config = catalog.config(tenantId);
        boolean roomLevel = config.roomLevel();
        Lookups lookups = new Lookups();
        Instant now = gate.now();
        List<AdmittedRow> everyone = admissions.findAdmittedWithPlacement(tenantId);
        // SAH-34: only the rows in scope go on. Nothing of another row is read for the answer (patients, bills, flags).
        List<AdmittedRow> placed = scope.isAll() ? everyone : inScope(scope, everyone);
        Set<UUID> visibleIds = null;
        if (!scope.isAll()) {
            visibleIds = new HashSet<>();
            for (AdmittedRow row : placed) {
                visibleIds.add(row.admission().id());
            }
        }
        List<AdmissionRow> admitted = new ArrayList<>();
        Set<UUID> patientIds = new HashSet<>();
        for (AdmittedRow placedRow : placed) {
            admitted.add(placedRow.admission());
            patientIds.add(placedRow.admission().patientId());
        }
        // ADR-013 D15: decided once, from the membership re-read above, before any patient read.
        boolean clinicalAccess = AdmittedCardRules.clinical(role, memberRole);
        AdmittedCards.Patients loaded = cards.patients(tenantId, patientIds, clinicalAccess, plan.mobile());
        Map<UUID, PatientClinical> patientMap = loaded.byId();
        lookups.preloadDoctors(tenantId, admitted);
        // SAH-25: one query for the charges of all admitted patients; the deposit sums come with the rows.
        Batch batch = billing.forAdmitted(tenantId, now, rules.alertPercent(), catalog.categories(tenantId), config);
        // SAH-27: one query for the latest discharge sign-off of every admitted patient.
        Map<UUID, SignoffRow> signoffs = discharges.findLatestSignoffsOfAdmitted(tenantId);
        LocalDate today = DischargeRules.istDate(now);
        // SAH-33, SAH-36: the Safety tab count, the banner and the clinical facts of the cards, for OWNER, DOCTOR and
        // NURSE only, from the same snapshot and the same escalation rows; null / absent for every other role.
        UnreviewedMark unreviewed = AdmittedCardRules.both(Set.of("OWNER"), role, memberRole)
                ? scopeRepository.unreviewedMark(tenantId) : null;
        AdmittedCards.Clinical clinical = clinicalAccess
                ? cards.clinical(tenantId, asOf, placed, patientMap, plan, ids -> actors.displayNames(tenantId, ids),
                        visibleIds, unreviewed)
                : AdmittedCards.Clinical.NONE;
        List<AdmittedPatientRow> items = new ArrayList<>();
        int alerts = 0;
        int goingHome = 0;
        for (AdmittedRow placedRow : placed) {
            AdmissionRow row = placedRow.admission();
            RunningBill bill = batch.bill(row, lookups.doctor(tenantId, row.doctorMembershipId()));
            boolean home = DischargeRules.goingHomeToday(signoffs.get(row.id()), today);
            PatientClinical patient = patientMap.get(row.patientId());
            Summary summary = summary(tenantId, row, patient == null ? null : patient.name(),
                    patient == null ? null : patient.age(), patient == null ? null : patient.sex(), rules, lookups,
                    bill, home);
            if (summary.depositLow()) {
                alerts++;
            }
            if (home) {
                goingHome++;
            }
            Instant grantEnds = scope.grantOnlyExpiry(placedRow.wardId(), row.doctorMembershipId(), row.id());
            items.add(AdmittedPatientRow.of(summary, patient == null ? null : patient.uhid(), placedRow.wardId(),
                    roomLevel ? placedRow.roomName() : null, clinical.flags().get(row.id()),
                    plan.mobile() ? Optional.ofNullable(loaded.mobiles().get(row.patientId())) : null)
                    .withScope(mine(scope, memberRole, placedRow),
                            grantEnds == null ? null : new AdmittedEmergencyAccess(grantEnds)));
        }
        boolean canAdmit = ADMIT_ROLES.contains(role) && !"readonly".equals(ent.ipdState());
        Instant dayStart = today.atStartOfDay(RunningBill.IST).toInstant();
        int dischargedToday = discharges.countDischargedBetween(tenantId, dayStart,
                today.plusDays(1).atStartOfDay(RunningBill.IST).toInstant());
        Counts emergencyCounts = emergency.counts(tenantId, ent);
        // The select "Ward": every ward of the clinic in the order of the Beds setup, also wards with no patient.
        List<WardOption> wardOptions = new ArrayList<>();
        for (WardRow ward : wards.findWards(tenantId)) {
            wardOptions.add(new WardOption(ward.id(), ward.name()));
        }
        AdmissionScope scopeView = new AdmissionScope(kindName(scope), items.size(), everyone.size(),
                scope.isNurse() ? scope.hasWard() : null,
                !scope.isAll() && AdmittedCardRules.both(Set.of(ScopeGuard.NURSE), role, memberRole));
        return new AdmissionList(allBeds.size(), occupied, available, canAdmit, alerts, rules.alertPercent(),
                dischargedToday, goingHome, emergencyCounts, clinical.banner(), asOf, List.copyOf(wardOptions),
                AdmittedCardRules.abilities(role, memberRole, ent.ipdState()), cardSetup, List.copyOf(items),
                scopeView);
    }

    /** The rows the scope reaches: the ward or own rule, or a live emergency access grant of the caller. */
    private static List<AdmittedRow> inScope(PatientScope scope, List<AdmittedRow> rows) {
        List<AdmittedRow> kept = new ArrayList<>();
        for (AdmittedRow row : rows) {
            if (scope.allows(row.wardId(), row.admission().doctorMembershipId(), row.admission().id())) {
                kept.add(row);
            }
        }
        return kept;
    }

    /** ALL, WARDS or OWN on the wire; a nurse with no ward (NONE) is WARDS with {@code hasWard} false. */
    private static String kindName(PatientScope scope) {
        switch (scope.kind()) {
            case ALL:
                return "ALL";
            case OWN:
                return "OWN";
            default:
                return "WARDS";
        }
    }

    /**
     * SAH-34 (AC 33, OQ-34-9): the filter "My patients", never a security rule. OWNER and DOCTOR: the admitting doctor is
     * the caller's membership. NURSE: the bed's ward is one of her live wards, with the switch on or off. Absent (null)
     * for every other role, and no membership id is sent.
     */
    private static Boolean mine(PatientScope scope, String memberRole, AdmittedRow row) {
        if (scope.isNurse()) {
            return row.wardId() != null && scope.wardIds().contains(row.wardId());
        }
        if ("OWNER".equals(memberRole) || "DOCTOR".equals(memberRole)) {
            return scope.membershipId() != null && scope.membershipId().equals(row.admission().doctorMembershipId());
        }
        return null;
    }

    /**
     * Opening an admission shows the patient's name, reason and (to some roles) mobile number, so it writes one
     * {@code patient.record_access} row (security review M1). That makes this a read-write transaction, on purpose.
     * The charge and deposit POSTs return the same sheet, so they write one too (security review L2). The list and
     * the bed board do not.
     */
    @Transactional
    public Detail detail(UUID tenantId, UUID actor, String role, UUID admissionId) {
        ModuleEntitlement ent = gate.check(tenantId, false);
        AdmissionRow row = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        // SAH-34: after the 404, before the record-access row (a refusal writes nothing).
        guard.requireAdmission(tenantId, actor, role, null, admissionId, "admission-detail");
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        return buildDetail(tenantId, actor, role, ent, row);
    }

    // ---- admit and collect a deposit ----

    @Transactional
    public Detail admit(UUID tenantId, UUID actor, String role, AdmissionInput input) {
        // Defence in depth (SAH-28): the admit roles are re-checked here, before the IPD state.
        requireRole(role, ADMIT_ROLES, "You cannot admit a patient.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        if (input == null) {
            throw new InvalidInputException("patientId", "Choose the patient");
        }
        // Bad input is refused before the clinic-wide lock is taken (security review L3).
        String reason = validateShape(input);
        long deposit = input.depositPaise() == null ? 0L : input.depositPaise();
        validateDepositShape(input.depositPaise(), input.depositMode());
        wards.lockTenant(tenantId);
        // SAH-28: an admission from the emergency queue checks its case first, right after the clinic lock and before
        // the patient: 404 when it does not exist, 409 case-closed when it was closed meanwhile. The row stays locked.
        CaseRow emergencyCase = input.emergencyCaseId() == null ? null
                : emergency.lockOpenCase(tenantId, input.emergencyCaseId());
        String source = emergencyCase == null ? "DIRECT" : "EMERGENCY";

        PatientSummary patient = patients.find(tenantId, input.patientId())
                .orElseThrow(() -> new NotFoundException("Patient not found"));
        if (patient.merged()) {
            throw new ConflictException("patient-merged", "Patient record was merged",
                    "This patient record was merged into another. Use the kept record.");
        }
        if (admissions.existsAdmitted(tenantId, input.patientId())) {
            throw alreadyAdmitted();
        }
        TeamMember doctor = team.findActiveDoctors(tenantId).stream()
                .filter(d -> d.membershipId().equals(input.doctorMembershipId())).findFirst()
                .orElseThrow(() -> new InvalidInputException("doctorMembershipId", "Choose an active doctor"));
        BedRow bed = beds.findForUpdate(tenantId, input.bedId()).filter(b -> "free".equals(b.status()))
                .orElseThrow(() -> new ConflictException("bed-not-free", "Bed is not free",
                        "That bed is no longer free. Pick another."));
        if (!Boolean.TRUE.equals(input.consentSigned())) {
            throw new InvalidInputException("consentSigned", "Record the admission consent first");
        }
        DepositRules rules = catalog.depositRules(tenantId);
        long minimum = rules.minimum(input.admissionType(), bed.categoryKey());
        boolean emergencyFree = "emergency".equals(input.admissionType()) && rules.emergencyWithoutDeposit();
        if ("Self".equals(input.payer()) && deposit < minimum && !emergencyFree) {
            throw new InvalidInputException("depositPaise", "The deposit is below the minimum of " + rupees(minimum)
                    + ". Collect at least that, or choose Insurance or Corporate.");
        }

        String year = financialYear(gate.now());
        String ipNumber = "IP/" + year + "/" + String.format("%04d", admissions.nextIpNo(tenantId, year));
        UUID admissionId;
        try {
            admissionId = admissions.insert(tenantId, new NewAdmission(input.patientId(), ipNumber, bed.id(),
                    doctor.membershipId(), input.admissionType(), input.payer(), reason, actor, source));
        } catch (DuplicateKeyException e) {
            if (isIpNumberClash(e)) {
                // The number comes from a counter taken under the clinic lock, so a clash means the counter is wrong.
                // Not the patient's fault and not retryable by the user: log ids only, answer 500.
                log.error("IP number clash tenantId={} ipNumber={}", tenantId, ipNumber);
                throw new IllegalStateException("The IP number was already used in this clinic", e);
            }
            throw alreadyAdmitted();
        }
        beds.markOccupied(tenantId, bed.id());
        UUID depositId = null;
        if (deposit > 0) {
            depositId = admissions.insertDeposit(tenantId, admissionId, deposit, input.depositMode(), actor);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ipNumber", ipNumber);
        after.put("patientId", input.patientId());
        after.put("doctorMembershipId", doctor.membershipId());
        after.put("admissionType", input.admissionType());
        after.put("payer", input.payer());
        after.put("source", source);
        after.put("bedId", bed.id());
        after.put("bedCode", bed.code());
        if (deposit > 0) {
            after.put("depositId", depositId);
            after.put("depositPaise", deposit);
            after.put("depositMode", input.depositMode());
        }
        auditLog.record("IPD_ADMITTED", "IPD_ADMISSION", admissionId, null, after);
        if (emergencyCase != null) {
            // The case becomes ADMITTED with the admission and the patient linked, in the same transaction.
            emergency.markAdmitted(tenantId, actor, emergencyCase, admissionId, input.patientId());
        }
        AdmissionRow row = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new IllegalStateException("The new admission was not found"));
        return buildDetail(tenantId, actor, role, ent, row, patient);
    }

    @Transactional
    public Detail addDeposit(UUID tenantId, UUID actor, String role, UUID admissionId,
                             Long amountPaise, String mode) {
        // Defence in depth (security review L1), as in addCharge.
        // Who may collect a deposit: the one copy is CardAccessRule.DEPOSIT, also behind the card's Deposit action.
        requireRole(role, CardAccessRule.DEPOSIT, "You cannot collect a deposit.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        if (amountPaise == null || amountPaise < RUPEE || amountPaise > MAX_PAISE || amountPaise % RUPEE != 0) {
            throw new InvalidInputException("amountPaise", "Enter whole rupees, at least ₹1 and at most ₹10,00,000");
        }
        if (mode == null || !MODES.contains(mode)) {
            throw new InvalidInputException("mode", "Choose Cash, UPI or Card");
        }
        // NOTE (SAH-23, code review 3): the clinic IPD lock is taken before the status check so that a discharge,
        // which must take the same lock before it changes the status, cannot slip in between the check and the
        // insert. The discharge story must call wards.lockTenant first, too.
        wards.lockTenant(tenantId);
        AdmissionRow row = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        // SAH-34: under the clinic IPD lock (a transfer or discharge takes it too), before the state checks.
        guard.requireAdmissionLocked(tenantId, actor, role, null, admissionId, "deposit-add");
        if (!"ADMITTED".equals(row.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted",
                    "This patient is not admitted any more. A deposit cannot be added.");
        }
        // The answer is the full admission sheet, so the access is logged like an open (security review L2).
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        UUID depositId = admissions.insertDeposit(tenantId, admissionId, amountPaise, mode, actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", admissionId);
        after.put("ipNumber", row.ipNumber());
        after.put("amountPaise", amountPaise);
        after.put("mode", mode);
        auditLog.record("IPD_DEPOSIT_COLLECTED", "IPD_DEPOSIT", depositId, null, after);
        AdmissionRow updated = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new IllegalStateException("The admission was not found"));
        return buildDetail(tenantId, actor, role, ent, updated);
    }

    // ---- SAH-25 extra charges ----

    /** The fixed list of services with their base prices. */
    @Transactional(readOnly = true)
    public List<ChargeService> chargeServices(UUID tenantId) {
        gate.check(tenantId, false);
        return ChargeServices.ALL;
    }

    /**
     * Adds one charge row (quantity 1) to an admitted patient's bill. Append-only: nothing removes it in this story.
     * Same lock order as a deposit: the clinic IPD lock first, then the admission, so that a discharge cannot slip
     * between the status check and the insert. The audit row carries the admission id, the service key and the base
     * price only.
     */
    @Transactional
    public Detail addCharge(UUID tenantId, UUID actor, String role, UUID admissionId, String serviceKey) {
        // Defence in depth (security review L1): a charge cannot be removed, so the role is re-checked here.
        requireRole(role, CHARGE_ROLES, "You cannot add a charge.");
        ModuleEntitlement ent = gate.check(tenantId, true);
        ChargeService service = ChargeServices.find(serviceKey)
                .orElseThrow(() -> new InvalidInputException("serviceKey", "Choose a service from the list"));
        wards.lockTenant(tenantId);
        AdmissionRow row = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        guard.requireAdmissionLocked(tenantId, actor, role, null, admissionId, "charge-add");
        if (!"ADMITTED".equals(row.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted",
                    "This patient is not admitted any more. A charge cannot be added.");
        }
        // The answer is the full admission sheet, so the access is logged like an open (security review L2).
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        if (charges.countByService(tenantId, admissionId, service.key()) >= ChargeServices.MAX_ROWS_PER_SERVICE) {
            throw new InvalidInputException("serviceKey", "At most " + ChargeServices.MAX_ROWS_PER_SERVICE
                    + " charges of the same service can be added to one admission");
        }
        UUID chargeId = charges.insert(tenantId, admissionId, service.key(), service.name(), service.basePaise(),
                actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", admissionId);
        after.put("serviceKey", service.key());
        after.put("basePaise", service.basePaise());
        auditLog.record("IPD_CHARGE_ADDED", "IPD_CHARGE", chargeId, null, after);
        return buildDetail(tenantId, actor, role, ent, row);
    }

    // ---- helpers ----

    /**
     * SAH-37 (ADR-015 D6): {@code cardFacts} is "setup" (default) or "all". "all" is for the owner's live preview only (token
     * role and membership role both OWNER, else 403 forbidden); any other value is a 400.
     */
    private static boolean allCardFacts(String cardFacts, String role, String memberRole) {
        if (cardFacts == null || "setup".equals(cardFacts)) {
            return false;
        }
        if (!"all".equals(cardFacts)) {
            throw new InvalidInputException("cardFacts", "Use cardFacts=setup or cardFacts=all");
        }
        if (!AdmittedCardRules.both(Set.of("OWNER"), role, memberRole)) {
            throw new AccessDeniedException(SafetySupport.FORBIDDEN);
        }
        return true;
    }

    private static void requireRole(String role, Set<String> allowed, String message) {
        if (role == null || !allowed.contains(role)) {
            throw new AccessDeniedException(message);
        }
    }

    private PatientSummary patient(UUID tenantId, UUID patientId) {
        return patients.find(tenantId, patientId).orElse(null);
    }

    /** Per-request caches so that one doctor or collector is looked up once. */
    private final class Lookups {
        private final Map<UUID, String> doctors = new HashMap<>();
        private final Map<UUID, String> collectors = new HashMap<>();

        /** One query for the doctors of all admitted patients, so the list does not look them up row by row. */
        void preloadDoctors(UUID tenantId, List<AdmissionRow> rows) {
            Set<UUID> ids = new HashSet<>();
            for (AdmissionRow row : rows) {
                ids.add(row.doctorMembershipId());
            }
            if (ids.isEmpty()) {
                return;
            }
            Map<UUID, TeamMember> found = team.findAll(tenantId, ids);
            for (UUID id : ids) {
                TeamMember member = found.get(id);
                doctors.put(id, member == null ? "Doctor" : member.name());
            }
        }

        String doctor(UUID tenantId, UUID membershipId) {
            return doctors.computeIfAbsent(membershipId,
                    id -> team.find(tenantId, id).map(TeamMember::name).orElse("Doctor"));
        }

        String collector(UUID tenantId, UUID userId) {
            return collectors.computeIfAbsent(userId,
                    id -> actors.displayName(tenantId, id).orElse("Team member"));
        }
    }

    private Summary summary(UUID tenantId, AdmissionRow row, PatientSummary patient, DepositRules rules,
                            Lookups lookups, RunningBill bill, boolean goingHome) {
        return summary(tenantId, row, patient == null ? null : patient.name(), patient == null ? null : patient.age(),
                patient == null ? null : patient.sex(), rules, lookups, bill, goingHome);
    }

    /** The row of the table. A patient record that cannot be found is shown as "Patient" with no age and no sex. */
    private Summary summary(UUID tenantId, AdmissionRow row, String patientName, Integer age, String sex,
                            DepositRules rules, Lookups lookups, RunningBill bill, boolean goingHome) {
        long minimum = rules.minimum(row.admissionType(), row.categoryKey());
        String state;
        if (!"Self".equals(row.payer())) {
            state = "NOT_NEEDED";
        } else {
            state = row.depositTotalPaise() >= minimum ? "OK" : "DUE";
        }
        return new Summary(row.id(), row.ipNumber(), row.patientId(), patientName == null ? "Patient" : patientName,
                age, sex, lookups.doctor(tenantId, row.doctorMembershipId()), row.bedCode(),
                IpdService.NAMES.get(row.categoryKey()), row.wardName(), row.admissionType(), row.payer(),
                bill.billDays(), row.depositTotalPaise(), minimum, state, row.admittedAt(),
                bill.totalPaise(), bill.depositLow(row.payer()), goingHome);
    }

    private Detail buildDetail(UUID tenantId, UUID actor, String role, ModuleEntitlement ent, AdmissionRow row) {
        return buildDetail(tenantId, actor, role, ent, row, patient(tenantId, row.patientId()));
    }

    /** SAH-26: the admission sheet for a caller that has changed the admission in this transaction. */
    Detail sheet(UUID tenantId, UUID actor, String role, ModuleEntitlement ent, AdmissionRow row) {
        return buildDetail(tenantId, actor, role, ent, row);
    }

    private Detail buildDetail(UUID tenantId, UUID actor, String role, ModuleEntitlement ent, AdmissionRow row,
                               PatientSummary patient) {
        boolean admitted = "ADMITTED".equals(row.status());
        if (!admitted) {
            Optional<FinalBillRow> frozenBill = discharges.findFinalBill(tenantId, row.id());
            Optional<DischargeRow> frozenDischarge = discharges.findDischarge(tenantId, row.id());
            if (frozenBill.isPresent() && frozenDischarge.isPresent()) {
                return dischargedDetail(tenantId, role, row, patient, frozenBill.get(), frozenDischarge.get());
            }
            // A row marked DISCHARGED without a frozen bill (data from before SAH-27, which had no discharge):
            // shown as it was at discharge time, nothing can be changed.
        }
        Lookups lookups = new Lookups();
        DepositRules rules = catalog.depositRules(tenantId);
        Instant now = admitted || row.dischargedAt() == null ? gate.now() : row.dischargedAt();
        RunningBill bill = billing.forOne(tenantId, row.id(), now, rules.alertPercent())
                .bill(row, lookups.doctor(tenantId, row.doctorMembershipId()));
        boolean goingHome = admitted
                && DischargeRules.goingHomeToday(discharges.findLatestSignoff(tenantId, row.id()).orElse(null),
                        DischargeRules.istDate(now));
        Summary s = summary(tenantId, row, patient, rules, lookups, bill, goingHome);
        List<DepositEntry> deposits = new ArrayList<>();
        for (DepositRow d : admissions.findDeposits(tenantId, row.id())) {
            deposits.add(new DepositEntry(d.id(), d.amountPaise(), d.mode(), d.collectedAt(),
                    lookups.collector(tenantId, d.collectedBy())));
        }
        String state = ent.ipdState();
        boolean canCollect = CardAccessRule.DEPOSIT.contains(role) && ("trial".equals(state) || "on".equals(state))
                && "ADMITTED".equals(row.status());
        boolean canAddCharge = CHARGE_ROLES.contains(role) && !"readonly".equals(state)
                && "ADMITTED".equals(row.status());
        boolean canTransfer = TRANSFER_ROLES.contains(role) && !"readonly".equals(state)
                && "ADMITTED".equals(row.status());
        boolean canStartDischarge = admitted && DischargeRules.READ_ROLES.contains(role) && !"readonly".equals(state);
        // SAH-30: the Nursing button, vitals due and the latest score; null for a role without vitals access.
        VitalsRepository.VitalsRow latestVitals = VitalsRules.canRead(role)
                ? vitals.findLatest(tenantId, row.id()).orElse(null) : null;
        // The database clock, not the application clock, so "vitals due" agrees with recorded_at (code review M1).
        Instant databaseNow = VitalsRules.canRead(role) && admitted ? vitals.databaseNow() : null;
        Boolean vitalsDue = VitalsRules.vitalsDue(role, admitted,
                latestVitals == null ? null : latestVitals.recordedAt(), databaseNow);
        return new Detail(s.id(), s.ipNumber(), s.patientId(), s.patientName(), s.age(), s.sex(), s.doctorName(),
                s.bedCode(), s.categoryName(), s.wardName(), s.admissionType(), s.payer(), s.dayNumber(),
                s.depositPaise(), s.minimumDepositPaise(), s.depositState(), s.admittedAt(),
                patient != null && MOBILE_ROLES.contains(role) ? patient.mobile() : null, row.reason(),
                List.copyOf(deposits), canCollect, s.runningBillPaise(), admitted && s.depositLow(), bill, canAddCharge,
                canTransfer, catalog.transferRules(tenantId), goingHome, row.status(), row.dischargedAt(),
                canStartDischarge, null, null, VitalsRules.canRead(role), vitalsDue,
                VitalsRules.summary(latestVitals), canOpenEmr(tenantId, actor, role, admitted),
                AdmissionViews.AdmissionPackage.of(row.packageCopy()));
    }

    /**
     * SAH-31 (AC1, OQ-31-7): the EMR button. The role in the token and the role read again from the active membership
     * are both OWNER, DOCTOR or NURSE, and the admission is ADMITTED. IPD is Trial, On or Read-only here (Off never
     * reaches an admission sheet), so the state adds nothing. False for a discharged admission.
     */
    private boolean canOpenEmr(UUID tenantId, UUID actor, String role, boolean admitted) {
        if (!admitted || role == null || !MedicineRules.EMR_ROLES.contains(role)) {
            return false;
        }
        return team.findActiveByUserId(tenantId, actor).filter(TeamMember::active).map(TeamMember::role)
                .filter(MedicineRules.EMR_ROLES::contains).isPresent();
    }

    /** SAH-31: the admitting doctor's name and the billed bed days, the numbers the EMR sheet shows. */
    record EmrHeader(String doctorName, int dayNumber) {
    }

    /**
     * SAH-31: the header numbers of the EMR sheet, computed exactly as the admission sheet computes them (one
     * number of billed bed days on every screen): from the frozen final bill once discharged, otherwise from the running
     * bill at the current time (or at the discharge time of an old discharged row).
     */
    EmrHeader emrHeader(UUID tenantId, AdmissionRow row) {
        String doctor = team.find(tenantId, row.doctorMembershipId()).map(TeamMember::name).orElse("Doctor");
        boolean admitted = "ADMITTED".equals(row.status());
        if (!admitted) {
            Optional<FinalBillRow> frozen = discharges.findFinalBill(tenantId, row.id());
            if (frozen.isPresent()) {
                return new EmrHeader(doctor, frozen.get().billDays());
            }
        }
        DepositRules rules = catalog.depositRules(tenantId);
        Instant now = admitted || row.dischargedAt() == null ? gate.now() : row.dischargedAt();
        RunningBill bill = billing.forOne(tenantId, row.id(), now, rules.alertPercent()).bill(row, doctor);
        return new EmrHeader(doctor, bill.billDays());
    }

    /**
     * SAH-27: a discharged admission, fixed at discharge. The bill is rebuilt from the stored final bill lines, never
     * from today's rates or clock; the day number is the frozen bill days; the category is the one billed at discharge.
     * Nothing can be changed on it, so every can-flag is false.
     */
    private Detail dischargedDetail(UUID tenantId, String role, AdmissionRow stored, PatientSummary patient,
                                   FinalBillRow bill, DischargeRow discharge) {
        List<RunningBill.BillLine> lines = discharges.findFinalBillLines(tenantId, bill.id());
        // The bed may have changed category since: the frozen category is the one billed.
        AdmissionRow row = new AdmissionRow(stored.id(), stored.ipNumber(), stored.patientId(), stored.bedId(),
                stored.bedCode(), bill.categoryKey(), stored.wardName(), stored.doctorMembershipId(),
                stored.admissionType(), stored.payer(), stored.reason(), stored.status(), stored.admittedAt(),
                bill.depositsPaise(), stored.dischargedAt(), stored.packageCopy());
        Lookups lookups = new Lookups();
        DepositRules rules = catalog.depositRules(tenantId);
        RunningBill frozen = new RunningBill(bill.billDays(), bill.bedDayCut(),
                IpdService.NAMES.get(bill.categoryKey()), bill.multiplier(), List.copyOf(lines), bill.totalPaise(),
                bill.depositsPaise(), RunningBill.depositPercent(bill.totalPaise(), bill.depositsPaise()),
                rules.alertPercent());
        Summary s = summary(tenantId, row, patient, rules, lookups, frozen, false);
        // A discharged patient is never "low on deposit": the bill is settled.
        List<DepositEntry> deposits = new ArrayList<>();
        for (DepositRow d : admissions.findDeposits(tenantId, row.id())) {
            deposits.add(new DepositEntry(d.id(), d.amountPaise(), d.mode(), d.collectedAt(),
                    lookups.collector(tenantId, d.collectedBy())));
        }
        DischargeViews.DischargeRecord record = new DischargeViews.DischargeRecord(discharge.type(),
                discharge.templateKey(), lookups.collector(tenantId, discharge.signedBy()), discharge.signedAt(),
                lookups.collector(tenantId, discharge.completedBy()), stored.dischargedAt(),
                discharge.followUpDays(), discharge.followUpDue());
        DischargeViews.FinalBill finalBill = new DischargeViews.FinalBill(List.copyOf(lines), bill.billDays(),
                bill.totalPaise(), bill.depositsPaise(), bill.balancePaise(), DischargeRules.outcome(bill.balancePaise()),
                bill.settleMode(), lookups.collector(tenantId, bill.createdBy()), bill.createdAt());
        // SAH-30: never "due" once discharged; the latest score is the last record before the discharge.
        VitalsRepository.VitalsRow latestVitals = VitalsRules.canRead(role)
                ? vitals.findLatest(tenantId, row.id()).orElse(null) : null;
        return new Detail(s.id(), s.ipNumber(), s.patientId(), s.patientName(), s.age(), s.sex(), s.doctorName(),
                s.bedCode(), s.categoryName(), s.wardName(), s.admissionType(), s.payer(), bill.billDays(),
                s.depositPaise(), s.minimumDepositPaise(), s.depositState(), s.admittedAt(),
                patient != null && MOBILE_ROLES.contains(role) ? patient.mobile() : null, row.reason(),
                List.copyOf(deposits), false, bill.totalPaise(), false, frozen, false, false,
                catalog.transferRules(tenantId), false, "DISCHARGED", stored.dischargedAt(), false, record,
                finalBill, VitalsRules.canRead(role), VitalsRules.canRead(role) ? Boolean.FALSE : null,
                VitalsRules.summary(latestVitals), false,
                AdmissionViews.AdmissionPackage.of(row.packageCopy()));
    }

    /** Required ids and choices; returns the reason to store (stripped, or the default when blank). */
    private static String validateShape(AdmissionInput input) {
        if (input.patientId() == null) {
            throw new InvalidInputException("patientId", "Choose the patient");
        }
        if (input.doctorMembershipId() == null) {
            throw new InvalidInputException("doctorMembershipId", "Choose the admitting doctor");
        }
        if (input.admissionType() == null || !TYPES.contains(input.admissionType())) {
            throw new InvalidInputException("admissionType", "Choose Planned, Emergency, Day-care or Maternity");
        }
        if (input.bedId() == null) {
            throw new InvalidInputException("bedId", "Choose a bed");
        }
        if (input.payer() == null || !PAYERS.contains(input.payer())) {
            throw new InvalidInputException("payer", "Choose Self, Insurance or Corporate");
        }
        String raw = input.reason() == null ? "" : IpdText.nfc(input.reason());
        // The NFC text is checked first: stripping must not hide a control or text-direction character at an end.
        if (IpdText.hasControlOrBidi(raw)) {
            throw new InvalidInputException("reason", "Reason cannot contain control or text-direction characters");
        }
        String reason = IpdText.strip(raw);
        if (reason.length() > REASON_MAX) {
            throw new InvalidInputException("reason", "Reason can be at most 200 characters");
        }
        return reason.isEmpty() ? DEFAULT_REASON : reason;
    }

    private static void validateDepositShape(Long depositPaise, String mode) {
        if (depositPaise == null || depositPaise == 0) {
            return;
        }
        if (depositPaise < 0 || depositPaise > MAX_PAISE || depositPaise % RUPEE != 0) {
            throw new InvalidInputException("depositPaise", "Enter whole rupees up to ₹10,00,000");
        }
        if (mode == null || !MODES.contains(mode)) {
            throw new InvalidInputException("depositMode", "Choose how the deposit was paid");
        }
    }

    private static DepositRules validate(RulesInput input, boolean confirmed) {
        if (input == null || input.depositPaise() == null || input.categoryAddPaise() == null
                || input.emergencyWithoutDeposit() == null || input.alertPercent() == null) {
            throw new InvalidInputException("depositPaise", "Fill in every deposit rule");
        }
        DepositAmounts d = input.depositPaise();
        CategoryAdds c = input.categoryAddPaise();
        int alert = input.alertPercent();
        if (alert < 50 || alert > 100 || alert % 5 != 0) {
            throw new InvalidInputException("alertPercent", "Choose a deposit alert from 50% to 100%, in steps of 5");
        }
        return new DepositRules(amount("depositPaise.planned", d.planned()), amount("depositPaise.emergency", d.emergency()),
                amount("depositPaise.daycare", d.daycare()), amount("depositPaise.maternity", d.maternity()),
                amount("categoryAddPaise.gen", c.gen()), amount("categoryAddPaise.semi", c.semi()),
                amount("categoryAddPaise.pvt", c.pvt()), amount("categoryAddPaise.icu", c.icu()),
                input.emergencyWithoutDeposit(), alert, confirmed);
    }

    private static long amount(String field, Long paise) {
        if (paise == null || paise < 0 || paise > MAX_PAISE) {
            throw new InvalidInputException(field, "Enter an amount from ₹0 to ₹10,00,000");
        }
        if (paise % RUPEE != 0) {
            throw new InvalidInputException(field, "Enter whole rupees");
        }
        return paise;
    }

    private static DepositRules withConfirmed(DepositRules r) {
        return new DepositRules(r.planned(), r.emergency(), r.daycare(), r.maternity(), r.catGen(), r.catSemi(),
                r.catPvt(), r.catIcu(), r.emergencyWithoutDeposit(), r.alertPercent(), true);
    }

    private static Rules toRules(DepositRules r) {
        return new Rules(new DepositAmounts(r.planned(), r.emergency(), r.daycare(), r.maternity()),
                new CategoryAdds(r.catGen(), r.catSemi(), r.catPvt(), r.catIcu()), r.emergencyWithoutDeposit(),
                r.alertPercent(), r.confirmed());
    }

    private static Map<String, Object> snapshot(DepositRules r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plannedPaise", r.planned());
        m.put("emergencyPaise", r.emergency());
        m.put("daycarePaise", r.daycare());
        m.put("maternityPaise", r.maternity());
        m.put("genAddPaise", r.catGen());
        m.put("semiAddPaise", r.catSemi());
        m.put("pvtAddPaise", r.catPvt());
        m.put("icuAddPaise", r.catIcu());
        m.put("emergencyWithoutDeposit", r.emergencyWithoutDeposit());
        m.put("alertPercent", r.alertPercent());
        m.put("confirmed", r.confirmed());
        return m;
    }

    private static boolean isIpNumberClash(DuplicateKeyException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(IP_NUMBER_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private static ConflictException alreadyAdmitted() {
        return new ConflictException("already-admitted", "Already admitted", "This patient is already admitted.");
    }

    /** Financial year April to March in Asia/Kolkata, as {@code 26-27}. */
    static String financialYear(Instant at) {
        ZonedDateTime z = at.atZone(RunningBill.IST);
        int start = z.getMonthValue() >= 4 ? z.getYear() : z.getYear() - 1;
        return String.format("%02d-%02d", start % 100, (start + 1) % 100);
    }

    /** Rupees with Indian digit grouping, for example {@code ₹1,25,000}. */
    static String rupees(long paise) {
        String digits = Long.toString(paise / RUPEE);
        if (digits.length() <= 3) {
            return "₹" + digits;
        }
        String result = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        while (rest.length() > 2) {
            result = rest.substring(rest.length() - 2) + "," + result;
            rest = rest.substring(0, rest.length() - 2);
        }
        if (!rest.isEmpty()) {
            result = rest + "," + result;
        }
        return "₹" + result;
    }
}
