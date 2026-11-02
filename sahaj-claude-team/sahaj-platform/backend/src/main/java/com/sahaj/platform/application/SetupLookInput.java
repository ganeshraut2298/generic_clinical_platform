package com.sahaj.platform.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Step 5 of the setup wizard: {@code SetupLook} in {@code docs/api/platform.yaml} (SAH-4 AC11). */
public record SetupLookInput(
        @NotBlank(message = "Choose a clinic colour")
        @Pattern(regexp = "^(blue|teal|green|maroon|custom)$", message = "Choose a valid colour")
        String brand,

        @NotBlank(message = "Choose a web address")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{2,23}$", message = "3 to 24 lowercase letters, numbers or hyphens")
        String slug,

        @NotBlank(message = "Choose a staff language")
        @Pattern(regexp = "^(en|mr|hi)$", message = "Choose a valid language")
        String language) {
}
