package com.sahaj.ipd.application;

import com.sahaj.ipd.domain.EffectiveNursingSettings;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** SAH-76 (ADR-027 D8): reads the nursing values in force for an admission. Owner: WP4. */
public interface NursingSettingsReader {

    /** The profile of the ward of the admission's current bed, else the clinic settings, else the defaults. */
    EffectiveNursingSettings effectiveFor(UUID tenantId, UUID admissionId);

    /** The clinic-level values (no ward profile), for a read with no admission (new order in a new context, summary). */
    EffectiveNursingSettings clinicDefaults(UUID tenantId);

    /**
     * The same for many admissions (the Medicine round, the card counts). Every id in {@code admissionIds} is a key of the
     * answer. The real reader overrides this with a fixed number of statements; this default asks one by one, for a
     * reader (a test double) that only knows {@link #effectiveFor}.
     */
    default Map<UUID, EffectiveNursingSettings> effectiveForAll(UUID tenantId, Collection<UUID> admissionIds) {
        Map<UUID, EffectiveNursingSettings> all = new LinkedHashMap<>();
        for (UUID id : admissionIds) {
            all.put(id, effectiveFor(tenantId, id));
        }
        return all;
    }
}
