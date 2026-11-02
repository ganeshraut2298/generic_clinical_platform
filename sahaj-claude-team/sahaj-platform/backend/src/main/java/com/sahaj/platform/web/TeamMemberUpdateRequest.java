package com.sahaj.platform.web;

import com.sahaj.platform.application.TeamUpdateInput;
import com.sahaj.platform.domain.MembershipRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code TeamMemberUpdate} in {@code docs/api/platform.yaml}: {@code PATCH /team/{id}}. Every field is optional. */
public record TeamMemberUpdateRequest(
        @Pattern(regexp = ".*\\S.*", message = "Enter the name")
        @Size(max = 200, message = "Enter the name")
        String name,

        MembershipRole role,

        @Valid
        DoctorDetails doctor) {

    public TeamUpdateInput toInput() {
        return new TeamUpdateInput(name, role, doctor == null ? null : doctor.toInput());
    }
}
