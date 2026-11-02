package com.sahaj.platform.web;

import java.util.List;

/** {@code GET /setup/slug-availability} in {@code docs/api/platform.yaml} (SAH-4 AC3, AC11). */
public record SlugAvailabilityResponse(boolean available, List<String> alternatives) {
}
