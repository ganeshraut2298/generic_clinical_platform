package com.sahaj.hra.web;

import java.time.LocalDate;
import java.util.UUID;

/** Body of {@code POST /leave} ({@code LeaveInput}). The reason is free text and is never logged. */
public record LeaveRequestBody(UUID membershipId, String type, LocalDate from, LocalDate to, String reason) {

    @Override
    public String toString() {
        return "LeaveRequestBody[type=" + type + ", from=" + from + ", to=" + to + "]";
    }
}
