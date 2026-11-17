package com.sahaj.ipd.application;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import com.sahaj.ipd.application.NursingProfileRepository.NewProfile;
import com.sahaj.ipd.application.NursingProfileRepository.ProfileRow;
import com.sahaj.ipd.application.NursingProfileRepository.WardProfile;
import com.sahaj.ipd.application.NursingSettingsRules.ProfileValues;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.ProfileView;
import com.sahaj.ipd.application.NursingSettingsViews.Settings;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsInput;
import com.sahaj.ipd.application.NursingSettingsViews.SettingsView;
import com.sahaj.ipd.application.NursingSettingsViews.WardProfileInput;
import com.sahaj.ipd.application.NursingSettingsViews.WardView;
import com.sahaj.ipd.domain.Drug;
import com.sahaj.platform.api.ConflictException;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.NotFoundException;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.api.TeamMemberPort;
import com.sahaj.platform.api.TeamMemberPort.TeamMember;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SAH-76-6 nursing settings and ward profiles (ADR-027 D8, M1). OWNER, DOCTOR, NURSE and PHARMACIST read; only the owner
 * writes. Every write takes the clinic IPD advisory lock (the one {@code PUT /ward-scope} uses) and no admission lock, so
 * two owners cannot interleave and the 20-profile cap holds. The starter profiles are copied from the template table on
 * the clinic's first profile or ward-profile write, in the same transaction; until then a read returns the template rows.
 * A change takes effect for later events only (verification is fixed on each order version at signing, the undo window
 * is stamped on each dose, a past record is never rewritten). Audit rows hold keys, numbers and On or Off, never a
 * clinical text. No Java code branches on a profile key or name.
 */
@Service
public class NursingSettingsService {

    static final String ENTITY_CONFIG = "IPD_CONFIG";
    static final String ENTITY_PROFILE = "IPD_NURSING_PROFILE";
    static final String ENTITY_WARD = "IPD_WARD";
    private static final Set<String> READ_ROLES = Set.of("OWNER", "DOCTOR", "NURSE", "PHARMACIST");
    private static final String READ_REFUSED = "You cannot open nursing settings.";
    private static final String OWNER_REFUSED = "Only the clinic owner can change settings.";

    private final IpdSettingsRepository settings;
    private final NursingProfileRepository profiles;
    private final WardRepository wards;
    private final IpdAudit audit;
    private final IpdGate gate;
    private final OwnerGuard ownerGuard;
    private final TeamMemberPort team;
    private final MedicineCatalogue catalogue;

    NursingSettingsService(IpdSettingsRepository settings, NursingProfileRepository profiles, WardRepository wards,
                           IpdAudit audit, IpdGate gate, OwnerGuard ownerGuard, TeamMemberPort team,
                           MedicineCatalogue catalogue) {
        this.settings = settings;
        this.profiles = profiles;
        this.wards = wards;
        this.audit = audit;
        this.gate = gate;
        this.ownerGuard = ownerGuard;
        this.team = team;
        this.catalogue = catalogue;
    }

    // ---- read ----

