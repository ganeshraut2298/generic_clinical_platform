package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminSignInPolicies;

/**
 * The dev authenticator bypass (ADR-003 D5): when {@code sahaj.admin.totp.dev-bypass} is on, a
 * request whose servlet remote address is a loopback address skips the 6-digit code check. Any
 * other address goes through the real check. Forwarding headers are never read. Only the code
 * check is replaced: the password and recovery codes are always checked for real. The guard
 * {@link AdminTotpDevBypassGuard} refuses to start the application with the switch on outside dev.
 */
public final class AdminDevBypass implements AdminSignInPolicies.TotpDevBypass {

    private final boolean enabled;

    public AdminDevBypass(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean appliesTo(String remoteAddress) {
        return enabled && IpLiterals.parse(remoteAddress).map(address -> address.isLoopbackAddress()).orElse(false);
    }
}
