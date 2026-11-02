package com.sahaj.platform.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Step 4 of the setup wizard: {@code SetupOpd} in {@code docs/api/platform.yaml} (SAH-4 AC10). */
public record SetupOpdInput(
        @NotBlank(message = "Choose when the fee is collected")
        @Pattern(regexp = "^(before|after|split)$", message = "Choose a valid option")
        String feeTiming,

        @NotNull(message = "Choose whether the queue uses tokens")
        Boolean queue,

        @NotNull(message = "Choose whether a nurse records vitals")
        Boolean vitals,

        @NotNull(message = "Enter how many days a follow-up stays free")
        @Min(value = 1, message = "Enter 1 to 30 days")
        @Max(value = 30, message = "Enter 1 to 30 days")
        Integer followUpDays,

        @NotBlank(message = "Choose the follow-up fee")
        @Pattern(regexp = "^(free|half|full)$", message = "Choose a valid option")
        String followUpFee) {
}
