package com.sahaj.platform.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body of {@code POST /api/v1/platform/auth/select-clinic} (SAH-3 AC10). */
public record SelectClinicRequest(
        @NotBlank(message = "Choose your clinic again")
        String selectionToken,

        @NotNull(message = "Choose a clinic")
        UUID tenantId) {

    @Override
    public String toString() {
        return "SelectClinicRequest[tenantId=" + tenantId + "]";
    }
}
