package com.sahaj.hra.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code hra.attendance} (SAH-20). No delete. Needs {@code app.tenant_id} bound. */
public interface AttendanceRepository {

    List<AttendanceRow> findForDate(UUID tenantId, LocalDate date);

    Optional<AttendanceRow> find(UUID tenantId, UUID membershipId, LocalDate date);

    /**
     * Inserts the day's mark or changes it (version + 1). {@code markedTime} is the IST wall clock for
     * {@code in} and {@code late}, and must be null for {@code abs}.
     */
    void upsert(UUID tenantId, UUID membershipId, LocalDate date, String status, LocalTime markedTime,
                UUID actorUserId, Instant now);

    record AttendanceRow(UUID membershipId, LocalDate date, String status, LocalTime markedTime) {
    }
}
