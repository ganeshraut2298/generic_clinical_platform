package com.sahaj.platform.application;

import com.sahaj.platform.domain.StaffPinRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port over {@code platform.staff_pin} (SAH-31, ADR-010; statements in {@code docs/database/SAH-31-pin.md}, changed by
 * {@code docs/database/SAH-38.md} section 4: every statement keeps the two colleague flags legal). Needs
 * {@code app.tenant_id} bound. The database guard triggers accept exactly the kinds of change below, each with
 * {@code version = version + 1}; a refusal (SQLSTATE 23514, 42501, 23503) is a code bug and surfaces as an error.
 * Every method that decides something reads {@code db_now} from the database clock.
 */
public interface StaffPinRepository {

    /** The membership's row with the database clock; absent = never set. {@code forUpdate} locks the row. */
    Optional<StaffPinRow> find(UUID tenantId, UUID membershipId, boolean forUpdate);

    /** Every row of the clinic (at most one per membership). */
    List<StaffPinRow> findAll(UUID tenantId);

    /** Active members of the clinic who have no PIN (never set, reset or switched off). */
    int countActiveMembersWithoutPin(UUID tenantId);

    /** First PIN. A second first save at the same moment fails with a duplicate key error. */
    void insert(UUID membershipId, String pinHash, int pepperVersion, UUID actorUserId);

    /** New PIN on an existing row (change, forgot, after reset or switch-off); clears counters and the reason. */
    void replace(UUID tenantId, UUID membershipId, String pinHash, int pepperVersion, UUID actorUserId);

    /**
     * SAH-38 (ADR-014 D5): {@code set local lock_timeout = '3s'} and {@code statement_timeout = '5s'} for the current
     * transaction, so a stuck row lock or a slow statement fails (SQLSTATE 55P03 or 57014) instead of waiting.
     */
    void limitWaitingTime();

    /** One more wrong try typed in the person's own session (only while the failed count is below 4 and not locked). */
    void recordWrongTry(UUID tenantId, UUID membershipId, UUID actorUserId);

    /** One more wrong try typed by a colleague: also marks the run as containing a colleague try (ADR-014 D6). */
    void recordColleagueWrongTry(UUID tenantId, UUID membershipId, UUID actorUserId);

    /** Right PIN: failed count back to 0 and the run flag false (call only when the failed count is above 0). */
    void recordRightPin(UUID tenantId, UUID membershipId, UUID actorUserId);

    /**
     * Fifth wrong try of a run typed in the person's own session: lock for 15 minutes; {@code newLockCount} is 1 for a
     * new 24-hour window, else the old count + 1. Both colleague flags are false afterwards.
     */
    void lock(UUID tenantId, UUID membershipId, int newLockCount, UUID actorUserId);

    /**
     * Fifth wrong try of a run that has a colleague try (ADR-014 D6): lock for 15 minutes, {@code lock_by_colleagues}
     * true, the run flag false, {@code lock_count} and the 24-hour window untouched. Never a switch-off.
     */
    void lockByColleagues(UUID tenantId, UUID membershipId, UUID actorUserId);

    /** Third lock in 24 hours: PIN off with reason TOO_MANY_LOCKS. */
    void switchOffAfterLocks(UUID tenantId, UUID membershipId, UUID actorUserId);

    /** Owner reset or removal of access: PIN off with reason RESET_BY_OWNER (only when a PIN is set). */
    void reset(UUID tenantId, UUID membershipId, UUID ownerUserId);
}
