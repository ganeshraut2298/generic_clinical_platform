package com.sahaj.hra.application;

import java.time.LocalDate;
import java.util.UUID;

/** One roster cell change (SAH-20); every field is validated by {@link HraService}. */
public record RosterCellInput(UUID membershipId, LocalDate date, String shift) {
}
