package com.sahaj.platform.application;

import java.time.Instant;

/** SAH-19 result of every modules call: plan, IPD state, HRA flag and menu counts for the signed-in role. */
public record ModulesView(String plan, Ipd ipd, boolean ipdTrialAvailable, boolean hraOn, MenuCounts menuCounts) {

    public record Ipd(String state, Instant trialEndsAt, boolean requested, Instant requestedAt) {
    }

    public record MenuCounts(int simple, int advanced) {
    }
}
