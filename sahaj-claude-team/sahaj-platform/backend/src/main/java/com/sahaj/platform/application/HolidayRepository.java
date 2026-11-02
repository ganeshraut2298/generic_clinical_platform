package com.sahaj.platform.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over {@code platform.clinic_holiday} (SAH-7). Needs {@code app.tenant_id} bound. */
public interface HolidayRepository {

    /** The bound clinic's holidays, earliest date first. */
    List<HolidayRow> findAll(UUID tenantId);

    Optional<HolidayRow> findById(UUID tenantId, UUID id);

    /** Throws {@link org.springframework.dao.DuplicateKeyException} when the clinic already has that date. */
    void insert(UUID id, LocalDate date, String name, String type, UUID actorUserId);

    /** Returns how many rows were deleted (0 when the id is not this clinic's). */
    int delete(UUID tenantId, UUID id);

    /** Holidays on or after {@code from}. */
    int countFrom(UUID tenantId, LocalDate from);

    record HolidayRow(UUID id, LocalDate date, String name, String type) {
    }
}
