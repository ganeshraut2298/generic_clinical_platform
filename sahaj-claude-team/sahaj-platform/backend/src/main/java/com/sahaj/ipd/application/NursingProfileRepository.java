package com.sahaj.ipd.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SAH-76-6 (ADR-027 D8) {@code ipd.nursing_profile}, its global template table {@code ipd.nursing_profile_template},
 * and the profile key of each ward ({@code ipd.ward.nursing_profile_key}). Every tenant method is scoped to the tenant.
 * The starter rows are copied from the templates only by {@link #copyTemplates}, on the clinic's first profile or ward
 * profile write, under the clinic IPD lock. There is no delete: a profile in use must not vanish.
 */
public interface NursingProfileRepository {

    /** True when the clinic has any {@code ipd.nursing_profile} row. */
    boolean hasProfiles(UUID tenantId);

    /** The clinic's profiles, {@code sort_order} then name. */
    List<ProfileRow> findProfiles(UUID tenantId);

    /** The global starter rows, {@code sort_order}. */
    List<ProfileRow> findTemplates();

    Optional<ProfileRow> findProfile(UUID tenantId, String key);

    /** The profile of the ward of each admission's current bed; an admission whose ward has no profile is absent. */
    Map<UUID, ProfileRow> findProfilesOfAdmissions(UUID tenantId, Collection<UUID> admissionIds);

    /** Copies the template rows into the clinic's table. Only when {@link #hasProfiles} is false. */
    void copyTemplates(UUID tenantId);

    /** True when another profile of the clinic has this name, ignoring case; {@code exceptKey} is left out (null: none). */
    boolean nameTaken(UUID tenantId, String name, String exceptKey);

    boolean keyTaken(UUID tenantId, String key);

    void insert(UUID tenantId, NewProfile profile);

    /** Updates the profile; false when the key is not a profile of the clinic. */
    boolean update(UUID tenantId, String key, NewProfile profile, UUID actor, Instant now);

    /** Each ward of the clinic with its profile key (null: clinic defaults), creation order. */
    List<WardProfile> findWardProfiles(UUID tenantId);

    /** Number of wards on each profile key. */
    Map<String, Integer> countWardsPerProfile(UUID tenantId);

    /** Sets the ward's profile key (null = clinic defaults). False when the ward does not exist. */
    boolean setWardProfile(UUID tenantId, UUID wardId, String profileKey);

    /** One profile row. The window overrides are null when the clinic setting applies. */
    record ProfileRow(String key, String name, boolean medVerificationRequired, int vitalsEveryMinutes,
                      Integer doseWindowBeforeMin, Integer doseWindowAfterMin, int sortOrder) {
    }

    /** The values of a profile to write. */
    record NewProfile(String key, String name, boolean medVerificationRequired, int vitalsEveryMinutes,
                      Integer doseWindowBeforeMin, Integer doseWindowAfterMin, int sortOrder) {
    }

    record WardProfile(UUID wardId, String wardName, String profileKey) {
    }
}
