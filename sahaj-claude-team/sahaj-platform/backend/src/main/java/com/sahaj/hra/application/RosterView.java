package com.sahaj.hra.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code Roster} in {@code docs/api/hra.yaml} (SAH-20). */
public record RosterView(List<LocalDate> days, List<ShiftInfo> shifts, boolean published, Instant publishedAt,
                         List<Row> rows) {

    public record ShiftInfo(String key, String name, String time) {
    }

    /** {@code kind} is SHIFT (staff, see {@code shift}), OPD or OFF (doctor), or LEAVE (SAH-21, anyone on approved leave; no shift). */
    public record Cell(LocalDate date, String kind, String shift) {
    }

    public record Row(UUID membershipId, String name, String roleLabel, boolean doctor, List<Cell> cells) {
    }
}
