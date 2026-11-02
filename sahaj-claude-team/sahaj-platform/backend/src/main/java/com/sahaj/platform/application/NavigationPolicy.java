package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps role and menu mode to allowed menu keys (SAH-5 technical design, "Proposed role menu").
 * The server stays the source of truth: {@code GET /me} returns exactly this list, and every
 * later API checks the role itself (AC1, AC11) - the menu is never the only protection.
 *
 * <p>Modules for now: OPD on, IPD and HRA off, so every key below is an OPD screen; there is
 * nothing yet to filter by module.
 */
public final class NavigationPolicy {

    private static final Map<MembershipRole, List<String>> ADVANCED_MENU = Map.of(
            MembershipRole.OWNER, List.of("today", "queue", "appointments", "patients", "billing", "revenue",
                    "followups", "reports", "config"),
            MembershipRole.DOCTOR, List.of("today", "queue", "appointments", "patients", "followups"),
            MembershipRole.VISITING_DOCTOR, List.of("today", "queue", "appointments", "patients", "followups"),
            MembershipRole.NURSE, List.of("today", "queue", "patients"),
            MembershipRole.FRONT_DESK, List.of("today", "queue", "appointments", "patients", "billing", "followups"),
            MembershipRole.BILLING, List.of("today", "billing"),
            MembershipRole.PHARMACIST, List.of("today"),
            MembershipRole.COUNSELLOR, List.of("today", "queue", "patients"));

    private static final List<String> SIMPLE_MENU_ORDER =
            List.of("today", "queue", "appointments", "patients", "billing", "config");

    private NavigationPolicy() {
    }

    /** {@code menuMode} is {@code simple} or {@code advanced} (SAH-5 AC6). */
    public static List<String> menuFor(MembershipRole role, String menuMode) {
        List<String> advanced = ADVANCED_MENU.getOrDefault(role, List.of());
        if (!"simple".equals(menuMode)) {
            return advanced;
        }
        Set<String> allowed = new LinkedHashSet<>(advanced);
        return SIMPLE_MENU_ORDER.stream().filter(allowed::contains).toList();
    }
}
