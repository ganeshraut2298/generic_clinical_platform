package com.sahaj.platform.web.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Contract schema TotpEnrolInput. */
public record AdminEnrolRequest(
        @NotNull(message = "Start the sign-in again")
        @Pattern(regexp = "^[A-Za-z0-9_-]{43}$", message = "Start the sign-in again")
        String preAuthToken) {

    @Override
    public String toString() {
        return "AdminEnrolRequest[]";
    }
}
