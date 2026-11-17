package com.sahaj.ipd.application;

import com.sahaj.ipd.application.MedicineViews.KeyLabel;
import java.util.List;
import java.util.UUID;

/**
 * SAH-76-6 request and response shapes of {@code docs/api/ipd.yaml} 0.4.0 (nursing settings, ward profiles). Request
 * fields are loose ({@code Object}) so that the server is the only judge of the format and gives the contract's own
 * message. Profile names are shown on screen only.
 */
public final class NursingSettingsViews {

    private NursingSettingsViews() {
    }

    /** Body of {@code PUT /nursing-settings}, and the {@code settings} part of the answer. */
    public record SettingsInput(Object medVerificationRequired, Object doseWindowBeforeMinutes,
                                Object doseWindowAfterMinutes, Object statWindowMinutes, Object unackFlagMinutes,
                                Object unackFlagStatMinutes, Object undoSeconds) {
    }

    /** Body of {@code POST /nursing-profiles} and {@code PUT /nursing-profiles/{key}}. */
    public record ProfileInput(String name, Object medVerificationRequired, Object vitalsEveryMinutes,
                               Object doseWindowBeforeMinutes, Object doseWindowAfterMinutes) {
    }

    /** Body of {@code PUT /wards/{id}/nursing-profile}: the profile key, or null for the clinic defaults. */
    public record WardProfileInput(String profileKey) {
    }

    /** One profile of the answer (contract {@code NursingProfile}). */
    public record ProfileView(String key, String name, boolean starter, boolean medVerificationRequired,
                              int vitalsEveryMinutes, Integer doseWindowBeforeMinutes, Integer doseWindowAfterMinutes,
                              int wardCount) {
    }

    /** One ward with its profile key (null: the clinic defaults). */
    public record WardView(UUID wardId, String wardName, String profileKey) {
    }

    /** The settings answer (contract {@code NursingSettings}). */
    public record Settings(boolean canEdit, SettingsView settings, List<ProfileView> profiles, List<WardView> wards,
                           List<KeyLabel> highAlertMedicines, List<KeyLabel> timeCriticalMedicines) {
    }

    /** The clinic settings as the answer carries them. */
    public record SettingsView(boolean medVerificationRequired, int doseWindowBeforeMinutes, int doseWindowAfterMinutes,
                               int statWindowMinutes, int unackFlagMinutes, int unackFlagStatMinutes, int undoSeconds) {
    }
}
