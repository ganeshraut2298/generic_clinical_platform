package com.sahaj.platform.api;

import java.util.UUID;

/**
 * Checks a personal staff PIN (SAH-31, ADR-010 D9). {@link #check} checks the signed-in person's own PIN
 * ({@code ipd} pharmacist check, "Verify order"); {@link #checkColleague} checks another named nurse's PIN for the
 * second check of a high-alert dose (SAH-32, ADR-011 D7).
 *
 * <p>How to call {@link #check} (the order matters; {@link #checkColleague} follows the same rules):
 * <ul>
 *   <li>Pass the signed-in person's own {@code tenantId} and {@code userId} from the security
 *       context, never a value from a request. {@code check} never takes another person's id.</li>
 *   <li>Call it <b>outside any transaction</b>, before the caller's own transaction starts. The
 *       implementation runs its own short platform transaction (so a wrong try is committed even
 *       when the caller's request then fails, and no platform row lock is held while the caller
 *       holds its own locks). It throws {@link IllegalStateException} when a transaction is active.</li>
 *   <li>Normally call {@code check(...).requireOk()}: it returns for a right PIN and otherwise
 *       throws {@link StaffPinException}, which the platform error handler turns into the problem
 *       types {@code wrong-pin}, {@code pin-locked}, {@code pin-not-set}, {@code rate-limit} and
 *       {@code pin-unavailable} with the texts written once in the platform.</li>
 * </ul>
 *
 * <p>A right PIN is not a token: it is valid for this one call only. The PIN is never stored,
 * logged or audited by the implementation.
 */
public interface StaffPinPort {

    /**
     * Checks the PIN of the person's active membership in the clinic.
     *
     * @param tenantId the clinic, from the signed-in user
     * @param userId   the person whose PIN is checked, from the signed-in user
     * @param pin      the PIN as typed; may be null or malformed, which counts as a wrong try
     * @return the outcome; never null
     * @throws IllegalStateException when called inside an active transaction
     */
    PinCheck check(UUID tenantId, UUID userId, String pin);

    /**
     * Checks a colleague's own personal staff PIN for a second-person check (SAH-32, ADR-011 D7): the second nurse of a
     * high-alert dose types their PIN, the signed-in caller only submits it. The giver's PIN is not involved.
     *
     * <p>Same calling rules as {@link #check}: pass the {@code tenantId} and the caller's {@code callerUserId} from the
     * security context (never from a request), and call <b>outside any transaction</b> (the implementation runs its own
     * short platform transaction that commits, so a wrong try counts although the caller's request then fails; it throws
     * {@link IllegalStateException} when a transaction is active). The PIN is never stored, logged, audited or echoed.
     *
     * <p>Who may be named: an ACTIVE member of the same clinic whose role is {@code requiredRole}, and who is not the
     * caller. Anyone else (another clinic's id, a deactivated or unknown member, another role, the caller, a null id) gives
     * {@link PinCheck.Outcome#NOT_ELIGIBLE} and no PIN is compared or counted; the answer is the same for every such case,
     * so it tells the caller nothing about the person.
     *
     * <p>Order (SAH-38, ADR-014 D3): pepper missing UNAVAILABLE; the caller's in-memory limit (5 failed colleague tries per
     * 15 minutes, 10 per 24 hours) RATE_LIMITED before any hash; not eligible NOT_ELIGIBLE; then one platform transaction
     * (lock wait 3 s, statement time 5 s) that locks the colleague's PIN row: no PIN NOT_SET, locked LOCKED, then the pair
     * cap (the caller already made 5 wrong tries of this colleague's PIN within a rolling 7 days, durable in the database)
     * PAIR_LIMIT without comparing the PIN or counting anything, then the comparison. A wrong PIN counts on the colleague's
     * lockout (5 wrong tries in a row lock it for 15 minutes; a lock caused by a run with a colleague try never counts
     * toward switching the PIN off) and on the pair, and is audited with ids only. A right PIN of a pair that had wrong
     * tries clears the pair. A database failure or timeout answers CHECK_FAILED (fail closed, nothing counted, the same
     * answer for a right or a wrong PIN).
     *
     * @param tenantId            the clinic, from the signed-in user
     * @param callerUserId        the signed-in person (the giver), from the signed-in user
     * @param colleagueMembershipId the second checker's membership id (from the MAR's list); null is not eligible
     * @param requiredRole        the role the colleague must have, a {@code MembershipRole} name such as {@code "NURSE"}
     * @param pin                 the colleague's PIN as typed; may be null or malformed, which counts as a wrong try
     * @return the outcome with the colleague's user id when OK; never null
     * @throws IllegalArgumentException when a required id or the role is missing or the role is not a known role
     * @throws IllegalStateException    when called inside an active transaction
     */
    ColleaguePinCheck checkColleague(UUID tenantId, UUID callerUserId, UUID colleagueMembershipId,
                                     String requiredRole, String pin);

    /**
     * SAH-66 (ADR-023 D6): which of these memberships of the clinic have a PIN set, for the chemotherapy checker options
     * ("hasPin"). A locked or switched-off PIN is not revealed here. Runs in the caller's transaction (the clinic is
     * bound); the default answers none, so a fake of this port needs no change.
     */
    default java.util.Set<UUID> membershipsWithPin(UUID tenantId, java.util.Collection<UUID> membershipIds) {
        return java.util.Set.of();
    }
}
