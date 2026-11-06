package com.sahaj.hra.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code hra.roster_shift} and {@code hra.roster_state} (SAH-20). Needs {@code app.tenant_id} bound. */
public interface RosterRepository {

    /** Saved cells from {@code from} to {@code to}, both included. A day with no row is Off. */
    List<ShiftRow> findBetween(UUID tenantId, LocalDate from, LocalDate to);

    /** The saved shift letter, or empty when no row exists (Off). */
    Optional<String> findShift(UUID tenantId, UUID membershipId, LocalDate date);

    /** Inserts the cell or overwrites its shift. */
    void upsertShift(UUID tenantId, UUID membershipId, LocalDate date, String shift, UUID actorUserId, Instant now);

    /** The first publish time, empty while the roster has never been published. */
    Optional<Instant> findPublishedAt(UUID tenantId);

    /** Records the publish time and person only when not published yet; a published roster keeps its first time. */
    void publish(UUID tenantId, UUID actorUserId, Instant now);

    record ShiftRow(UUID membershipId, LocalDate date, String shift) {
    }
}
