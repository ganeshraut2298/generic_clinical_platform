package com.sahaj.platform.domain;

import java.util.UUID;

/** The minimum a clinic chooser needs to show one clinic (SAH-3, AuthResult.clinics). */
public record TenantSummary(UUID tenantId, String name) {
}
