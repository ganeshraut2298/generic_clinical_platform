package com.sahaj.platform.application;

import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.SetupProgress;
import com.sahaj.platform.domain.TenantProfile;
import com.sahaj.platform.domain.UserPreference;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-5: builds the current user's shell - who they are, which clinic, what they are allowed to
 * see, and their own saved look and feel. The server stays the source of truth for the menu
 * (AC1, AC11): the frontend never computes it.
 */
@Service
public class MeService {

    private final TeamRepository teamRepository;
    private final TenantRepository tenantRepository;
    private final ClinicProfileRepository clinicProfileRepository;
    private final SetupProgressRepository setupProgressRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final ModuleEntitlementRepository moduleEntitlementRepository;
    private final Clock clock;

    public MeService(TeamRepository teamRepository, TenantRepository tenantRepository,
                      ClinicProfileRepository clinicProfileRepository, SetupProgressRepository setupProgressRepository,
                      UserPreferenceRepository userPreferenceRepository,
                      ModuleEntitlementRepository moduleEntitlementRepository, Clock clock) {
        this.moduleEntitlementRepository = moduleEntitlementRepository;
        this.teamRepository = teamRepository;
        this.tenantRepository = tenantRepository;
        this.clinicProfileRepository = clinicProfileRepository;
        this.setupProgressRepository = setupProgressRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeView getMe(UUID tenantId, UUID userId, String roleName) {
        return build(tenantId, userId, roleName);
    }

    /** AC2, AC3: every field is optional; only the fields present in {@code input} change. */
    @Transactional
    public MeView updatePreferences(UUID tenantId, UUID userId, String roleName, PreferencesInput input) {
        UserPreference current = userPreferenceRepository.find(tenantId, userId).orElse(UserPreference.defaults());
        UserPreference merged = new UserPreference(
                input.language() != null ? input.language() : current.language(),
                input.theme() != null ? input.theme() : current.theme(),
                input.menuMode() != null ? input.menuMode() : current.menuMode(),
                input.sidebarCollapsed() != null ? input.sidebarCollapsed() : current.sidebarCollapsed());
        userPreferenceRepository.upsert(tenantId, userId, merged);
        return build(tenantId, userId, roleName);
    }

    private MeView build(UUID tenantId, UUID userId, String roleName) {
        TeamRepository.TeamRow member = teamRepository.findByUser(tenantId, userId)
                .orElseThrow(() -> new IllegalStateException("The signed-in user was not found"));
        MembershipRole role = MembershipRole.valueOf(roleName);
        TenantProfile tenantProfile = tenantRepository.findProfile(tenantId)
                .orElseThrow(() -> new IllegalStateException("The signed-in clinic was not found"));
        SetupProgress progress = setupProgressRepository.find(tenantId).orElse(new SetupProgress(1, java.util.Map.of(), null));
        UserPreference preferences = userPreferenceRepository.find(tenantId, userId).orElse(UserPreference.defaults());
        String area = clinicProfileRepository.findCity(tenantId).orElse(null);
        String brandHex = "custom".equals(tenantProfile.brand())
                ? clinicProfileRepository.find(tenantId).map(ClinicProfileRepository.ProfileRow::brandHex).orElse(null)
                : null;

        boolean setupComplete = progress.isComplete();
        int setupPercent = setupComplete ? 100 : Math.min(100, Math.max(0, (progress.currentStep() - 1) * 20));
        Integer planDaysLeft = tenantProfile.planEndsAt() == null ? null
                : (int) Math.max(0, Duration.between(clock.instant(), tenantProfile.planEndsAt()).toDays());

        MeView.Clinic clinic = new MeView.Clinic(tenantProfile.name(), area, tenantProfile.brand(),
                brandHex, tenantProfile.plan(), planDaysLeft, setupComplete, setupPercent);
        NavigationPolicy.ModuleFlags flags = moduleEntitlementRepository.find(tenantId)
                .map(row -> flagsOf(row, ModuleEntitlements.effectiveIpdState(row, clock.instant())))
                .orElse(new NavigationPolicy.ModuleFlags(false, false, false));
        return new MeView(userId, member.id(), member.name(), role.name(), clinic,
                NavigationPolicy.menuFor(role, preferences.menuMode(), flags, member.hasDoctorProfile()), preferences);
    }

    /** SAH-28: IPD is writable (menu item Emergency) in Trial and On, not in Read-only. */
    private static NavigationPolicy.ModuleFlags flagsOf(ModuleEntitlementRepository.EntitlementRow row, String state) {
        return new NavigationPolicy.ModuleFlags(row.hraOn(), !"off".equals(state),
                "trial".equals(state) || "on".equals(state));
    }
}
