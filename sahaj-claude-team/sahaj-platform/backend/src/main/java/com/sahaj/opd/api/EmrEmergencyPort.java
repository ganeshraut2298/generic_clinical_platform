package com.sahaj.opd.api;

import java.util.List;
import java.util.UUID;

/**
 * SAH-67-2, SAH-67-4 (ADR-025 D9, D11): how the EMR reaches an Emergency case (SAH-28) without {@code opd} depending on
 * {@code ipd}. Implemented by {@code ipd}, which runs its own release and refer code paths. Every method runs in the
 * caller's transaction. Lock order (ADR-025 D1 step 1): {@link #lockCase} takes the clinic IPD advisory lock and then
 * the case row lock, before any opd row is locked.
 */
public interface EmrEmergencyPort {

    enum CloseKind { RELEASE, REFER, SENT_TO_OPD }

    /** Status of the locked case, WAITING or SEEN when it can still be closed. */
    record CaseLock(UUID caseId, String status, boolean open, int triageLevel) {
    }

    record CloseOutcome(boolean closed) {
    }

    record TriageLevel(int level, String name, String description, String tone) {
    }

    /** {@code clinicDefault} is true when the clinic has no Emergency settings (the SAH-28 default 3-colour scale). */
    record TriageScale(int scale, boolean clinicDefault, List<TriageLevel> levels) {
    }

    /** Locks the case; another clinic's id is a NotFoundException. Never throws for a closed case: read {@code open}. */
    CaseLock lockCase(UUID tenantId, UUID caseId);

    /**
     * RELEASE and REFER are the SAH-28 "Treat and release" and "Refer out" updates. SENT_TO_OPD needs {@code patientId} and
     * {@code opdVisitId}. The case must be open (call {@link #lockCase} first); a case that is not open closes nothing.
     */
    CloseOutcome close(UUID tenantId, UUID actorUserId, UUID caseId, CloseKind kind, UUID patientId,
                       UUID opdVisitId);

    /** The clinic's scale, or the SAH-28 default when it has none (also with IPD off). Levels most urgent first. */
    TriageScale triageScale(UUID tenantId);
}
