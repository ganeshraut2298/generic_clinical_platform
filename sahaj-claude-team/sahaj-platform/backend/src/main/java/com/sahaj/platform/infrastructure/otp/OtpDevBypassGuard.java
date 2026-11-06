package com.sahaj.platform.infrastructure.otp;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails the application start when the OTP dev-bypass is on outside the {@code dev} profile, or when
 * {@code dev} is combined with any profile other than {@code test} (for example {@code dev,prod}), so a
 * forgotten or wrong profile can never accept any 6-digit code for any mobile number.
 */
@Component
public class OtpDevBypassGuard {

    public OtpDevBypassGuard(@Value("${sahaj.otp.dev-bypass:false}") boolean devBypass, Environment environment) {
        verify(devBypass, Arrays.asList(environment.getActiveProfiles()));
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
                throw new IllegalStateException("sahaj.otp.dev-bypass is true but the profile '" + profile
                        + "' is active. Only dev (optionally with test) may use it. Refusing to start.");
            }
        }
        if (!dev) {
            throw new IllegalStateException(
                    "sahaj.otp.dev-bypass is true but the dev profile is not active. Refusing to start.");
        }
    }
}
