package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps role and menu mode to allowed menu keys (SAH-5 technical design, "Proposed role menu").
 * The server stays the source of truth: {@code GET /me} returns exactly this list, and every
 * later API checks the role itself (AC1, AC11) - the menu is never the only protection.
 *
 * <p>The base menu below is the OPD screens. SAH-20 adds the module-aware overload: when HRA is on,
 * {@code staff} is added for every clinic role (before {@code config} when the role has it). The
 * counts in {@code menuCounts} stay on the base menu. There is
 * nothing else to filter by module yet.
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

    /**
     * SAH-31 (ADR-009 D11, DEC-017 Q2): PHARMACIST also gets {@code ipd}, placed after {@code today}; the page shows
     * only the Safety tab (that is a frontend matter).
     */
    private static final Set<MembershipRole> IPD_ROLES = Set.of(MembershipRole.OWNER, MembershipRole.FRONT_DESK,
            MembershipRole.NURSE, MembershipRole.DOCTOR, MembershipRole.PHARMACIST);

    /** SAH-28: who sees the menu item Emergency (mockup default menu; DOCTOR reaches the queue through the tab). */
    private static final Set<MembershipRole> EMERGENCY_ROLES = Set.of(MembershipRole.OWNER, MembershipRole.FRONT_DESK,
            MembershipRole.NURSE);

    /**
     * SAH-66 (ADR-023 D6, H7, D13.5): who sees Second checks. NURSE, PHARMACIST, DOCTOR and VISITING_DOCTOR always; OWNER
     * only when the owner's membership has doctor details (the checker roles of the chemotherapy second check).
     */
    private static final Set<MembershipRole> SECOND_CHECK_ROLES = Set.of(MembershipRole.NURSE,
            MembershipRole.PHARMACIST, MembershipRole.DOCTOR, MembershipRole.VISITING_DOCTOR);

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

    /** SAH-20: the base menu plus {@code staff} for every clinic role when the HRA add-on is on. */
    public static List<String> menuFor(MembershipRole role, String menuMode, boolean hraOn) {
        return menuFor(role, menuMode, new ModuleFlags(hraOn, false, false));
    }

    /**
     * SAH-23: module-aware menu. {@code ipd} (Beds and admissions) is added for OWNER, FRONT_DESK, NURSE and
     * DOCTOR (SAH-31: and PHARMACIST, after {@code today}) when IPD is not off: after {@code followups} in the advanced menu, before {@code config} in the
     * simple one (mockup order); SAH-28 adds {@code emergency} right before {@code ipd} for OWNER, FRONT_DESK and NURSE
     * in the advanced menu while IPD is Trial or On; then {@code staff} when HRA is on.
     */
    public static List<String> menuFor(MembershipRole role, String menuMode, ModuleFlags flags) {
        return menuFor(role, menuMode, flags, false);
    }

    /**
     * The full menu. {@code ownerHasDoctorDetails} matters for OWNER only: Second checks (key {@code secondchecks}) is added
     * right after {@code queue} when the role has it, else at the end of the list; it is applied after ipd and emergency and
     * before staff, in both menu modes, with no module condition (SAH-66).
     */
    public static List<String> menuFor(MembershipRole role, String menuMode, ModuleFlags flags,
                                       boolean ownerHasDoctorDetails) {
        List<String> menu = new ArrayList<>(menuFor(role, menuMode));
        if (flags.ipdOn() && IPD_ROLES.contains(role) && !menu.contains("ipd")) {
            int at = "simple".equals(menuMode) ? menu.indexOf("config") : menu.indexOf("followups") + 1;
            if (at <= 0 && !"simple".equals(menuMode)) {
                at = menu.indexOf("config");
            }
            menu.add(at < 0 ? menu.size() : at, "ipd");
        }
        // SAH-28: Emergency sits right before Beds and admissions, in the advanced menu only, and only while IPD can
        // be written (Trial or On); in Read-only the item is not shown.
        if (flags.ipdWritable() && !"simple".equals(menuMode) && EMERGENCY_ROLES.contains(role)
                && menu.contains("ipd") && !menu.contains("emergency")) {
            menu.add(menu.indexOf("ipd"), "emergency");
        }
        boolean checker = SECOND_CHECK_ROLES.contains(role) || (role == MembershipRole.OWNER && ownerHasDoctorDetails);
        if (checker && !menu.contains("secondchecks")) {
            int queue = menu.indexOf("queue");
            menu.add(queue < 0 ? menu.size() : queue + 1, "secondchecks");
        }
        if (flags.hraOn() && !menu.contains("staff")) {
            int at = menu.indexOf("config");
            menu.add(at < 0 ? menu.size() : at, "staff");
        }
        return List.copyOf(menu);
    }

    /**
     * Which add-on modules are switched on for the clinic; {@code ipdOn} is true for trial, on and read-only,
     * {@code ipdWritable} (SAH-28) only for trial and on.
     */
    public record ModuleFlags(boolean hraOn, boolean ipdOn, boolean ipdWritable) {
    }
}
