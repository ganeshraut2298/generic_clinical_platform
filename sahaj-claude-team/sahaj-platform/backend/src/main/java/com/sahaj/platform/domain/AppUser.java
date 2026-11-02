package com.sahaj.platform.domain;

import java.util.UUID;

/**
 * A person, identified by a 10-digit mobile number (SAH-1, {@code platform.app_user}). Global:
 * not tied to any one tenant. Never log {@link #mobile()} or {@link #fullName()}.
 */
public record AppUser(UUID id, String mobile, String fullName) {
}
