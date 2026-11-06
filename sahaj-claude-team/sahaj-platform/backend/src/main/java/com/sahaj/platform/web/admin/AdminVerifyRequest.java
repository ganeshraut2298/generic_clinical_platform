package com.sahaj.platform.web.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Contract schema TotpVerifyInput: the pre-auth token and exactly one of {@code code} and {@code recoveryCode}. */
public record AdminVerifyRequest(
        @NotNull(message = "Start the sign-in again")
        @Pattern(regexp = "^[A-Za-z0-9_-]{43}$", message = "Start the sign-in again")
        String preAuthToken,
        @Pattern(regexp = "^[0-9]{6}$", message = "Enter the 6-digit code from your authenticator app")
        String code,
        @Pattern(regexp = "^[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}$",
                message = "Enter the recovery code as XXXX-XXXX-XXXX-XXXX")
        String recoveryCode) {

    @Override
    public String toString() {
        return "AdminVerifyRequest[]";
    }
}
