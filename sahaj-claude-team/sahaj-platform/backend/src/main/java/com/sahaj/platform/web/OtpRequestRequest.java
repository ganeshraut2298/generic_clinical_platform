package com.sahaj.platform.web;

import com.sahaj.platform.domain.AuthMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /api/v1/platform/auth/otp/request} (SAH-3). */
public record OtpRequestRequest(
        @NotNull(message = "Enter a 10-digit mobile number")
        @Pattern(regexp = "^[6-9][0-9]{9}$", message = "Enter a 10-digit mobile number")
        String mobile,

        @NotNull(message = "Choose log in or new clinic")
        AuthMode mode) {

    /** Never includes the mobile number in a log line or exception message. */
    @Override
    public String toString() {
        return "OtpRequestRequest[mode=" + mode + "]";
    }
}
