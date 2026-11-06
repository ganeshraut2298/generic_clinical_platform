package com.sahaj.hra.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code LeaveView} in {@code docs/api/hra.yaml} (SAH-21). */
public record LeaveView(List<TypeInfo> types, List<Request> requests, List<Balance> balances,
                        List<Applicant> applicants, int pendingCount, boolean canApprove) {

    public record TypeInfo(String key, String name, int days) {
    }

    public record Request(UUID id, UUID membershipId, String name, String roleLabel, String type, String typeName,
                          LocalDate from, LocalDate to, int days, String reason, String status, Instant askedAt,
                          Instant decidedAt, String decidedByName, int affectedAppointments) {

        /** The reason is staff health data and must never reach a log line. */
        @Override
        public String toString() {
            return "LeaveView.Request[id=" + id + ", type=" + type + ", from=" + from + ", to=" + to
                    + ", status=" + status + "]";
        }
    }

    public record Remaining(int CL, int SL, int EL) {
    }

    public record Balance(UUID membershipId, String name, Remaining remaining) {
    }

    public record Applicant(UUID membershipId, String name) {
    }
}
