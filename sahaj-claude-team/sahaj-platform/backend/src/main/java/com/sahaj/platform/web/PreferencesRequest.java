package com.sahaj.platform.web;

import com.sahaj.platform.application.PreferencesInput;
import jakarta.validation.constraints.Pattern;

/** Body of {@code PATCH /api/v1/platform/me/preferences} (SAH-5 AC2, AC3): every field is optional. */
public record PreferencesRequest(
        @Pattern(regexp = "^(en|mr|hi)$", message = "Choose a valid language")
        String language,

        @Pattern(regexp = "^(light|dark)$", message = "Choose a valid option")
        String theme,

        @Pattern(regexp = "^(simple|advanced)$", message = "Choose a valid option")
        String menuMode,

        Boolean sidebarCollapsed) {

    public PreferencesInput toInput() {
        return new PreferencesInput(language, theme, menuMode, sidebarCollapsed);
    }
}
