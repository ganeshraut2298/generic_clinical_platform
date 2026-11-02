package com.sahaj.platform.web;

import com.sahaj.platform.application.TeamAddInput;
import com.sahaj.platform.domain.MembershipRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code TeamMemberCreate} in {@code docs/api/platform.yaml}: {@code POST /team} (SAH-6 AC7). */
public record TeamMemberCreateRequest(
        @NotBlank(message = "Enter the name")
        @Size(max = 200, message = "Enter the name")
        String name,

        @NotNull(message = "Enter a 10-digit mobile number")
        @Pattern(regexp = "^[6-9][0-9]{9}$", message = "Enter a 10-digit mobile number")
        String mobile,

        @NotNull(message = "Choose a role")
        MembershipRole role,

        @Valid
        DoctorDetails doctor) {

    public TeamAddInput toInput() {
        return new TeamAddInput(name, mobile, role, doctor == null ? null : doctor.toInput());
    }
}
