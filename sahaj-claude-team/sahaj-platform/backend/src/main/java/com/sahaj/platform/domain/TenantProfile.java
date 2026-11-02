package com.sahaj.platform.domain;

import java.time.Instant;

/** The bound tenant's own identity for the header and the app shell (SAH-5 {@code GET /me}). */
public record TenantProfile(String name, String brand, String plan, Instant planEndsAt) {
}
