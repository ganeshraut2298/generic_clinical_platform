package com.sahaj.ipd.application;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.NursingProfileRepository.ProfileRow;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-76-6 (ADR-027 D8, D10) the real {@link NursingSettingsReader}: for an admission, the profile of the ward of the
 * current bed wins where it sets a value (verification, vitals interval, and the dose windows when it has them), else the
 * clinic settings of {@code ipd.config}, else the built defaults (vitals every 240 minutes). No Java code branches on a
 * profile key or name. Two statements for any number of admissions (one for the clinic row, one for the profiles).
 * Read-only: it joins the caller's transaction.
 */
@Component
class NursingSettingsResolver implements NursingSettingsReader {

    /** The vitals interval of a ward without a profile (SAH-30, 4 hours). */
    static final int DEFAULT_VITALS_MINUTES = 240;

    private final IpdSettingsRepository settings;
    private final NursingProfileRepository profiles;

    NursingSettingsResolver(IpdSettingsRepository settings, NursingProfileRepository profiles) {
        this.settings = settings;
        this.profiles = profiles;
    }

    @Override
    public EffectiveNursingSettings effectiveFor(UUID tenantId, UUID admissionId) {
        return effectiveForAll(tenantId, java.util.List.of(admissionId)).get(admissionId);
    }

    @Override
    public Map<UUID, EffectiveNursingSettings> effectiveForAll(UUID tenantId, Collection<UUID> admissionIds) {
        NursingSettings clinic = settings.findNursingSettings(tenantId).orElse(NursingSettings.DEFAULTS);
        Map<UUID, ProfileRow> byAdmission = profiles.findProfilesOfAdmissions(tenantId,
                new LinkedHashSet<>(admissionIds));
        Map<UUID, EffectiveNursingSettings> out = new LinkedHashMap<>();
        for (UUID id : admissionIds) {
            out.put(id, combine(clinic, byAdmission.get(id)));
        }
        return out;
    }

    @Override
    public EffectiveNursingSettings clinicDefaults(UUID tenantId) {
        return combine(settings.findNursingSettings(tenantId).orElse(NursingSettings.DEFAULTS), null);
    }

    /** Pure: the profile (or null) over the clinic settings. */
    static EffectiveNursingSettings combine(NursingSettings clinic, ProfileRow profile) {
        if (profile == null) {
            return new EffectiveNursingSettings(clinic.medVerificationRequired(), clinic.doseWindowBeforeMin(),
                    clinic.doseWindowAfterMin(), clinic.statWindowMin(), clinic.unackFlagMin(),
                    clinic.unackFlagStatMin(), clinic.undoSeconds(), DEFAULT_VITALS_MINUTES);
        }
        int before = profile.doseWindowBeforeMin() != null ? profile.doseWindowBeforeMin() : clinic.doseWindowBeforeMin();
        int after = profile.doseWindowAfterMin() != null ? profile.doseWindowAfterMin() : clinic.doseWindowAfterMin();
        return new EffectiveNursingSettings(profile.medVerificationRequired(), before, after, clinic.statWindowMin(),
                clinic.unackFlagMin(), clinic.unackFlagStatMin(), clinic.undoSeconds(),
                profile.vitalsEveryMinutes());
    }
}
