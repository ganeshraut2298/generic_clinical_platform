package com.sahaj.platform.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Step 3 of the setup wizard: {@code SetupTeam} in {@code docs/api/platform.yaml} (SAH-4 AC9). */
public record SetupTeamInput(
        @NotNull(message = "Add at least one doctor")
        @Size(max = 2, message = "The free plan allows up to 2 doctors")
        @Valid
        List<Doctor> doctors,

        @NotNull(message = "Add your staff, or leave this list empty")
        @Valid
        List<Staff> staff) {

    /** The first entry is always the owner (SAH-4 AC9); later entries may carry a mobile number. */
    public record Doctor(
            @NotBlank(message = "Enter the doctor's name")
            String name,

            @NotBlank(message = "Enter the specialty")
            String specialty,

            @NotNull(message = "Enter the consultation fee")
            @PositiveOrZero(message = "Enter a valid fee")
            Integer feeRupees,

            // Optional: the first doctor is the owner and logs in with their own number, so the
            // wizard sends an empty value. A second doctor must give a real number (see SetupService).
            @Pattern(regexp = "^([6-9][0-9]{9})?$", message = "Enter a 10-digit mobile number")
            String mobile) {
    }

    public record Staff(
            @NotBlank(message = "Enter the staff member's name")
            String name,

            @NotBlank(message = "Choose a role")
            @Pattern(regexp = "^(DOCTOR|VISITING_DOCTOR|NURSE|FRONT_DESK|BILLING|PHARMACIST|COUNSELLOR)$",
                    message = "Choose a valid role")
            String role,

            @NotNull(message = "Enter a 10-digit mobile number")
            @Pattern(regexp = "^[6-9][0-9]{9}$", message = "Enter a 10-digit mobile number")
            String mobile) {
    }
}
