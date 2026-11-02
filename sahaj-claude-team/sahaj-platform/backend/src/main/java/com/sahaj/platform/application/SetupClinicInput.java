package com.sahaj.platform.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Step 1 of the setup wizard: {@code SetupClinic} in {@code docs/api/platform.yaml} (SAH-4 AC7). */
public record SetupClinicInput(
        @NotBlank(message = "Enter your clinic name")
        @Size(max = 160, message = "Enter your clinic name")
        String name,

        String address,

        @NotBlank(message = "Enter the city")
        String city,

        @Pattern(regexp = "^[0-9]{6}$", message = "Enter a 6-digit PIN code")
        String pin,

        String phone,

        String registrationNo) {
}