    /**
     * Check order: role (403), module Off (403), the role read again from the membership (403). Allowed in Read-only
     * (canEdit is then false). One REPEATABLE READ read-only transaction; writes nothing.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Settings read(UUID tenantId, UUID actor, String role) {
        MedicineOrderService.requireRole(role, READ_ROLES, READ_REFUSED);
        ModuleEntitlement ent = gate.check(tenantId, false);
        TeamMember member = team.findActiveByUserId(tenantId, actor).filter(TeamMember::active)
                .filter(m -> READ_ROLES.contains(m.role()))
                .orElseThrow(() -> MedicineOrderService.forbidden(READ_REFUSED));
        boolean canEdit = "OWNER".equals(role) && "OWNER".equals(member.role())
                && ("trial".equals(ent.ipdState()) || "on".equals(ent.ipdState()));
        return view(tenantId, canEdit);
    }

    // ---- writes ----

    /** Saves the clinic settings. The same values write and audit nothing. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings saveSettings(UUID tenantId, UUID actor, String role, SettingsInput input) {
        MedicineOrderService.requireRole(role, Set.of("OWNER"), OWNER_REFUSED);
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        NursingSettings next = NursingSettingsRules.settings(input);
        wards.lockTenant(tenantId);
        NursingSettings current = settings.findNursingSettings(tenantId).orElse(NursingSettings.DEFAULTS);
        if (!next.equals(current)) {
            settings.upsertNursingSettings(tenantId, next, actor, gate.now());
            audit.record("IPD_NURSING_SETTINGS_CHANGED", ENTITY_CONFIG, null, snapshot(current), snapshot(next));
        }
        return view(tenantId, true);
    }

    /** Adds a ward profile (201 at the controller). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings addProfile(UUID tenantId, UUID actor, String role, ProfileInput input) {
        MedicineOrderService.requireRole(role, Set.of("OWNER"), OWNER_REFUSED);
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        ProfileValues values = NursingSettingsRules.profile(input);
        wards.lockTenant(tenantId);
        copyStartersWhenNeeded(tenantId);
        if (profiles.findProfiles(tenantId).size() >= NursingSettingsRules.MAX_PROFILES) {
            throw new ConflictException("profile-limit", "Too many profiles",
                    "A clinic can have up to " + NursingSettingsRules.MAX_PROFILES + " profiles.");
        }
        requireNameFree(tenantId, values.name(), null);
        String key = NursingSettingsRules.key(values.name(), k -> profiles.keyTaken(tenantId, k));
        NewProfile created = new NewProfile(key, values.name(), values.medVerificationRequired(),
                values.vitalsEveryMinutes(), values.doseWindowBeforeMin(), values.doseWindowAfterMin(),
                NursingSettingsRules.DEFAULT_SORT);
        profiles.insert(tenantId, created);
        audit.record("IPD_NURSING_PROFILE_SAVED", ENTITY_PROFILE, null, null, snapshot(created));
        return view(tenantId, true);
    }

    /** Changes a ward profile. The same values write and audit nothing. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings changeProfile(UUID tenantId, UUID actor, String role, String key, ProfileInput input) {
        MedicineOrderService.requireRole(role, Set.of("OWNER"), OWNER_REFUSED);
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        ProfileValues values = NursingSettingsRules.profile(input);
        wards.lockTenant(tenantId);
        if (!NursingSettingsRules.validKey(key)) {
            throw new NotFoundException("Profile not found");
        }
        copyStartersWhenNeeded(tenantId);
        ProfileRow before = profiles.findProfile(tenantId, key)
                .orElseThrow(() -> new NotFoundException("Profile not found"));
        requireNameFree(tenantId, values.name(), key);
        NewProfile next = new NewProfile(key, values.name(), values.medVerificationRequired(),
                values.vitalsEveryMinutes(), values.doseWindowBeforeMin(), values.doseWindowAfterMin(),
                before.sortOrder());
        if (!same(before, next)) {
            if (!profiles.update(tenantId, key, next, actor, gate.now())) {
                throw new IllegalStateException("The profile was not changed");
            }
            audit.record("IPD_NURSING_PROFILE_SAVED", ENTITY_PROFILE, null, snapshot(before), snapshot(next));
        }
        return view(tenantId, true);
    }

    /** Chooses the profile of a ward (null key: the clinic defaults). The same key writes and audits nothing. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings setWardProfile(UUID tenantId, UUID actor, String role, UUID wardId, WardProfileInput input) {
        MedicineOrderService.requireRole(role, Set.of("OWNER"), OWNER_REFUSED);
        ownerGuard.requireOwner(tenantId, actor);
        gate.check(tenantId, true);
        wards.lockTenant(tenantId);
        wards.findWard(tenantId, wardId).orElseThrow(() -> new NotFoundException("Ward not found"));
        String key = input == null ? null : input.profileKey();
        if (key != null) {
            if (!NursingSettingsRules.validKey(key)) {
                throw new InvalidInputException("profileKey", NursingSettingsRules.PROFILE_MESSAGE);
            }
            copyStartersWhenNeeded(tenantId);
            if (profiles.findProfile(tenantId, key).isEmpty()) {
                throw new InvalidInputException("profileKey", NursingSettingsRules.PROFILE_MESSAGE);
            }
        }
        String before = null;
        for (WardProfile w : profiles.findWardProfiles(tenantId)) {
            if (w.wardId().equals(wardId)) {
                before = w.profileKey();
            }
        }
        if (!java.util.Objects.equals(before, key)) {
            if (!profiles.setWardProfile(tenantId, wardId, key)) {
                throw new IllegalStateException("The ward profile was not changed");
            }
            Map<String, Object> was = new LinkedHashMap<>();
            was.put("wardId", wardId);
            was.put("profileKey", before);
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("wardId", wardId);
            now.put("profileKey", key);
            audit.record("IPD_WARD_PROFILE_CHANGED", ENTITY_WARD, wardId, was, now);
        }
        return view(tenantId, true);
    }

    // ---- helpers ----

    /** The starter rows are copied once, on the clinic's first profile or ward-profile write. */
    private void copyStartersWhenNeeded(UUID tenantId) {
        if (!profiles.hasProfiles(tenantId)) {
            profiles.copyTemplates(tenantId);
        }
    }

