package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.IpdSettingsRepository.NursingSettings;
import com.sahaj.ipd.application.NursingProfileRepository.ProfileRow;
import com.sahaj.ipd.domain.EffectiveNursingSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-76-6 the values in force for a patient: the ward profile, else the clinic settings, else the defaults. */
class NursingSettingsResolverTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ICU_PATIENT = UUID.randomUUID();
    private static final UUID PLAIN_PATIENT = UUID.randomUUID();

    private IpdSettingsRepository settings;
    private NursingProfileRepository profiles;
    private NursingSettingsResolver resolver;

    @BeforeEach
    void setUp() {
        settings = mock(IpdSettingsRepository.class);
        profiles = mock(NursingProfileRepository.class);
        resolver = new NursingSettingsResolver(settings, profiles);
    }

    @Test
    void aClinicWithNoRowGetsTheBuiltDefaults() {
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.empty());
        when(profiles.findProfilesOfAdmissions(eq(TENANT), any())).thenReturn(Map.of());

        EffectiveNursingSettings effective = resolver.effectiveFor(TENANT, PLAIN_PATIENT);

        assertThat(effective).isEqualTo(EffectiveNursingSettings.defaults());
        assertThat(effective.vitalsIntervalMinutes()).isEqualTo(240);
    }

    @Test
    void theClinicSettingsReplaceTheDefaultsForAWardWithoutAProfile() {
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.of(new NursingSettings(false, 30, 45, 10, 20, 3,
                7)));
        when(profiles.findProfilesOfAdmissions(eq(TENANT), any())).thenReturn(Map.of());

        EffectiveNursingSettings effective = resolver.effectiveFor(TENANT, PLAIN_PATIENT);

        assertThat(effective).isEqualTo(new EffectiveNursingSettings(false, 30, 45, 10, 20, 3, 7, 240));
    }

    @Test
    void theWardProfileWinsForVerificationAndVitalsAndForTheWindowsItSets() {
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.of(new NursingSettings(false, 30, 45, 10, 20, 3,
                7)));
        ProfileRow icu = new ProfileRow("icu", "ICU", true, 60, null, 15, 1);
        when(profiles.findProfilesOfAdmissions(eq(TENANT), any())).thenReturn(Map.of(ICU_PATIENT, icu));

        Map<UUID, EffectiveNursingSettings> all = resolver.effectiveForAll(TENANT, List.of(ICU_PATIENT, PLAIN_PATIENT));

        assertThat(all.get(ICU_PATIENT)).isEqualTo(new EffectiveNursingSettings(true, 30, 15, 10, 20, 3, 7, 60));
        assertThat(all.get(PLAIN_PATIENT)).isEqualTo(new EffectiveNursingSettings(false, 30, 45, 10, 20, 3, 7, 240));
    }

    @Test
    void manyAdmissionsCostTwoReadsOnly() {
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.empty());
        when(profiles.findProfilesOfAdmissions(eq(TENANT), any())).thenReturn(Map.of());

        resolver.effectiveForAll(TENANT, List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        verify(settings, times(1)).findNursingSettings(TENANT);
        verify(profiles, times(1)).findProfilesOfAdmissions(eq(TENANT), any());
    }

    @Test
    void theClinicDefaultsIgnoreEveryProfile() {
        when(settings.findNursingSettings(TENANT)).thenReturn(Optional.empty());

        assertThat(resolver.clinicDefaults(TENANT)).isEqualTo(EffectiveNursingSettings.defaults());
    }
}
