package com.sahaj.platform.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Clinic holidays for other modules (SAH-20): {@code opd} uses it to decide whether a doctor has OPD
 * on a date. Implemented by {@code platform}, the owner of {@code platform.clinic_holiday}. Runs in
 * the caller's transaction (tenant already bound).
 */
public interface ClinicHolidayPort {

    /** Dates from {@code from} to {@code to} (both included) on which the clinic is closed all day. A one-session day is still open. */
    Set<LocalDate> closedDates(UUID tenantId, LocalDate from, LocalDate to);
}
