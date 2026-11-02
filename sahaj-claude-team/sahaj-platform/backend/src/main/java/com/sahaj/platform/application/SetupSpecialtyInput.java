package com.sahaj.platform.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Step 2 of the setup wizard: {@code SetupSpecialty} in {@code docs/api/platform.yaml} (SAH-4 AC8). */
public record SetupSpecialtyInput(
        @NotBlank(message = "Choose a specialty")
        @Pattern(regexp = "^(gp|dental|paeds|homeo)$", message = "Choose a valid specialty")
        String code) {
}
