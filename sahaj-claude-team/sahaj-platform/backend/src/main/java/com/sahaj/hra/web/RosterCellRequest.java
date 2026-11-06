package com.sahaj.hra.web;

import java.time.LocalDate;
import java.util.UUID;

/** {@code RosterCellInput} in {@code docs/api/hra.yaml}; missing or unknown values are refused by the service with a field error. */
public record RosterCellRequest(UUID membershipId, LocalDate date, String shift) {
}
