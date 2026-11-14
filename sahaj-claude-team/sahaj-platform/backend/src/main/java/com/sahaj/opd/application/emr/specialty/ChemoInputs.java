package com.sahaj.opd.application.emr.specialty;

import java.util.List;
import java.util.UUID;

/**
 * The request bodies of the chemotherapy order (docs/api/opd.yaml 0.5.0, SAH-66). Each body is closed (the strict JSON
 * module refuses an unknown field). Fields are nullable so that a missing field is answered with a field error. The PIN is
 * only ever held in {@link PinInput}, whose {@code toString} hides it; it is never stored, logged, audited or echoed.
 */
public final class ChemoInputs {

    private ChemoInputs() {
    }

    /** One edited final dose. */
    public record FinalDose(String drugKey, Double finalMg) {
    }

    /** PUT /emr/records/{id}/chemo-order. */
    public record OrderInput(Long revision, String protocolKey, Integer levelPct, String reductionReasonKey,
                             String reductionReasonText, List<FinalDose> finalDoses, List<String> premedsNotGiven,
                             Boolean allergyConfirmed, Boolean consentNewSigned, String scheme,
                             Double priorOutsideDoxorubicinMgM2) {
    }

    /** PUT /emr/records/{id}/chemo-order/checker. */
    public record CheckerInput(Long revision, UUID checkerMembershipId) {
    }

    /** POST /emr/records/{id}/chemo-order/second-check (way b, the checker's own colleague PIN). */
    public record PinInput(Long orderRevision, String fingerprint, UUID checkerMembershipId, String pin) {

        /** Never shows the PIN. */
        @Override
        public String toString() {
            return "PinInput[orderRevision=" + orderRevision + "]";
        }
    }

    /** POST /emr/records/{id}/chemo-order/send. */
    public record ActionInput(Long revision, String fingerprint) {
    }

    /** POST /emr/records/{id}/chemo-order/hold. */
    public record HoldInput(Long revision, String reasonText) {
    }

    /** POST /emr/chemo-checks/{orderId}/confirm (way a, the checker's own sign-in). */
    public record ConfirmInput(String fingerprint) {
    }
}
