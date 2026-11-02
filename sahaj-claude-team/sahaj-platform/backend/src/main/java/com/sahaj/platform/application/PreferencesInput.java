package com.sahaj.platform.application;

import jakarta.validation.constraints.Pattern;

/** {@code Preferences} in {@code docs/api/platform.yaml} (SAH-5 AC2, AC3): every field is optional, patch-style. */
public record PreferencesInput(
        @Pattern(regexp = "^(en|mr|hi)$", message = "Choose a valid language")
        String language,

        @Pattern(regexp = "^(light|dark)$", message = "Choose a valid option")
        String theme,

        @Pattern(regexp = "^(simple|advanced)$", message = "Choose a valid option")
        String menuMode,

        Boolean sidebarCollapsed) {
}
