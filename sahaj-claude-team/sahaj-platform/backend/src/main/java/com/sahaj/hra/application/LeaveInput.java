package com.sahaj.hra.application;

import java.time.LocalDate;
import java.util.UUID;

/** {@code LeaveInput} in {@code docs/api/hra.yaml} (SAH-21). {@code membershipId} null means the signed-in person. */
public record LeaveInput(UUID membershipId, String type, LocalDate from, LocalDate to, String reason) {

    /** The reason is free text typed by staff and must never reach a log line. */
    @Override
    public String toString() {
        return "LeaveInput[type=" + type + ", from=" + from + ", to=" + to + "]";
    }
}
