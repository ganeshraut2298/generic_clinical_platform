package com.sahaj.ipd.application;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.RoundNoteRepository.NewRound;
import com.sahaj.opd.api.EmrRoundPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-67-1 (ADR-025 D8): implements {@link EmrRoundPort}. When a doctor signs the day's progress note the admission gets one
 * {@code round_note} for the day (a repeat is success) and one clinical event "Signed the daily progress note". The only
 * clinical text received is "investigations advised", kept for the nursing sheet; no lab order is made. The audit
 * IPD_ROUND_SIGNED holds ids and the order count only. A discharged admission is refused (NOT_ADMITTED) and nothing is written.
 */
@Component
class EmrRoundAdapter implements EmrRoundPort {

    static final String EVENT_TEXT = "Signed the daily progress note";
    private static final int MAX_INVESTIGATIONS = 1000;

    private final AdmissionRepository admissions;
    private final RoundNoteRepository rounds;
    private final ClinicalEventRepository events;
    private final TeamMemberPort team;
    private final AuditLog auditLog;
    private final IpdGate gate;

    EmrRoundAdapter(AdmissionRepository admissions, RoundNoteRepository rounds, ClinicalEventRepository events,
                    TeamMemberPort team, AuditLog auditLog, IpdGate gate) {
        this.gate = gate;
        this.admissions = admissions;
        this.rounds = rounds;
        this.events = events;
        this.team = team;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RoundOutcome recordSignedRound(UUID tenantId, UUID actorUserId, RoundNote note) {
        // a write like every other: refused (409 ipd-readonly) while IPD is Read-only; the EMR sign checks it first (L2)
        gate.check(tenantId, true);
        Optional<AdmissionRow> admission = admissions.findForNoKeyUpdate(tenantId, note.admissionId());
        if (admission.isEmpty() || !"ADMITTED".equals(admission.get().status())) {
            return RoundOutcome.NOT_ADMITTED;
        }
        TeamMember member = team.findActiveByUserId(tenantId, actorUserId)
                .orElseThrow(() -> new IllegalStateException("The signer is not an active member"));
        String investigations = note.investigationsAdvised();
        if (investigations != null && investigations.length() > MAX_INVESTIGATIONS) {
            investigations = investigations.substring(0, MAX_INVESTIGATIONS);
        }
        boolean inserted = rounds.insertIfAbsent(tenantId, new NewRound(note.admissionId(), note.roundDate(),
                note.recordId(), actorUserId, member.membershipId(), note.signedAt(), note.ordersPlaced(),
                investigations == null || investigations.isBlank() ? null : investigations));
        if (!inserted) {
            return RoundOutcome.ALREADY_RECORDED;
        }
        events.insert(tenantId, note.admissionId(), ClinicalEventRepository.KIND_INFO, EVENT_TEXT, actorUserId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("admissionId", note.admissionId());
        after.put("roundDate", note.roundDate().toString());
        after.put("recordId", note.recordId());
        after.put("ordersPlaced", note.ordersPlaced());
        auditLog.record("IPD_ROUND_SIGNED", "IPD_ADMISSION", note.admissionId(), null, after);
        return RoundOutcome.RECORDED;
    }
}
