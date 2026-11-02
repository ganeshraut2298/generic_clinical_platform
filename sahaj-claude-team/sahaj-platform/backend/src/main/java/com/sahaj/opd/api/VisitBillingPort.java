package com.sahaj.opd.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@code billing} needs from a visit to run the billing counter (SAH-10): the patient id, the
 * doctor's name, the visit type, the current stage, the token number and the consultation fee -
 * the always-derived first line of a bill. {@code billing} never joins {@code opd.visit} directly,
 * the same module-boundary rule {@code opd} itself follows for {@code patient.patient} and
 * {@code platform.membership}. Implemented by {@code opd}; runs in the caller's transaction (tenant
 * already bound).
 */
public interface VisitBillingPort {

    record VisitForBilling(UUID id, UUID patientId, String doctorName, String visitType, String stage, int tokenNo,
                            long feePaise) {
    }

    /** Another clinic's visit id, or any id at all, is simply absent. */
    Optional<VisitForBilling> find(UUID tenantId, UUID visitId);

    /** Every visit created today (Asia/Kolkata), any stage - the billing counter's board (SAH-10). */
    List<VisitForBilling> today(UUID tenantId);

    /**
     * Moves a visit from {@code BILLING} to {@code DONE} once its balance reaches zero (SAH-10 AC4).
     * {@code actorRole} must be {@code OWNER} or {@code FRONT_DESK}, the same rule {@code VisitService}
     * uses for a forward move by either of those roles; anyone else throws
     * {@link org.springframework.security.access.AccessDeniedException} (403). A visit not currently
     * in {@code BILLING} throws {@code InvalidInputException} (400); an unknown visit throws
     * {@code NotFoundException} (404).
     */
    void advanceToDone(UUID tenantId, UUID actorUserId, String actorRole, UUID visitId);
}
