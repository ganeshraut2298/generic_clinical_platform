package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.AdmissionViews.Detail;
import com.sahaj.ipd.application.BedRepository.BedRow;
import com.sahaj.ipd.application.DischargeRepository.NewDischarge;
import com.sahaj.ipd.application.DischargeRepository.NewFinalBill;
import com.sahaj.ipd.application.DischargeRepository.SignoffRow;
import com.sahaj.ipd.application.DischargeViews.DischargeInput;
import com.sahaj.ipd.application.DischargeViews.OpdFollowUpRule;
import com.sahaj.ipd.application.DischargeViews.Preview;
import com.sahaj.ipd.application.DischargeViews.Settings;
import com.sahaj.ipd.application.DischargeViews.SettingsInput;
import com.sahaj.ipd.application.DischargeViews.Sheet;
import com.sahaj.ipd.application.DischargeViews.SheetBill;
import com.sahaj.ipd.application.DischargeViews.Signature;
import com.sahaj.ipd.application.DischargeViews.SignoffInput;
import com.sahaj.ipd.application.EscalationRepository.EscalationRow;
import com.sahaj.ipd.application.IpdSettingsRepository.DepositRules;
import com.sahaj.ipd.application.IpdSettingsRepository.DischargeSettings;
import com.sahaj.ipd.domain.EscalationClock;
import com.sahaj.opd.api.FollowUpPort;
import com.sahaj.opd.api.FollowUpPort.DischargeFollowUp;
import com.sahaj.opd.api.FollowUpPort.FeeRule;
import com.sahaj.patient.api.PatientAccessPort;
import com.sahaj.patient.api.PatientLookupPort;
import com.sahaj.patient.api.PatientLookupPort.PatientSummary;
import com.sahaj.platform.api.ActorDirectoryPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.ForbiddenException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-27 discharge: Discharge setup, the sheet, the doctor's sign-off and the discharge itself. Every call first
 * re-checks the role (defence in depth) and then the IPD state. The sign-off locks the admission row; the discharge
 * takes the clinic's IPD advisory lock first (the one every IPD write uses), then locks the bed and the admission
 * (the order of a bed transfer), so a deposit, a charge, a transfer, a sign-off and a second discharge cannot
 * interleave with it. The amount collected or refunded is always worked out here from the running bill and the
 * deposits, never taken from the request. Audit rows carry ids, codes, types and amounts only: never the patient's
 * name, mobile or the reason for admission.
 */
@Service
public class DischargeService {

    private static final Set<String> MODES = Set.of("Cash", "UPI", "Card");
    private static final long MAX_EXPECTED_PAISE = 10_000_000_000L;
    static final String ESCALATION_OPEN_MESSAGE = "This patient has an early warning escalation that is not closed. "
            + "A doctor must record the response first.";
    static final String ESCALATION_AFTER_SIGNOFF_MESSAGE = "This patient has an early warning escalation that "
            + "started after the doctor signed the summary. A doctor must record the response or sign the summary "
            + "again.";

    private final AdmissionRepository admissions;
    private final BedRepository beds;
    private final WardRepository wards;
    private final IpdSettingsRepository settings;
    private final DischargeRepository discharges;
    private final OwnerGuard ownerGuard;
    private final AuditLog auditLog;
    private final TeamMemberPort team;
    private final PatientLookupPort patients;
    private final PatientAccessPort patientAccess;
    private final ActorDirectoryPort actors;
    private final FollowUpPort followUps;
    private final IpdGate gate;
    private final IpdCatalog catalog;
    private final IpdBilling billing;
    private final AdmissionService sheets;
    private final EscalationRepository escalations;
    private final ClinicalEventRepository events;
    private final ScopeGuard guard;

