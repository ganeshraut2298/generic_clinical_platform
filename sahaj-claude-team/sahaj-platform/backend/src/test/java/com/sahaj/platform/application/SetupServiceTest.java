package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.OpdDefaultsPort;
import com.sahaj.platform.api.AuditLog;
import com.sahaj.platform.domain.Membership;
import com.sahaj.platform.domain.MembershipRole;
import com.sahaj.platform.domain.MembershipStatus;
import com.sahaj.platform.domain.SetupProgress;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetupServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID OWNER_MEMBERSHIP_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    private SetupProgressRepository progressRepository;
    private ClinicProfileRepository clinicProfileRepository;
    private DoctorProfileRepository doctorProfileRepository;
    private MembershipRepository membershipRepository;
    private AppUserRepository appUserRepository;
    private TenantRepository tenantRepository;
    private OpdDefaultsPort opdDefaultsPort;
    private AuditLog auditLog;
    private SetupService service;

    @BeforeEach
    void setUp() {
        progressRepository = mock(SetupProgressRepository.class);
        clinicProfileRepository = mock(ClinicProfileRepository.class);
        doctorProfileRepository = mock(DoctorProfileRepository.class);
        membershipRepository = mock(MembershipRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        tenantRepository = mock(TenantRepository.class);
        opdDefaultsPort = mock(OpdDefaultsPort.class);
        auditLog = mock(AuditLog.class);
        service = new SetupService(progressRepository, clinicProfileRepository, doctorProfileRepository,
                membershipRepository, appUserRepository, tenantRepository, opdDefaultsPort, auditLog, validator,
                objectMapper, clock);
        when(progressRepository.find(TENANT_ID)).thenReturn(Optional.empty());
    }

    private JsonNode json(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void savesAValidClinicStepAndAudits() {
        service.saveStep(TENANT_ID, 1, json("""
                {"name": "Sunrise Clinic", "city": "Pune"}
                """));

        verify(progressRepository).saveStep(eq(TENANT_ID), eq(1), eq("clinic"), any());
        verify(auditLog).record(eq("SETUP_STEP_SAVED"), eq("SETUP_PROGRESS"), eq(TENANT_ID), eq(null), any());
    }

    @Test
    void rejectsAClinicStepMissingTheRequiredCity() {
        assertThatThrownBy(() -> service.saveStep(TENANT_ID, 1, json("""
                {"name": "Sunrise Clinic"}
                """))).isInstanceOf(ConstraintViolationException.class);
        verify(progressRepository, never()).saveStep(any(), any(Integer.class), any(), any());
    }

    @Test
    void rejectsATeamStepOverTheFreePlanUserLimit() {
        String body = """
                {"doctors": [
                    {"name": "Dr Owner", "specialty": "GP", "feeRupees": 300},
                    {"name": "Dr Two", "specialty": "GP", "feeRupees": 300, "mobile": "9123456780"}
                 ],
                 "staff": [
                    {"name": "A", "role": "NURSE", "mobile": "9123456781"},
                    {"name": "B", "role": "FRONT_DESK", "mobile": "9123456782"},
                    {"name": "C", "role": "BILLING", "mobile": "9123456783"},
                    {"name": "D", "role": "PHARMACIST", "mobile": "9123456784"}
                 ]}
                """;
        assertThatThrownBy(() -> service.saveStep(TENANT_ID, 3, json(body)))
                .isInstanceOf(PlanLimitException.class);
    }

    @Test
    void getStateDefaultsToStepOneWithNoSavedProgress() {
        SetupState state = service.getState(TENANT_ID);

        assertThat(state.currentStep()).isEqualTo(1);
        assertThat(state.answers()).isEmpty();
        assertThat(state.maxDoctors()).isEqualTo(2);
        assertThat(state.maxUsers()).isEqualTo(5);
    }

    @Test
    void slugAvailabilityRejectsAReservedWordAndNeverOffersItBack() {
        when(tenantRepository.isSlugAvailable(anyString())).thenReturn(true);

        SlugAvailability result = service.checkSlugAvailability(TENANT_ID, "clinic");

        assertThat(result.available()).isFalse();
        assertThat(result.alternatives()).doesNotContain("clinic");
        verify(tenantRepository, never()).isSlugAvailable("clinic");
    }

    @Test
    void slugAvailabilityDelegatesAnOrdinaryWordToTheDatabase() {
        when(tenantRepository.isSlugAvailable("sunrise")).thenReturn(true);

        SlugAvailability result = service.checkSlugAvailability(TENANT_ID, "sunrise");

        assertThat(result.available()).isTrue();
        assertThat(result.alternatives()).isEmpty();
    }

    @Test
    void completeFailsWhenAStepHasNotBeenSaved() {
        when(progressRepository.find(TENANT_ID)).thenReturn(Optional.of(new SetupProgress(3, Map.of("clinic", Map.of()), null)));

        assertThatThrownBy(() -> service.complete(TENANT_ID, OWNER_USER_ID))
                .isInstanceOf(SetupIncompleteException.class);
    }

    @Test
    void completeIsIdempotentOnceAlreadyDone() {
        when(progressRepository.find(TENANT_ID)).thenReturn(
                Optional.of(new SetupProgress(5, Map.of(), clock.instant())));
        when(tenantRepository.findProfile(TENANT_ID))
                .thenReturn(Optional.of(new com.sahaj.platform.domain.TenantProfile("Sunrise Clinic", "teal", "FREE", null)));

        SetupCompleteResult result = service.complete(TENANT_ID, OWNER_USER_ID);

        assertThat(result.brand()).isEqualTo("teal");
        verify(clinicProfileRepository, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completeWritesEveryRowAndMarksProgressDone() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("clinic", Map.of("name", "Sunrise Clinic", "city", "Pune"));
        answers.put("specialty", Map.of("code", "gp"));
        answers.put("team", Map.of(
                "doctors", List.of(Map.of("name", "Dr Owner", "specialty", "GP", "feeRupees", 300)),
                "staff", List.of(Map.of("name", "Nurse A", "role", "NURSE", "mobile", "9123456781"))));
        answers.put("opd", Map.of("feeTiming", "before", "queue", true, "vitals", true,
                "followUpDays", 7, "followUpFee", "free"));
        answers.put("look", Map.of("brand", "teal", "slug", "sunrise", "language", "en"));
        when(progressRepository.find(TENANT_ID)).thenReturn(Optional.of(new SetupProgress(5, answers, null)));
        when(tenantRepository.isSlugAvailable("sunrise")).thenReturn(true);
        when(membershipRepository.findByTenantAndUser(TENANT_ID, OWNER_USER_ID)).thenReturn(
                Optional.of(new Membership(OWNER_MEMBERSHIP_ID, TENANT_ID, OWNER_USER_ID, MembershipRole.OWNER, MembershipStatus.ACTIVE)));
        when(appUserRepository.findByMobile("9123456781")).thenReturn(Optional.empty());

        SetupCompleteResult result = service.complete(TENANT_ID, OWNER_USER_ID);

        assertThat(result.brand()).isEqualTo("teal");
        verify(tenantRepository).claimSlugAndBrand(TENANT_ID, "Sunrise Clinic", "teal", "sunrise");
        verify(clinicProfileRepository).upsert(eq(TENANT_ID), eq("Sunrise Clinic"), any(), eq("Pune"), any(), any(),
                any(), eq("gp"), eq("teal"), eq("en"));
        verify(doctorProfileRepository).insert(eq(OWNER_MEMBERSHIP_ID), eq(TENANT_ID), eq("GP"), anyLong(), any(), any());
        verify(appUserRepository).insert(any(), eq("9123456781"), eq("Nurse A"));
        verify(membershipRepository, times(1)).insert(any(), eq(TENANT_ID), any(), eq(MembershipRole.NURSE), any());
        verify(opdDefaultsPort).saveDefaults(TENANT_ID, "before", true, true, 7, "free");
        verify(progressRepository).markCompleted(TENANT_ID, clock.instant());
        verify(auditLog).record(eq("SETUP_COMPLETED"), eq("TENANT"), eq(TENANT_ID), eq(null), any());
    }

    @Test
    void completeRefusesAReservedSlugEvenIfSavedEarlier() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("clinic", Map.of("name", "Sunrise Clinic", "city", "Pune"));
        answers.put("specialty", Map.of("code", "gp"));
        answers.put("team", Map.of("doctors",
                List.of(Map.of("name", "Dr Owner", "specialty", "GP", "feeRupees", 300)), "staff", List.of()));
        answers.put("opd", Map.of("feeTiming", "before", "queue", true, "vitals", true,
                "followUpDays", 7, "followUpFee", "free"));
        answers.put("look", Map.of("brand", "teal", "slug", "clinic", "language", "en"));
        when(progressRepository.find(TENANT_ID)).thenReturn(Optional.of(new SetupProgress(5, answers, null)));

        assertThatThrownBy(() -> service.complete(TENANT_ID, OWNER_USER_ID)).isInstanceOf(SlugTakenException.class);
        verify(tenantRepository, never()).claimSlugAndBrand(any(), any(), any(), any());
    }
}
