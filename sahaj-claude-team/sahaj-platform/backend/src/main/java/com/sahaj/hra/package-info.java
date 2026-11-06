/**
 * Staff, timings and leave (the HRA add-on): the 7-day roster, the daily attendance sheet, the tiles and
 * leave requests with balances (SAH-20, SAH-21).
 *
 * <p>This module's only part visible to other modules is {@code com.sahaj.hra.api}. Everything
 * else in this package and its sub-packages is internal implementation (see
 * {@code docs/architecture/adr/ADR-001-scaffold-baseline.md}, decision D1). {@code hra} reaches other
 * modules only through {@code com.sahaj.platform.api} and {@code com.sahaj.opd.api}; {@code opd} learns of
 * approved leave only through {@code DoctorBlockedDatesPort}.
 */
package com.sahaj.hra;
