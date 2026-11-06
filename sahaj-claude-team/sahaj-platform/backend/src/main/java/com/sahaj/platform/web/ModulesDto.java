package com.sahaj.platform.web;

import com.sahaj.platform.application.ModulesView;
import java.time.Instant;

/** SAH-19 contract shape of {@code ModulesView}. */
public record ModulesDto(String plan, Ipd ipd, boolean ipdTrialAvailable, Hra hra, MenuCounts menuCounts) {

    public record Ipd(String state, Instant trialEndsAt, boolean requested, Instant requestedAt) {
    }

    public record Hra(boolean on) {
    }

    public record MenuCounts(int simple, int advanced) {
    }

    static ModulesDto from(ModulesView v) {
        return new ModulesDto(v.plan(),
                new Ipd(v.ipd().state(), v.ipd().trialEndsAt(), v.ipd().requested(), v.ipd().requestedAt()),
                v.ipdTrialAvailable(), new Hra(v.hraOn()),
                new MenuCounts(v.menuCounts().simple(), v.menuCounts().advanced()));
    }
}