    private void requireNameFree(UUID tenantId, String name, String exceptKey) {
        if (profiles.nameTaken(tenantId, name, exceptKey)) {
            throw new ConflictException("profile-name-taken", "Profile name taken",
                    "A profile with this name already exists.");
        }
    }

    private static boolean same(ProfileRow before, NewProfile next) {
        return before.name().equals(next.name()) && before.medVerificationRequired() == next.medVerificationRequired()
                && before.vitalsEveryMinutes() == next.vitalsEveryMinutes()
                && java.util.Objects.equals(before.doseWindowBeforeMin(), next.doseWindowBeforeMin())
                && java.util.Objects.equals(before.doseWindowAfterMin(), next.doseWindowAfterMin());
    }

    /** The whole answer, read after the writes in the same transaction. */
    private Settings view(UUID tenantId, boolean canEdit) {
        NursingSettings clinic = settings.findNursingSettings(tenantId).orElse(NursingSettings.DEFAULTS);
        boolean saved = profiles.hasProfiles(tenantId);
        List<ProfileRow> rows = saved ? profiles.findProfiles(tenantId) : profiles.findTemplates();
        Map<String, Integer> counts = profiles.countWardsPerProfile(tenantId);
        List<ProfileView> profileViews = new ArrayList<>();
        for (ProfileRow p : rows) {
            profileViews.add(new ProfileView(p.key(), p.name(), !saved, p.medVerificationRequired(),
                    p.vitalsEveryMinutes(), p.doseWindowBeforeMin(), p.doseWindowAfterMin(),
                    counts.getOrDefault(p.key(), 0)));
        }
        List<WardView> wardViews = new ArrayList<>();
        for (WardProfile w : profiles.findWardProfiles(tenantId)) {
            wardViews.add(new WardView(w.wardId(), w.wardName(), w.profileKey()));
        }
        List<KeyLabel> highAlert = new ArrayList<>();
        List<KeyLabel> timeCritical = new ArrayList<>();
        for (Drug d : catalogue.catalogue().drugs()) {
            if (d.highAlert() != null) {
                highAlert.add(new KeyLabel(d.key(), d.name() + " · " + d.highAlert()));
            }
            if (d.timeCritical()) {
                timeCritical.add(new KeyLabel(d.key(), d.name()));
            }
        }
        return new Settings(canEdit, new SettingsView(clinic.medVerificationRequired(), clinic.doseWindowBeforeMin(),
                clinic.doseWindowAfterMin(), clinic.statWindowMin(), clinic.unackFlagMin(), clinic.unackFlagStatMin(),
                clinic.undoSeconds()), List.copyOf(profileViews), List.copyOf(wardViews), List.copyOf(highAlert),
                List.copyOf(timeCritical));
    }

    private static Map<String, Object> snapshot(NursingSettings s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("medVerificationRequired", s.medVerificationRequired());
        map.put("doseWindowBeforeMinutes", s.doseWindowBeforeMin());
        map.put("doseWindowAfterMinutes", s.doseWindowAfterMin());
        map.put("statWindowMinutes", s.statWindowMin());
        map.put("unackFlagMinutes", s.unackFlagMin());
        map.put("unackFlagStatMinutes", s.unackFlagStatMin());
        map.put("undoSeconds", s.undoSeconds());
        return map;
    }

    private static Map<String, Object> snapshot(ProfileRow p) {
        return profileMap(p.key(), p.name(), p.medVerificationRequired(), p.vitalsEveryMinutes(),
                p.doseWindowBeforeMin(), p.doseWindowAfterMin());
    }

    private static Map<String, Object> snapshot(NewProfile p) {
        return profileMap(p.key(), p.name(), p.medVerificationRequired(), p.vitalsEveryMinutes(),
                p.doseWindowBeforeMin(), p.doseWindowAfterMin());
    }

    private static Map<String, Object> profileMap(String key, String name, boolean verification, int vitals,
                                                  Integer before, Integer after) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profileKey", key);
        map.put("name", name);
        map.put("medVerificationRequired", verification);
        map.put("vitalsEveryMinutes", vitals);
        map.put("doseWindowBeforeMinutes", before);
        map.put("doseWindowAfterMinutes", after);
        return map;
    }

}
