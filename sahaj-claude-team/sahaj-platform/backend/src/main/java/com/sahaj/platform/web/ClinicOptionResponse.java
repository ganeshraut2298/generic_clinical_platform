package com.sahaj.platform.web;

import java.util.UUID;

/** One entry of {@code AuthResult.clinics} (SAH-3 AC10, the clinic chooser). */
public record ClinicOptionResponse(UUID tenantId, String name, String area) {
}
