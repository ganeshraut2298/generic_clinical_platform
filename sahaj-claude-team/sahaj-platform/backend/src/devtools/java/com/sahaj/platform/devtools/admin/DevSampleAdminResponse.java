package com.sahaj.platform.devtools.admin;

import java.util.UUID;

/**
 * Contract schema {@code DevSampleAdmin} (docs/api/admin.yaml). Returned once. {@code toString} prints
 * no value, so a framework debug line such as "Writing [...]" shows nothing secret.
 */
public record DevSampleAdminResponse(UUID adminId, String email, String fullName, String password, String secretKey,
                                     String otpauthUri, String accountName, String issuer, int digits,
                                     int periodSeconds, String algorithm, boolean totpEnrolled,
                                     boolean totpDevBypassOn, String signInPath) {

    @Override
    public String toString() {
        return "DevSampleAdminResponse[]";
    }
}
