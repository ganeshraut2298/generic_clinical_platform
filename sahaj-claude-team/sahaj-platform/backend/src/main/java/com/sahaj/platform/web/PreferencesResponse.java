package com.sahaj.platform.web;

import com.sahaj.platform.domain.UserPreference;

/** {@code Preferences} in {@code docs/api/platform.yaml} (SAH-5), as returned inside {@code Me}. */
public record PreferencesResponse(String language, String theme, String menuMode, boolean sidebarCollapsed) {

    public static PreferencesResponse from(UserPreference preference) {
        return new PreferencesResponse(preference.language(), preference.theme(), preference.menuMode(),
                preference.sidebarCollapsed());
    }
}
