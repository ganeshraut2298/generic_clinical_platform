package com.sahaj.platform.web;

import com.sahaj.platform.application.MeView;
import java.util.List;
import java.util.UUID;

/** {@code Me} in {@code docs/api/platform.yaml} (SAH-5): {@code GET /me}, {@code PATCH /me/preferences}. */
public record MeResponse(UUID userId, UUID membershipId, String name, String role, Clinic clinic, List<String> menu,
                          PreferencesResponse preferences) {

    public record Clinic(String name, String area, String brand, String brandHex, String plan, Integer planDaysLeft,
                          boolean setupComplete, int setupPercent) {
    }

    public static MeResponse from(MeView view) {
        MeView.Clinic c = view.clinic();
        return new MeResponse(view.userId(), view.membershipId(), view.name(), view.role(),
                new Clinic(c.name(), c.area(), c.brand(), c.brandHex(), c.plan(), c.planDaysLeft(), c.setupComplete(), c.setupPercent()),
                view.menu(), PreferencesResponse.from(view.preferences()));
    }
}
