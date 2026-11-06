package com.sahaj.platform.infrastructure.admin;

import java.util.Arrays;
import org.springframework.core.env.Environment;

/**
 * Fails the application start when a dummy-admin seed value ({@code SAHAJ_ADMIN_SEED_EMAIL} or
 * {@code SAHAJ_ADMIN_SEED_PASSWORD}) is set outside the {@code dev} profile (optionally with
 * {@code test}). Same rules as the dev bypass guard. Only the name of the variable is ever printed.
 */
public final class AdminSeedGuard {

    public AdminSeedGuard(AdminProperties.Seed seed, Environment environment) {
        verify(seed.email(), seed.password(), Arrays.asList(environment.getActiveProfiles()));
    }

    static void verify(String seedEmail, String seedPassword, Iterable<String> activeProfiles) {
        if (isBlank(seedEmail) && isBlank(seedPassword)) {
            return;
        }
        boolean dev = false;
        for (String profile : activeProfiles) {
            if ("dev".equals(profile)) {
                dev = true;
            } else if (!"test".equals(profile)) {
                throw new IllegalStateException("SAHAJ_ADMIN_SEED_EMAIL or SAHAJ_ADMIN_SEED_PASSWORD is set but the profile '"
                        + profile + "' is active. Only dev (optionally with test) may seed a dummy admin. Refusing to start.");
            }
        }
        if (!dev) {
            throw new IllegalStateException("SAHAJ_ADMIN_SEED_EMAIL or SAHAJ_ADMIN_SEED_PASSWORD is set but the dev "
                    + "profile is not active. Refusing to start.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
