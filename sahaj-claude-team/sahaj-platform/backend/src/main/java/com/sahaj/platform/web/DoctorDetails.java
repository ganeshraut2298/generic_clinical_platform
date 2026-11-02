package com.sahaj.platform.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahaj.platform.application.DoctorInput;
import com.sahaj.platform.application.TeamMemberView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** {@code DoctorDetails} in {@code docs/api/platform.yaml} (SAH-6), used in both requests and responses. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DoctorDetails(
        @Size(max = 120, message = "Keep this under 120 characters")
        String specialty,

        @Size(max = 120, message = "Keep this under 120 characters")
        String degree,

        @Size(max = 80, message = "Keep this under 80 characters")
        String registrationNo,

        @Min(value = 0, message = "Enter a fee of 0 or more")
        @Max(value = 10_000_000, message = "Enter a smaller fee")
        Integer feeRupees) {

    public DoctorInput toInput() {
        return new DoctorInput(specialty, degree, registrationNo, feeRupees);
    }

    static DoctorDetails from(TeamMemberView.DoctorView view) {
        return view == null ? null
                : new DoctorDetails(view.specialty(), view.degree(), view.registrationNo(), view.feeRupees());
    }
}
