package com.sahaj.opd.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-67-1 (ADR-025 D8): how the EMR round of {@code opd} reaches ipd's own medicine order path (SAH-31) without
 * {@code opd} depending on {@code ipd}. Declared here, implemented by {@code ipd}, which delegates to its order service:
 * the same catalogue, safety check, audit, clinical events and pharmacist check. {@code opd} holds no medicine-order
 * logic. Every method but {@code checkRecorded} runs in the caller's transaction (the clinic is already bound); {@code checkRecorded} must be called with no transaction open and commits each line itself. Every method acts for the signed-in user, whose role the caller passes.
 * Nothing here holds a patient name; allergy text never leaves ipd.
 */
public interface EmrIpdOrderPort {

    /** One select option. */
    record KeyLabel(String key, String label) {
    }

    /** {@code highAlert} is the "high-alert" tag or null. */
    record DrugOption(String key, String name, String route, String highAlert) {
    }

    record OrderOptions(List<DrugOption> drugs, List<KeyLabel> frequencies, List<KeyLabel> overrideReasons) {
    }

    /**
     * One new medicine of the round: {@code clientKey} is the EMR row id (the SAH-31 client key), {@code overrideReasonKey}
     * and {@code checkToken} are set only for a row whose check answered NEEDS_REASON.
     */
    record OrderLine(UUID clientKey, String drugKey, String frequencyKey, String overrideReasonKey,
                     String checkToken) {

        @Override
        public String toString() {
            return "OrderLine[clientKey=" + clientKey + "]";
        }
    }

    /**
     * PASS, NEEDS_REASON, BLOCKED or ORDERED (already placed: a retry). {@code texts} are SAH-31's own lines (they can hold the
     * patient's allergy text: screen only). {@code checkKind} is the code of the first finding that stopped the row (a hard
     * stop before a warning, the SAH-31 FindingCode name) or null when there is none; it is a fixed word, never clinical text.
     */
    record LineCheck(UUID clientKey, String drugName, String frequencyLabel, String outcome, List<String> texts,
                     String checkToken, String checkKind) {

        public LineCheck(UUID clientKey, String drugName, String frequencyLabel, String outcome, List<String> texts,
                         String checkToken) {
            this(clientKey, drugName, frequencyLabel, outcome, texts, checkToken, null);
        }

        @Override
        public String toString() {
            return "LineCheck[clientKey=" + clientKey + ", outcome=" + outcome + "]";
        }
    }

    record Placed(UUID clientKey, UUID orderId, Instant orderedAt) {
    }

    /**
     * {@code ok} is true when every line was placed (or already placed); otherwise {@code notPlaced} holds the checks of the
     * lines that were BLOCKED or NEEDS_REASON at that moment and the caller rolls back.
     */
    record PlaceResult(boolean ok, List<Placed> placed, List<LineCheck> notPlaced) {
    }

    /** The lists of the SAH-31 EMR sheet, read without a view entry. */
    OrderOptions options(UUID tenantId);

    /**
     * Checks each line against the admission's live orders and the patient's allergy exactly as "Check and order" does,
     * and applies SAH-31's own handling: a hard stop writes the deny clinical event and IPD_MED_ORDER_BLOCKED, a warning
     * writes the record-access row. Nothing is ordered.
     */
    List<LineCheck> check(UUID tenantId, UUID actorUserId, String actorRole, UUID admissionId, List<OrderLine> lines);

    /**
     * The same checks as {@link #check}, called with no transaction open (DEC-048 H37: one is refused), each line checked and
     * committed in a transaction of its own, so that its deny event, audit row or record-access row survive when the caller
     * then refuses and rolls back (DEC-048 H31). Nothing is ordered.
     */
    List<LineCheck> checkRecorded(UUID tenantId, UUID actorUserId, String actorRole, UUID admissionId,
                                  List<OrderLine> lines);

    /** Places each line through the SAH-31 order service, in order. A line not placed stops the rest. */
    PlaceResult place(UUID tenantId, UUID actorUserId, String actorRole, UUID admissionId, List<OrderLine> lines);
}
