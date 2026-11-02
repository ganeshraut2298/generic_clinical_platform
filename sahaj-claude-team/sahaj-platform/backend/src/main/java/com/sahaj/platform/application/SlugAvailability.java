package com.sahaj.platform.application;

import java.util.List;

/** Result of {@code GET /setup/slug-availability} (SAH-4 AC3, AC11). */
public record SlugAvailability(boolean available, List<String> alternatives) {
}
