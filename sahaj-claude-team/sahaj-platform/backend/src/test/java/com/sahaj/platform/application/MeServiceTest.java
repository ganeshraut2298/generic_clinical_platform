package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.SetupProgress;
import com.sahaj.platform.domain.TenantProfile;
import com.sahaj.platform.domain.UserPreference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();

    private TeamRepository teamRepository;
    private TenantRepository tenantRepository;
    private ClinicProfileRepository clinicProfileRepository;
    private SetupProgressRepository setupProgressRepository;
    private UserPreferenceRepository userPreferenceRepository;
    private MeService service;

    @BeforeEach
    void setUp() {
        teamRepository = mock(TeamRepository.class);
        tenantRepository = mock(TenantRepository.class);
        clinicProfileRepository = mock(ClinicProfileRepository.class);
        setupProgressRepository = mock(SetupProgressRepository.class);
        userPreferenceRepository = mock(UserPreferenceRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
        service = new MeService(teamRepository, tenantRepository, clinicProfileRepository,
                setupProgressRepository, userPreferenceRepository, clock);

        when(teamRepository.findByUser(TENANT_ID, USER_ID)).thenReturn(Optional.of(new TeamRepository.TeamRow(
                MEMBERSHIP_ID, USER_ID, "Asha", "9123456789", MembershipRole.OWNER, MembershipStatus.ACTIVE, null,
                false, null, null, null, 0L)));
        when(tenantRepository.findProfile(TENANT_ID))
                .thenReturn(Optional.of(new TenantProfile("Sunrise Clinic", "teal", "FREE",
                        Instant.parse("2026-12-01T10:00:00Z"))));
        when(clinicProfileRepository.findCity(TENANT_ID)).thenReturn(Optional.of("Pune"));
        when(setupProgressRepository.find(TENANT_ID)).thenReturn(Optional.of(new SetupProgress(5, Map.of(), Instant.now())));
        when(userPreferenceRepository.find(TENANT_ID, USER_ID)).thenReturn(Optional.empty());
    }

    @Test
    void buildsTheOwnerMenuFromTheirRole() {
        MeView view = service.getMe(TENANT_ID, USER_ID, "OWNER");

        assertThat(view.name()).isEqualTo("Asha");
        assertThat(view.membershipId()).isEqualTo(MEMBERSHIP_ID);
        assertThat(view.role()).isEqualTo("OWNER");
        assertThat(view.menu()).contains("today", "revenue", "config");
        assertThat(view.clinic().name()).isEqualTo("Sunrise Clinic");
        assertThat(view.clinic().area()).isEqualTo("Pune");
        assertThat(view.clinic().setupComplete()).isTrue();
        assertThat(view.clinic().planDaysLeft()).isEqualTo(30);
        assertThat(view.preferences().language()).isEqualTo("en");
    }

    @Test
    void brandHexIsNullForAPresetBrand() {
        assertThat(service.getMe(TENANT_ID, USER_ID, "OWNER").clinic().brandHex()).isNull();
    }

    @Test
    void brandHexComesFromTheProfileWhenTheBrandIsCustom() {
        when(tenantRepository.findProfile(TENANT_ID))
                .thenReturn(Optional.of(new TenantProfile("Sunrise Clinic", "custom", "FREE", null)));
        when(clinicProfileRepository.find(TENANT_ID)).thenReturn(Optional.of(
                new ClinicProfileRepository.ProfileRow("Sunrise Clinic", null, null, "Pune", null, null, null, null,
                        null, "sunrise", null, "custom", "#1f5bc8")));

        assertThat(service.getMe(TENANT_ID, USER_ID, "OWNER").clinic().brandHex()).isEqualTo("#1f5bc8");
    }

    @Test
    void aNurseNeverSeesBillingOrRevenue() {
        MeView view = service.getMe(TENANT_ID, USER_ID, "NURSE");

        assertThat(view.menu()).containsExactly("today", "queue", "patients");
    }

    @Test
    void simpleModeShowsAShortFlatListFilteredByRole() {
        when(userPreferenceRepository.find(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(new UserPreference("en", "light", "simple", false)));

        MeView view = service.getMe(TENANT_ID, USER_ID, "FRONT_DESK");

        assertThat(view.menu()).containsExactly("today", "queue", "appointments", "patients", "billing");
    }

    @Test
    void updatingOnlyTheThemeKeepsEveryOtherPreference() {
        when(userPreferenceRepository.find(TENANT_ID, USER_ID))
                .thenReturn(Optional.of(new UserPreference("mr", "light", "advanced", true)));

        service.updatePreferences(TENANT_ID, USER_ID, "OWNER", new PreferencesInput(null, "dark", null, null));

        verify(userPreferenceRepository).upsert(TENANT_ID, USER_ID, new UserPreference("mr", "dark", "advanced", true));
    }

    @Test
    void setupPercentReflectsTheCurrentStepBeforeItIsComplete() {
        when(setupProgressRepository.find(TENANT_ID)).thenReturn(Optional.of(new SetupProgress(3, Map.of(), null)));

        MeView view = service.getMe(TENANT_ID, USER_ID, "OWNER");

        assertThat(view.clinic().setupComplete()).isFalse();
        assertThat(view.clinic().setupPercent()).isEqualTo(40);
    }
}
