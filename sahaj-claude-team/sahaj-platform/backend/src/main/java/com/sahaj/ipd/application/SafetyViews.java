package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahaj.ipd.domain.EarlyWarningScore.Risk;
import com.sahaj.ipd.domain.EscalationState.Status;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-33 request and response shapes of {@code docs/api/ipd.yaml} (Safety board, summary, banner, the three actions
 * and the clinical audit trail). Patient names, staff names and the plan are on screen only: every record that
 * carries them hides them from {@code toString}, so they cannot reach a log line by accident.
 */
public final class SafetyViews {

    private SafetyViews() {
    }

    /**
     * Counts only, no names (ADR-005 D3). {@code open} is OPEN plus ACK; {@code asOf} is the database clock.
     * {@code changeToken} (ADR-007 R1) is an opaque hash of the id and version of every escalation counted in
     * {@code open}; the client compares it for equality with the last one it saw.
     */
    public record Counts(int open, int pastTarget, String changeToken, Instant asOf) {
    }

    /** One choice of the select "What was done". */
    public record ActionOption(String key, String label) {
    }

    /** The recorded response of a closed escalation. The plan is clinical text: hidden from {@code toString}. */
    public record Response(String action, String actionLabel, String note, String closedByName, Instant closedAt) {

        @Override
        public String toString() {
            return "Response[action=" + action + "]";
        }
    }

    /** One row of the panel "Early warning escalations". Names and the plan are on screen only: hidden. */
    public record Row(UUID id, UUID admissionId, long version, String patientName, String bedCode, int ewsScore,
                      Risk ewsRisk, int targetMinutes, Status status, Instant raisedAt, String raisedByName,
                      int minutesSinceRaised, Instant reopenedAt, int waitingMinutes, boolean pastTarget,
                      int seniorCalls, String acknowledgedByName, Instant acknowledgedAt, Response response,
                      boolean canAcknowledge, boolean canRespond, boolean canCallSenior) {

        @Override
        public String toString() {
            return "Row[id=" + id + ", status=" + status + "]";
        }
    }

    /** The Safety board. {@code view} is DOCTOR or NURSE; {@code yourPatients} is set for NURSE only. */
    public record Board(String state, String view, Instant asOf, Counts counts, Integer yourPatients,
                        List<ActionOption> responseActions, List<Row> escalations) {

        @Override
        public String toString() {
            return "Board[escalations=" + escalations.size() + "]";
        }
    }

    /** One escalation past target on the banner of the Admitted patients tab. The name is on screen only. */
    public record BannerItem(UUID escalationId, UUID admissionId, String patientName, int ewsScore) {

        @Override
        public String toString() {
            return "BannerItem[escalationId=" + escalationId + "]";
        }
    }

    /** {@code AdmissionList.safety}: the tab count and the banner, for OWNER, DOCTOR and NURSE only. */
    public record Banner(Counts counts, List<BannerItem> pastTarget) {
    }

    /** Answer of acknowledge, respond and call-senior. */
    public record ActionResult(Instant asOf, boolean replayed, Row escalation) {
    }

    /**
     * Request body of acknowledge. The version is kept as the JSON that arrived, so that a string such as "3" or a
     * fraction is the contract's 400 and is never converted.
     */
    public record VersionInput(JsonNode version) {
    }

    /** Request body of respond. The plan is clinical text: hidden from {@code toString}. */
    public record ResponseInput(JsonNode version, String action, String note) {

        @Override
        public String toString() {
            return "ResponseInput[redacted]";
        }
    }

    /** Request body of call-senior; both numbers are the JSON that arrived. */
    public record SeniorCallInput(JsonNode version, JsonNode seniorCalls) {
    }

    /** One entry of the clinical audit trail. Names are on screen only: hidden. */
    public record Event(UUID id, String kind, String text, Instant at, String actorName, UUID admissionId,
                        String patientName, String bedCode) {

        @Override
        public String toString() {
            return "Event[id=" + id + ", kind=" + kind + "]";
        }
    }

    /** One page of the audit trail; {@code nextCursor} is null when no older entries exist. */
    public record EventPage(Instant asOf, List<Event> items, String nextCursor) {
    }
}
