package com.sahaj.ipd.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SAH-28 request and response shapes of {@code docs/api/ipd.yaml} (emergency queue, quick registration, Emergency
 * setup, the Admit hand-off). The name, age, sex, mobile and complaint are on screen only: every record that carries
 * them hides them from {@code toString}, so they cannot reach a log line by accident.
 */
public final class EmergencyViews {

    private EmergencyViews() {
    }

    /** One level of the clinic's current scale; {@code tone} is crit, warn or good. */
    public record Level(int level, String name, String description, String tone, int targetMinutes) {
    }

    /** The menu badge, the tile and the tab: no names. */
    public record Counts(boolean writable, int queue, int overTarget) {
    }

    /**
     * What an action answers as {@code actedCase}: the full {@link Case}, or the {@link CaseRef} in the one place the
     * contract allows it (a repeated client key whose case is closed or was registered by someone else).
     */
    public sealed interface ActedCase permits Case, CaseRef {

        UUID id();

        int level();

        String status();
    }

    /** The smallest view of a case: id, level and status; no name, age, sex, complaint, bay or times (security review L1). */
    public record CaseRef(UUID id, int level, String status) implements ActedCase {
    }

    /** One card of the queue. The mobile is not part of it. */
    public record Case(UUID id, String displayName, Integer age, String sex, String complaint, boolean identityPending,
                       int level, String levelName, String bay, String status, Instant arrivedAt, int waitingMinutes,
                       int targetMinutes, boolean overTarget, Instant seenAt) implements ActedCase {

        @Override
        public String toString() {
            return "Case[id=" + id + ", level=" + level + ", status=" + status + "]";
        }
    }

    /** The Emergency page and the answer of every action; {@code actedCase} is set by an action only. */
    public record Queue(String state, int scale, List<Level> levels, int defaultLevel,
                        boolean unknownPatientRegistration, int baysTotal, int baysFree, Counts counts,
                        List<Case> cases, boolean canRegister, boolean canRetriage, boolean canAct, boolean canAdmit,
                        Instant asOf, ActedCase actedCase) {
    }

    /** {@code created} false is a repeat of a client key: 200 with the first case; true is 201. */
    public record Registration(Queue queue, boolean created) {
    }

    /**
     * Request body of {@code POST /emergency/cases}. The three typed fields are kept as the JSON that arrived
     * ({@link JsonNode}), so that a string where a number or a boolean belongs ("40", "true") is a field error with the
     * contract's message and is never converted, and a missing or null {@code identityUnknown} is never read as false
     * (code review 5(b) and 5(c)). The client key is text so that "not a UUID" is the contract's 400. Personal and
     * clinical text: hidden.
     */
    public record CaseInput(String clientKey, JsonNode identityUnknown, String name, JsonNode age, String sex,
                            String mobile, String complaint, JsonNode level) {

        @Override
        public String toString() {
            return "CaseInput[redacted]";
        }
    }

    /** Request body of {@code PUT /emergency/cases/{id}/level}; the JSON as it arrived, so that a string "2" is refused. */
    public record LevelInput(JsonNode level) {
    }

    /** Request body of {@code PUT /emergency-settings}; decimals so that 5.5 is refused and not cut to 5. */
    public record SettingsInput(BigDecimal scale, List<BigDecimal> targets3, List<BigDecimal> targets5,
                                BigDecimal bays, Boolean unknownPatientRegistration) {
    }

    /** The saved setup with the levels of the scale in use; {@code canEdit} is true for the owner outside Read-only. */
    public record Settings(int scale, List<Integer> targets3, List<Integer> targets5, int bays,
                           boolean unknownPatientRegistration, List<Level> levels, boolean canEdit) {
    }

    /** What the Admit sheet needs from a case. Name, mobile and complaint are on screen only: hidden. */
    public record Handoff(UUID caseId, String displayName, Integer age, String sex, String mobile, String complaint,
                          boolean identityPending, int level, String levelName, UUID suggestedBedId) {

        @Override
        public String toString() {
            return "Handoff[caseId=" + caseId + ", level=" + level + "]";
        }
    }
}
