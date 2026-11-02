package com.sahaj.platform.api;

/**
 * Plan limits during the setup wizard and team management (SAH-4 technical design, SAH-6).
 * Free and Professional are the same in phase 1: 2 doctors (the owner counts as one of them when
 * they see patients) and 5 users in total (every doctor and staff membership together).
 */
public final class PlanLimits {

    public static final int MAX_DOCTORS = 2;
    public static final int MAX_USERS = 5;

    private PlanLimits() {
    }
}
