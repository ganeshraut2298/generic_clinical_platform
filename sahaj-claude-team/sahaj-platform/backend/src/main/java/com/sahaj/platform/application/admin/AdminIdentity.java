package com.sahaj.platform.application.admin;

import java.util.UUID;

/** The signed-in admin as the shell shows them (contract schema AdminMe). Phase 1 has one role. */
public record AdminIdentity(UUID id, String name) {

    public static final String ROLE = "PLATFORM_ADMIN";
    public static final String ROLE_LABEL = "Platform admin";
}
