package com.sahaj.platform.web;

import com.sahaj.platform.domain.AuthMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body of {@code POST /api/v1/platform/auth/otp/verify} (SAH-3). */
public record OtpVerifyRequest(
        @NotNull(message = "Enter a 10-digit mobile number")
        @Pattern(regexp = "^[6-9][0-9]{9}$", message = "Enter a 10-digit mobile number")
        String mobile,

        @NotNull(message = "Enter the 6-digit code")
        @Pattern(regexp = "^[0-9]{6}$", message = "Enter the 6-digit code")
        String code,

        @NotNull(message = "Choose log in or new clinic")
        AuthMode mode,

        @Valid
        ConsentRequest consent) {

    /** Never includes the mobile number or the code in a log line or exception message. */
    @Override
    public String toString() {
        return "OtpVerifyRequest[mode=" + mode + "]";
    }
}
