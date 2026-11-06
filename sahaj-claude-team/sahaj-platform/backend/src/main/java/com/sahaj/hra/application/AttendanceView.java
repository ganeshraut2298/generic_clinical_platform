package com.sahaj.hra.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code Attendance} in {@code docs/api/hra.yaml} (SAH-20). {@code markedTime} is HH:mm IST, null when not marked or absent; {@code onLeave} (SAH-21) is true when approved leave covers today. */
public record AttendanceView(LocalDate date, int lateAfterMinutes, boolean canMark, List<Row> rows) {

    public record Row(UUID membershipId, String name, String roleLabel, String status, String markedTime,
                      boolean onLeave) {
    }
}
