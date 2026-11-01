package com.sahaj.platform.web;

import com.sahaj.platform.domain.BestTime;
import com.sahaj.platform.domain.EnquirySource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/platform/public/sales-enquiries}, as in {@code docs/api/platform.yaml}.
 * It has no tenant field; a {@code tenantId} sent by a client is not read.
 */
public record SalesEnquiryRequest(
        @NotBlank(message = "Enter your name")
        @Size(max = 120, message = "Name must be 120 characters or fewer")
        @Pattern(regexp = "^[^\\p{Cc}]*$", message = "Remove the unusual characters from this field")
        String name,

        @Size(max = 200, message = "Clinic name and city must be 200 characters or fewer")
        @Pattern(regexp = "^[^\\p{Cc}]*$", message = "Remove the unusual characters from this field")
        String clinicCity,

        @NotNull(message = "Enter a 10-digit mobile number")
        @Pattern(regexp = "^[6-9][0-9]{9}$", message = "Enter a valid 10-digit mobile number")
        String mobile,

        BestTime bestTime,

        @NotNull(message = "Source is required")
        EnquirySource source) {

    /** Leaves the mobile number out, so a log line or exception message cannot carry it. */
    @Override
    public String toString() {
        return "SalesEnquiryRequest[source=" + source + "]";
    }
}
