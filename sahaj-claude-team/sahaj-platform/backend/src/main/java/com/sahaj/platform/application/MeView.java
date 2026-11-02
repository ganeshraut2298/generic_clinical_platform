package com.sahaj.platform.application;

import com.sahaj.platform.domain.UserPreference;
import java.util.List;
import java.util.UUID;

/** {@code Me} in {@code docs/api/platform.yaml} (SAH-5): the current user, built fresh on every call. */
public record MeView(UUID userId, UUID membershipId, String name, String role, Clinic clinic, List<String> menu,
                      UserPreference preferences) {

    public record Clinic(String name, String area, String brand, String plan, Integer planDaysLeft,
                          boolean setupComplete, int setupPercent) {
    }
}
