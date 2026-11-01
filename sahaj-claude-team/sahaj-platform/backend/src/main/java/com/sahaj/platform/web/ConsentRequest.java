package com.sahaj.platform.web;

import jakarta.validation.constraints.NotBlank;

/** The consent object inside {@code OtpVerifyRequest} (SAH-3 AC12). */
public record ConsentRequest(
        @NotBlank(message = "Terms version is required")
        String termsVersion,

        @NotBlank(message = "Privacy version is required")
        String privacyVersion) {
}
