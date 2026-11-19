package com.sahaj.pharmacy.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is the current approver of a pending request at its current level (ADR-029 P6). Worked out on every read and on every
 * decision, never stored per person, and free of I/O.
 *
 * <ul>
 *   <li>STORE_APPROVER: the rule store's HOD ({@code HOD}) and its current delegate ({@code DELEGATE}); when the store has
 *       neither, every owner ({@code OWNER}, BR-14).</li>
 *   <li>OWNER: every owner ({@code OWNER}).</li>
 *   <li>Escalated (BR-16): for a STORE_APPROVER level every owner is added ({@code ESCALATED}); an OWNER level has no next
 *       approver, the mark only.</li>
 *   <li>Never the raiser (BR-13); at level 2 never the person who approved level 1 (OQ-79-28).</li>
 *   <li>If that leaves nobody, the request "Needs another approver" and is shown to the owners (the same exclusions); if no
 *       owner is left either, it waits and nobody can decide it. Nothing is bypassed.</li>
 * </ul>
 */
public final class ApproverResolver {

    private ApproverResolver() {
    }

    public static final String HOD = "HOD";
    public static final String DELEGATE = "DELEGATE";
    public static final String OWNER = "OWNER";
    public static final String ESCALATED = "ESCALATED";

    /** {@code approvers} maps a membership id to the capacity it would decide in; insertion order is HOD, delegate, owners. */
    public record Result(Map<UUID, String> approvers, boolean escalated, boolean needsAnotherApprover) {

        public boolean canDecide(UUID membershipId) {
            return approvers.containsKey(membershipId);
        }

        public String actingAs(UUID membershipId) {
            return approvers.get(membershipId);
        }
    }

    /**
     * @param kind          who approves at the current level
     * @param raisedBy      the raiser
     * @param level1Decider who approved level 1 (only when {@code level} is 2), else null
     * @param level         the current level, 1 or 2
     * @param escalated     true when the wait has passed the escalation time
     * @param hod           the rule store's HOD who can sign in, or null
     * @param delegate      the rule store's current delegate who can sign in, or null
     * @param owners        every owner who can sign in
     */
    public static Result resolve(ApproverKind kind, UUID raisedBy, UUID level1Decider, int level, boolean escalated, UUID hod,
                                 UUID delegate, List<UUID> owners) {
        UUID excludedSecond = level == 2 ? level1Decider : null;
        Map<UUID, String> base = new LinkedHashMap<>();
        if (kind == ApproverKind.STORE_APPROVER) {
            put(base, hod, HOD);
            put(base, delegate, DELEGATE);
            if (base.isEmpty()) {
                owners.forEach(o -> put(base, o, OWNER));
            }
            if (escalated) {
                owners.forEach(o -> putIfAbsent(base, o, ESCALATED));
            }
        } else {
            owners.forEach(o -> put(base, o, OWNER));
        }
        Map<UUID, String> approvers = without(base, raisedBy, excludedSecond);
        boolean needsAnother = false;
        if (approvers.isEmpty()) {
            needsAnother = true;
            Map<UUID, String> ownersOnly = new LinkedHashMap<>();
            owners.forEach(o -> put(ownersOnly, o, OWNER));
            approvers = without(ownersOnly, raisedBy, excludedSecond);
        }
        return new Result(approvers, escalated, needsAnother);
    }

    /** Escalated when a wait longer than the limit has passed; no limit never escalates. */
    public static boolean isEscalated(Integer escalateAfterMinutes, java.time.Instant since, java.time.Instant now) {
        return escalateAfterMinutes != null && now.isAfter(since.plus(escalateAfterMinutes, java.time.temporal.ChronoUnit.MINUTES));
    }

    private static void put(Map<UUID, String> map, UUID id, String acting) {
        if (id != null) {
            map.put(id, acting);
        }
    }

    private static void putIfAbsent(Map<UUID, String> map, UUID id, String acting) {
        if (id != null) {
            map.putIfAbsent(id, acting);
        }
    }

    private static Map<UUID, String> without(Map<UUID, String> source, UUID first, UUID second) {
        Map<UUID, String> kept = new LinkedHashMap<>();
        source.forEach((id, acting) -> {
            if (!id.equals(first) && !id.equals(second)) {
                kept.put(id, acting);
            }
        });
        return kept;
    }
}
