package com.sahaj.platform.infrastructure.modules;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails the application start when the owner IPD trial switch ({@code sahaj.modules.owner-ipd-trial}, DEC-010)
 * is on while neither the {@code dev} nor the {@code test} profile is active, so a production clinic owner can
 * never switch IPD on for themselves by mistake of configuration (security review L4).
 */
@Component
public class OwnerIpdTrialGuard {

    public OwnerIpdTrialGuard(@Value("${sahaj.modules.owner-ipd-trial:false}") boolean ownerIpdTrial,
                              Environment environment) {
        verify(ownerIpdTrial, Arrays.asList(environment.getActiveProfiles()));
    }

    static void verify(boolean ownerIpdTrial, Iterable<String> activeProfiles) {
        if (!ownerIpdTrial) {
            return;
        }
        for (String profile : activeProfiles) {
            if ("dev".equals(profile) || "test".equals(profile)) {
                return;
            }
        }
        throw new IllegalStateException("sahaj.modules.owner-ipd-trial is true but neither the dev nor the test "
                + "profile is active. Refusing to start.");
    }
}
