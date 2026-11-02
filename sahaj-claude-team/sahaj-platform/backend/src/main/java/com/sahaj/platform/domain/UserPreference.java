package com.sahaj.platform.domain;

/**
 * One person's own shell preferences in one clinic (SAH-5, {@code platform.user_preference}).
 * {@code language} is {@code en}, {@code mr} or {@code hi}; {@code theme} is {@code light} or
 * {@code dark}; {@code menuMode} is {@code simple} or {@code advanced}.
 */
public record UserPreference(String language, String theme, String menuMode, boolean sidebarCollapsed) {

    public static final String DEFAULT_LANGUAGE = "en";
    public static final String DEFAULT_THEME = "light";
    public static final String DEFAULT_MENU_MODE = "advanced";

    public static UserPreference defaults() {
        return new UserPreference(DEFAULT_LANGUAGE, DEFAULT_THEME, DEFAULT_MENU_MODE, false);
    }
}
