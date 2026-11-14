package com.sahaj.opd.application.emr.finish;

import com.sahaj.opd.application.emr.EmrProblemException;
import com.sahaj.opd.application.emr.EmrRecordService;
import com.sahaj.opd.application.emr.EmrRecordService.Authorized;
import com.sahaj.opd.application.emr.finish.EmrFinishRepository.RecordState;
import com.sahaj.opd.application.emr.finish.FinishModels.EditHold;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.api.ClinicUser;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-51-4 (ADR-025 D4): one doctor edits a record at a time. The hold is a two-minute lease on the record row, taken or
 * renewed by every draft save, by the 30-second tick and by "Edit this visit"; released when the doctor leaves the EMR and by
 * the sign. An owner with doctor details may take over another doctor's active hold (audited, ids only). Taking and
 * renewing write no audit entry. The holder's name is a staff name: on screen only, never logged.
 */
@Service
public class EmrEditHoldService {

    static final int HOLD_SECONDS = 120;
    private static final String ENTITY = "EMR_RECORD";

    private final EmrRecordService records;
    private final EmrFinishRepository finish;
    private final TeamMemberPort teamMembers;
    private final AuditLog auditLog;

    public EmrEditHoldService(EmrRecordService records, EmrFinishRepository finish, TeamMemberPort teamMembers,
                              AuditLog auditLog) {
        this.records = records;
        this.finish = finish;
        this.teamMembers = teamMembers;
        this.auditLog = auditLog;
    }

    /**
     * Takes or renews the hold. {@code takeOver} is for an OWNER, or the admitting doctor of an IPD round (DEC-048 H35); 403
     * for anyone else. The record row is locked first, so the holder read for the answer and the audit entry is the one the
     * take then replaces, and a record signed meanwhile is 409 emr-record-signed (never "another doctor is editing").
     */
    @Transactional
    public EditHold take(ClinicUser user, UUID recordId, boolean takeOver) {
        Authorized authorized = records.authorize(user, recordId);
        TeamMember member = authorized.member();
        UUID tenantId = user.tenantId();
        if (takeOver && !authorized.mayTakeOverHold()) {
            throw new AccessDeniedException("Only the owner or the admitting doctor can take over an edit.");
        }
        RecordState before = finish.stateForUpdate(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        if ("SIGNED".equals(before.status())) {
            throw EmrProblemException.emrRecordSigned();
        }
        UUID previous = before.holdActive() ? before.holdMembershipId() : null;
        if (!finish.takeHold(tenantId, recordId, member.membershipId(), user.userId(), HOLD_SECONDS, takeOver)) {
            throw EmrProblemException.emrRecordHeld("Dr. " + nameOf(tenantId, before.holdMembershipId()));
        }
        if (takeOver && previous != null && !previous.equals(member.membershipId())) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("previousHolderMembershipId", previous);
            auditLog.record("EMR_EDIT_HOLD_TAKEN_OVER", ENTITY, recordId, null, after);
        }
        RecordState now = finish.state(tenantId, recordId).orElseThrow(EmrProblemException::recordNotFound);
        return new EditHold(member.membershipId(), nameOf(tenantId, member.membershipId()), now.holdExpiresAt(), true,
                false);
    }

    /** Releases the caller's own hold; always succeeds for an accessible record (expiry covers a closed tab). */
    @Transactional
    public void release(ClinicUser user, UUID recordId) {
        Authorized authorized = records.authorize(user, recordId);
        finish.releaseHold(user.tenantId(), recordId, authorized.member().membershipId());
    }

    private String nameOf(UUID tenantId, UUID membershipId) {
        return membershipId == null ? "Doctor" : teamMembers.find(tenantId, membershipId).map(TeamMember::name)
                .orElse("Doctor");
    }
}