    DischargeService(AdmissionRepository admissions, BedRepository beds, WardRepository wards,
                     IpdSettingsRepository settings, DischargeRepository discharges, OwnerGuard ownerGuard,
                     AuditLog auditLog, TeamMemberPort team, PatientLookupPort patients,
                     PatientAccessPort patientAccess, ActorDirectoryPort actors, FollowUpPort followUps, IpdGate gate,
                     IpdCatalog catalog, IpdBilling billing, AdmissionService sheets, EscalationRepository escalations,
                     ClinicalEventRepository events, ScopeGuard guard) {
        this.guard = guard;
        this.admissions = admissions;
        this.beds = beds;
        this.wards = wards;
        this.settings = settings;
        this.discharges = discharges;
        this.ownerGuard = ownerGuard;
        this.auditLog = auditLog;
        this.team = team;
        this.patients = patients;
        this.patientAccess = patientAccess;
        this.actors = actors;
        this.followUps = followUps;
        this.gate = gate;
        this.catalog = catalog;
        this.billing = billing;
        this.sheets = sheets;
        this.escalations = escalations;
        this.events = events;
    }

    // ---- Discharge setup ----

    @Transactional(readOnly = true)
    public Settings settings(UUID tenantId, String role) {
        requireRole(role, DischargeRules.READ_ROLES, "You cannot open Discharge setup.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        boolean canEdit = "OWNER".equals(role) && !"readonly".equals(ent.ipdState());
        return toSettings(tenantId, catalog.dischargeSettings(tenantId), canEdit);
    }

    /**
     * Check order of the contract: module Off, role, body, Read-only. An unchanged setting writes and audits
     * nothing, as with the transfer rules.
     */
    @Transactional
    public Settings updateSettings(UUID tenantId, UUID actor, SettingsInput input) {
        ownerGuard.requireOwner(tenantId, actor);
        ModuleEntitlement ent = gate.check(tenantId, false);
        DischargeSettings next = validate(input);
        gate.requireWritable(ent);
        wards.lockTenant(tenantId);
        DischargeSettings current = catalog.dischargeSettings(tenantId);
        if (!next.equals(current)) {
            settings.upsertDischargeSettings(tenantId, next, actor, gate.now());
            auditLog.record("IPD_DISCHARGE_SETTINGS_UPDATED", "IPD_CONFIG", null, snapshot(current), snapshot(next));
        }
        return toSettings(tenantId, next, true);
    }

    // ---- the sheet and the sign-off ----

    /**
     * Opening the sheet changes nothing but the access log (security review M3): the answer carries the patient's name
     * and the diagnosis, so the open is recorded after the 404 and the not-admitted checks; a refused call writes
     * nothing.
     */
    @Transactional
    public Sheet sheet(UUID tenantId, UUID actor, String role, UUID admissionId) {
        requireRole(role, DischargeRules.READ_ROLES, "You cannot open the discharge sheet.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        AdmissionRow row = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        // SAH-34: after the 404, before the not-admitted check and the record-access row.
        guard.requireAdmission(tenantId, actor, role, null, admissionId, "discharge-sheet");
        requireAdmitted(row, "This patient is not admitted any more.");
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        return buildSheet(tenantId, role, ent, row);
    }

    /**
     * The doctor's tick. The admission row is locked first so that a sign-off and a discharge never interleave. The
     * same type and template again writes nothing, unless the admission has an escalation that is not closed and the
     * sign-off is not later than the start of its response clock: then a new sign-off is appended.
     */
    @Transactional
    public Sheet signoff(UUID tenantId, UUID actor, String role, UUID admissionId, SignoffInput input) {
        requireRole(role, DischargeRules.SIGN_ROLES, "You cannot sign the discharge summary.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        String type = input == null ? null : input.type();
        String templateKey = input == null ? null : input.templateKey();
        requireTypeAndTemplate(type, templateKey);
        AdmissionRow row = admissions.findForUpdate(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        // SAH-34: under the admission lock, after the 404, before the not-admitted check.
        guard.requireAdmissionLocked(tenantId, actor, role, null, admissionId, "discharge-signoff");
        requireAdmitted(row, "This patient is not admitted any more. The summary cannot be signed.");
        gate.requireWritable(ent);
        requireActiveSigner(tenantId, actor);
        SignoffRow latest = discharges.findLatestSignoff(tenantId, admissionId).orElse(null);
        // The double-tap no-op does not apply when the sign-off is older than an escalation that is not closed: the
        // doctor who has seen it signs again (SAH-33 security review M1, ADR-007 R2). Read under the admission lock.
        EscalationRow notClosed = escalations.findOpen(tenantId, admissionId).orElse(null);
        if (!applies(latest, type, templateKey) || !covers(latest, notClosed)) {
            SignoffRow made = discharges.insertSignoff(tenantId, admissionId, type, templateKey, actor);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("admissionId", admissionId);
            after.put("type", type);
            after.put("templateKey", templateKey);
            auditLog.record("IPD_DISCHARGE_SIGNED", "IPD_DISCHARGE_SIGNOFF", made.id(), null, after);
        }
        // The answer carries the patient's name and the reason (diagnosis), so every call that answers with the
        // sheet is logged like an open, also a repeat of the same sign-off; a refused call has thrown above and
        // writes nothing (SAH-27 follow-up).
        patientAccess.recordOpened(tenantId, row.patientId(), actor);
        return buildSheet(tenantId, role, ent, row);
    }

    // ---- the discharge ----

    /**
     * Completes the discharge in one transaction. Check order (SAH-27 AC7): role, module Off, body, the membership
     * read again (403; SAH-33 R3), then the
     * admission (404), not admitted (409), Read-only (409), summary not signed (409), escalation open (409: Normal,
     * or a sign-off not later than the escalation for death, LAMA and referred; SAH-33 R2), bill changed (409), refund
     * by a non-owner (403, by the membership role), missing pay mode (400). Writes, in order: final bill, its lines,
     * the discharge, the admission, the escalation, the bed, and last the OPD follow-up (not for death).
     */
    @Transactional
    public Detail discharge(UUID tenantId, UUID actor, String role, UUID admissionId, DischargeInput input) {
        requireRole(role, DischargeRules.DISCHARGE_ROLES, "You cannot discharge a patient.");
        ModuleEntitlement ent = gate.check(tenantId, false);
        if (input == null) {
            throw new InvalidInputException("type", "Choose a discharge type");
        }
        String type = input.type();
        String templateKey = input.templateKey();
        requireTypeAndTemplate(type, templateKey);
        Long expectedTotalValue = input.expectedTotalPaise();
        if (expectedTotalValue == null || expectedTotalValue < 0 || expectedTotalValue > MAX_EXPECTED_PAISE) {
            throw new InvalidInputException("expectedTotalPaise", "Review the final bill and try again");
        }
        Long expectedDepositsValue = input.expectedDepositsPaise();
        if (expectedDepositsValue == null || expectedDepositsValue < 0 || expectedDepositsValue > MAX_EXPECTED_PAISE) {
            throw new InvalidInputException("expectedDepositsPaise", "Review the final bill and try again");
        }
        long expected = expectedTotalValue;
        long expectedDeposits = expectedDepositsValue;
        String mode = input.mode();
        if (mode != null && !MODES.contains(mode)) {
            throw new InvalidInputException("mode", "Choose UPI, Cash or Card");
        }

        // SAH-33 security review M2, ADR-007 R3: the role in the token can be 15 minutes old, so the membership is read
        // again before the advisory lock and before any write: active, and still the owner or front desk. The refund
        // check below uses this role, not the token's.
        String memberRole = team.findActiveByUserId(tenantId, actor)
                .filter(member -> member.active() && DischargeRules.DISCHARGE_ROLES.contains(member.role()))
                .map(TeamMember::role)
                .orElseThrow(() -> new AccessDeniedException("You cannot discharge a patient."));

        // The clinic IPD lock first (every IPD write takes it), so a deposit, a charge or a move cannot slip in
        // between the checks below and the writes; then the bed and the admission, as a transfer does.
        wards.lockTenant(tenantId);
        AdmissionRow found = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        // SAH-34: under the clinic IPD lock (a transfer takes it too), after the 404, before the not-admitted check.
        guard.requireAdmissionLocked(tenantId, actor, role, memberRole, admissionId, "discharge");
        requireAdmitted(found, "This patient is not admitted any more. The patient cannot be discharged.");
        BedRow bed = beds.findForUpdate(tenantId, found.bedId())
                .orElseThrow(() -> new IllegalStateException("The bed of the admission was not found"));
        AdmissionRow row = admissions.findForUpdate(tenantId, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found"));
        requireAdmitted(row, "This patient is not admitted any more. The patient cannot be discharged.");
        gate.requireWritable(ent);

        SignoffRow signoff = discharges.findLatestSignoff(tenantId, admissionId)
                .filter(s -> applies(s, type, templateKey))
                .orElseThrow(() -> new ConflictException("summary-not-signed", "Summary is not signed",
                        "The doctor signs the summary first"));

        // SAH-33 (ADR-007 D6, security review SAH-30 L4): the escalation is read here, after the admission row lock
        // above, so a vitals save that raises or re-opens one cannot race past this check. A Normal discharge is
        // refused while it is not closed; Death, LAMA and Referred close it below, in this transaction.
        EscalationRow openEscalation = escalations.findOpen(tenantId, admissionId).orElse(null);
        if (openEscalation != null && DischargeRules.NORMAL.equals(signoff.type())) {
            throw new ConflictException("escalation-open", "Escalation is not closed", ESCALATION_OPEN_MESSAGE);
        }
        // SAH-33 security review M1, ADR-007 R2: Death, LAMA and Referred close the escalation without a doctor's
        // response, so the doctor's sign-off must be strictly later than the start of its response clock.
        if (openEscalation != null && !EscalationClock.signoffCovers(signoff.signedAt(), openEscalation.clockStart())) {
            throw new ConflictException("escalation-open", "Escalation started after the sign-off",
                    ESCALATION_AFTER_SIGNOFF_MESSAGE);
        }

        Instant now = gate.now();
        DepositRules depositRules = catalog.depositRules(tenantId);
        RunningBill bill = billing.forOne(tenantId, admissionId, now, depositRules.alertPercent())
                .bill(row, doctorName(tenantId, row.doctorMembershipId()));
        long total = bill.totalPaise();
        long deposits = row.depositTotalPaise();
        // A deposit added after the sheet was opened moves the amount to collect or refund, not the total.
        if (expected != total || expectedDeposits != deposits) {
            throw new ConflictException("bill-changed", "The bill changed",
                    "The bill changed. Review the final bill and try again.");
        }
        long balance = total - deposits;
        if (balance < 0 && !"OWNER".equals(memberRole)) {
            // The role of the active membership read at the start decides, not the token's (the stricter view).
            throw new ForbiddenException("refund-needs-owner", "Refund needs the owner",
                    "Only the owner can refund a deposit.");
        }
        String settleMode = null;
        if (balance != 0) {
            if (mode == null) {
                throw new InvalidInputException("mode", "Choose UPI, Cash or Card");
            }
            settleMode = mode;
        }
        // The answer is the full admission sheet, so the access is logged like an open (security review L2).
        patientAccess.recordOpened(tenantId, row.patientId(), actor);

        DischargeSettings config = catalog.dischargeSettings(tenantId);
        boolean death = DischargeRules.DEATH.equals(signoff.type());
        LocalDate dueDate = death ? null : DischargeRules.istDate(now).plusDays(config.followUpDays());

        UUID billId = discharges.insertFinalBill(tenantId, new NewFinalBill(admissionId, total, deposits, balance,
                settleMode, actor, bill.billDays(), bill.bedDayCut(), row.categoryKey(), bill.multiplier()));
        discharges.insertFinalBillLines(tenantId, billId, bill.lines());
        // Type, template and signature are those of the sign-off row that was checked above.
        discharges.insertDischarge(tenantId, new NewDischarge(admissionId, signoff.id(), signoff.type(),
                signoff.templateKey(), signoff.signedBy(), signoff.signedAt(), config.followUpDays(), dueDate, actor));
        if (!admissions.markDischarged(tenantId, admissionId, now)) {
            throw new IllegalStateException("The admission could not be marked discharged");
        }
        if (openEscalation != null) {
            closeAtDischarge(tenantId, actor, openEscalation, signoff.type());
        }
        beds.updateStatus(tenantId, bed.id(), "clean");
        if (!death) {
            followUps.createForDischarge(tenantId, actor, new DischargeFollowUp(admissionId, row.patientId(),
                    row.doctorMembershipId(), dueDate, DischargeRules.followUpReason(row.reason())));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", admissionId);
        after.put("ipNumber", row.ipNumber());
        after.put("type", signoff.type());
        after.put("totalPaise", total);
        after.put("balancePaise", balance);
        after.put("mode", settleMode);
        after.put("bedCode", bed.code());
        auditLog.record("IPD_DISCHARGED", "IPD_ADMISSION", admissionId, null, after);

        AdmissionRow updated = admissions.find(tenantId, admissionId)
                .orElseThrow(() -> new IllegalStateException("The admission was not found"));
        return sheets.sheet(tenantId, actor, role, ent, updated);
    }

    /**
     * SAH-33 D6: the escalation of a Death, LAMA or Referred discharge becomes DONE as "Closed at discharge", closed by
     * the discharging user, with no plan and the acknowledgement kept. It runs after the admission is marked
     * DISCHARGED (the database guard needs that). The event says plainly that no doctor response was recorded; the
     * audit row carries ids, score, risk, the status before and the discharge type, never a name or text.
     */
    private void closeAtDischarge(UUID tenantId, UUID actor, EscalationRow escalation, String dischargeType) {
        if (!escalations.closeAtDischarge(tenantId, escalation.id(), actor)) {
            throw new IllegalStateException("The escalation could not be closed at discharge");
        }
        events.insert(tenantId, escalation.admissionId(), ClinicalEventRepository.KIND_ESC,
                VitalsRules.closedAtDischargeText(DischargeRules.typeLabelLower(dischargeType)), actor);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("escalationId", escalation.id());
        after.put("admissionId", escalation.admissionId());
        after.put("ewsScore", escalation.ewsScore());
        after.put("ewsRisk", escalation.ewsRisk().name());
        after.put("statusBefore", escalation.status());
        after.put("dischargeType", dischargeType);
        auditLog.record("IPD_ESCALATION_CLOSED_AT_DISCHARGE", "IPD_EWS_ESCALATION", escalation.id(), null, after);
    }

    // ---- helpers ----

    private Sheet buildSheet(UUID tenantId, String role, ModuleEntitlement ent, AdmissionRow row) {
        Instant now = gate.now();
        DischargeSettings config = catalog.dischargeSettings(tenantId);
        DepositRules depositRules = catalog.depositRules(tenantId);
        TeamMember doctor = team.find(tenantId, row.doctorMembershipId()).orElse(null);
        String doctorName = doctor == null ? "Doctor" : doctor.name();
        RunningBill bill = billing.forOne(tenantId, row.id(), now, depositRules.alertPercent()).bill(row, doctorName);
        PatientSummary patient = patients.find(tenantId, row.patientId()).orElse(null);
        SignoffRow latest = discharges.findLatestSignoff(tenantId, row.id()).orElse(null);
        EscalationRow notClosed = escalations.findOpen(tenantId, row.id()).orElse(null);
        Signature signature = latest == null ? null : new Signature(latest.type(), latest.templateKey(),
                actors.displayName(tenantId, latest.signedBy()).orElse("Team member"), latest.signedAt());
        boolean writable = !"readonly".equals(ent.ipdState());
        long deposits = row.depositTotalPaise();
        Preview preview = new Preview(row.reason(), bill.billDays(), row.bedCode(), doctorName,
                BedBoardService.shortName(doctorName), team.doctorSpecialty(tenantId, row.doctorMembershipId())
                        .orElse(null));
        return new Sheet(row.id(), row.ipNumber(), patient == null ? "Patient" : patient.name(), row.bedCode(),
                bill.billDays(), config.approvalChain(), DischargeRules.TYPES, DischargeRules.TEMPLATES,
                DischargeRules.NORMAL, DischargeRules.defaultTemplate(config.templateKey(), row.admissionType(),
                        row.packageCopy() != null),
                signature, DischargeRules.SIGN_ROLES.contains(role) && writable,
                DischargeRules.DISCHARGE_ROLES.contains(role) && writable, "OWNER".equals(role) && writable,
                preview, new SheetBill(bill.totalPaise(), deposits, bill.totalPaise() - deposits),
                config.followUpDays(), DischargeRules.istDate(now).plusDays(config.followUpDays()),
                notClosed != null, notClosed != null && latest != null && !covers(latest, notClosed));
    }

    private Settings toSettings(UUID tenantId, DischargeSettings value, boolean canEdit) {
        FeeRule rule = followUps.feeRule(tenantId);
        return new Settings(value.templateKey(), value.approvalChain(), value.followUpDays(), DischargeRules.TYPES,
                DischargeRules.TEMPLATES, new OpdFollowUpRule(rule.days(), rule.fee()), canEdit);
    }

    private static DischargeSettings validate(SettingsInput input) {
        String template = input == null ? null : input.templateKey();
        if (template != null && !DischargeRules.TEMPLATE_KEYS.contains(template)) {
            throw new InvalidInputException("templateKey", "Choose a template from the list");
        }
        if (input == null || input.approvalChain() == null) {
            throw new InvalidInputException("approvalChain", "Choose on or off");
        }
        Integer days = input.followUpDays();
        if (days == null || days < 1 || days > 30) {
            throw new InvalidInputException("followUpDays", "Enter 1 to 30 days");
        }
        return new DischargeSettings(template, input.approvalChain(), days);
    }

    private static void requireTypeAndTemplate(String type, String templateKey) {
        if (type == null || !DischargeRules.TYPE_KEYS.contains(type)) {
            throw new InvalidInputException("type", "Choose a discharge type");
        }
        if (templateKey == null || !DischargeRules.TEMPLATE_KEYS.contains(templateKey)) {
            throw new InvalidInputException("templateKey", "Choose a summary template");
        }
    }

    /**
     * True when there is nothing for the sign-off to cover (no escalation that is not closed) or the sign-off is
     * strictly later than the start of the escalation's response clock (ADR-007 R2).
     */
    private static boolean covers(SignoffRow signoff, EscalationRow escalation) {
        return escalation == null || (signoff != null
                && EscalationClock.signoffCovers(signoff.signedAt(), escalation.clockStart()));
    }

    /** The signature applies to a discharge only when its type and template are the ones chosen. */
    private static boolean applies(SignoffRow signoff, String type, String templateKey) {
        return signoff != null && signoff.type().equals(type) && signoff.templateKey().equals(templateKey);
    }

    /**
     * Security review L1: the role in the token can be up to 15 minutes old, so the caller's membership is read again
     * before a sign-off: it must still be active and still a DOCTOR or the OWNER. (OQ-27-1: an owner without a doctor
     * profile may still sign for now; the human decides.)
     */
    private void requireActiveSigner(UUID tenantId, UUID actor) {
        boolean allowed = team.findActiveByUserId(tenantId, actor)
                .filter(member -> member.active() && DischargeRules.SIGN_ROLES.contains(member.role()))
                .isPresent();
        if (!allowed) {
            throw new AccessDeniedException("You cannot sign the discharge summary.");
        }
    }

    private static void requireRole(String role, Set<String> allowed, String message) {
        if (role == null || !allowed.contains(role)) {
            throw new AccessDeniedException(message);
        }
    }

    private static void requireAdmitted(AdmissionRow row, String message) {
        if (!"ADMITTED".equals(row.status())) {
            throw new ConflictException("not-admitted", "Patient is not admitted", message);
        }
    }

    private String doctorName(UUID tenantId, UUID membershipId) {
        return team.find(tenantId, membershipId).map(TeamMember::name).orElse("Doctor");
    }

    private static Map<String, Object> snapshot(DischargeSettings value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templateKey", value.templateKey());
        m.put("approvalChain", value.approvalChain());
        m.put("followUpDays", value.followUpDays());
        return m;
    }
}
