package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SAH-34 (ADR-017 D1) which admitted patients one caller may reach, built once per request by {@link ScopeGuard} from the
 * signed-in user, the clinic switch, the live ward assignments and the live emergency access grants. Nothing in a request
 * takes part. {@code wardIds} is filled for a NURSE whatever the switch says (it feeds "mine" and {@code hasWard});
 * {@code grants} (admission id to the end time) only when the kind is narrower than ALL.
 *
 * @param kind        ALL, WARDS (a NURSE with the switch on and at least one live ward), NONE (a NURSE with the switch on
 *                    and no live ward) or OWN (VISITING_DOCTOR, reserved for story 34D)
 * @param memberRole  the effective role: NURSE when the role in the token or in the membership is NURSE, VISITING_DOCTOR
 *                    likewise, else the membership role
 * @param membershipId the caller's active membership; null when it was not read (an unscoped role)
 */
record PatientScope(Kind kind, UUID userId, String tokenRole, String memberRole, UUID membershipId,
                    Set<UUID> wardIds, Map<UUID, Instant> grants) {

    enum Kind { ALL, WARDS, OWN, NONE }

    PatientScope {
        wardIds = wardIds == null ? Set.of() : Set.copyOf(wardIds);
        grants = grants == null ? Map.of() : Map.copyOf(grants);
    }

    /** A scope that reaches every admitted patient. */
    static PatientScope all(UUID userId, String tokenRole, String memberRole, UUID membershipId, Set<UUID> wardIds) {
        return new PatientScope(Kind.ALL, userId, tokenRole, memberRole, membershipId, wardIds, Map.of());
    }

    /** An unscoped caller with nothing else known (tests, roles with no scope). */
    static PatientScope all() {
        return new PatientScope(Kind.ALL, null, null, null, null, Set.of(), Map.of());
    }

    boolean isAll() {
        return kind == Kind.ALL;
    }

    boolean isNurse() {
        return ScopeGuard.NURSE.equals(memberRole);
    }

    /** NURSE with at least one live ward assignment, switch on or off. */
    boolean hasWard() {
        return !wardIds.isEmpty();
    }

    /** The patient is reached through the caller's own bed or admitting-doctor rule (no grant counted). */
    boolean ownsPlacement(UUID wardId, UUID doctorMembershipId) {
        switch (kind) {
            case ALL:
                return true;
            case WARDS:
                return wardId != null && wardIds.contains(wardId);
            case OWN:
                return membershipId != null && membershipId.equals(doctorMembershipId);
            default:
                return false;
        }
    }

    /** The placement is in scope: the ward or own rule, or a live grant on this ADMITTED admission. */
    boolean allows(UUID wardId, UUID doctorMembershipId, UUID admissionId) {
        return ownsPlacement(wardId, doctorMembershipId) || grants.containsKey(admissionId);
    }

    /** The caller sees this row only through her own live grant (the grey flag "Emergency access"). */
    Instant grantOnlyExpiry(UUID wardId, UUID doctorMembershipId, UUID admissionId) {
        if (isAll() || ownsPlacement(wardId, doctorMembershipId)) {
            return null;
        }
        return grants.get(admissionId);
    }
}
