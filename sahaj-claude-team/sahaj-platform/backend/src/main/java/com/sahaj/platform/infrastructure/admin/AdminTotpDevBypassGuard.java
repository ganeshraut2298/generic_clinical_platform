package com.sahaj.platform.infrastructure.admin;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Fails the application start when {@code sahaj.admin.totp.dev-bypass} is on outside the {@code dev}
 * profile, or when {@code dev} is combined with any profile other than {@code test}. Same rules as
 * the clinic {@code OtpDevBypassGuard}. When the bypass is on in dev it logs a warning at start.
 */
public final class AdminTotpDevBypassGuard {

    private static final Logger log = LoggerFactory.getLogger(AdminTotpDevBypassGuard.class);

    public AdminTotpDevBypassGuard(boolean devBypass, Environment environment) {
        verify(devBypass, Arrays.asList(environment.getActiveProfiles()));
        if (devBypass) {
            log.warn("Admin authenticator dev bypass is on. Any 6-digit code is accepted from this machine.");
        }
    }

    static void verify(boolean devBypass, Iterable<String> activeProfiles) {
        if (!devBypass) {
            return;
        }
        boolean dev = false;
        for (String profile : activeProfiles) {
            if ("dev".equals(profile)) {
                dev = true;
            } else if (!"test".equals(profile)) {
                throw new IllegalStateException("sahaj.admin.totp.dev-bypass is true but the profile '" + profile
                        + "' is active. Only dev (optionally with test) may use it. Refusing to start.");
            }
        }
        if (!dev) {
            throw new IllegalStateException(
                    "sahaj.admin.totp.dev-bypass is true but the dev profile is not active. Refusing to start.");
        }
    }
}
