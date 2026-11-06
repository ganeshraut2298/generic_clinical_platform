package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahaj.platform.api.InvalidInputException;
import com.sahaj.platform.api.OpdSetupStatus;
import com.sahaj.platform.api.OwnerGuard;
import com.sahaj.platform.application.AuditEventRepository.AuditRow;
import com.sahaj.platform.application.ClinicProfileRepository.ProfileRow;
import com.sahaj.platform.application.SetupOverviewService.Check;
import com.sahaj.platform.application.SetupOverviewService.Overview;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** SAH-7 AC6 and AC12: the server-computed percentage, the checklist and the latest change. */
class SetupOverviewServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OWNER_USER = UUID.randomUUID();
    // 20:00 UTC on 1 Nov is already 2 Nov in India: holidays are compared on the Indian date.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T20:00:00Z"), ZoneOffset.UTC);

    private ClinicProfileRepository profileRepository;
    private HolidayRepository holidayRepository;
    private OpdSetupStatus opdSetupStatus;
    private StaffPinRepository staffPinRepository;
    private AuditEventRepository auditEventRepository;
    private OwnerGuard ownerGuard;
    private SetupOverviewService service;

    @BeforeEach
    void setUp() {
        profileRepository = mock(ClinicProfileRepository.class);
        holidayRepository = mock(HolidayRepository.class);
        opdSetupStatus = mock(OpdSetupStatus.class);
        staffPinRepository = mock(StaffPinRepository.class);
        auditEventRepository = mock(AuditEventRepository.class);
        ownerGuard = mock(OwnerGuard.class);
        service = new SetupOverviewService(profileRepository, holidayRepository, opdSetupStatus, staffPinRepository,
                auditEventRepository, ownerGuard, new ObjectMapper(), CLOCK);
    }

    private void profile(String address, String phone, String specialty) {
        when(profileRepository.find(TENANT_ID)).thenReturn(Optional.of(new ProfileRow("Sai", null, address, "Pune",
                null, phone, null, null, null, "sai", specialty, "blue", null)));
    }

    private static boolean done(Overview overview, String key) {
        return overview.checks().stream().filter(c -> c.key().equals(key)).map(Check::done).findFirst().orElseThrow();
    }

    @Test
    void aFullySetUpClinicIs100Percent() {
        profile("12 MG Road", "9876543210", "gp");
        when(holidayRepository.countFrom(any(), any())).thenReturn(2);
        when(opdSetupStatus.statusFor(TENANT_ID)).thenReturn(new OpdSetupStatus.Status(true, true, true));
        when(auditEventRepository.findLatest(TENANT_ID, 1)).thenReturn(List.of(new AuditRow(UUID.randomUUID(),
                "Asha", "SERVICE_ADDED", "SERVICE", null, "{\"name\":\"Dressing\"}",
                Instant.parse("2026-11-01T10:00:00Z"))));

        Overview overview = service.overview(TENANT_ID, OWNER_USER);

        assertThat(overview.percent()).isEqualTo(100);
        assertThat(overview.checks()).hasSize(7).allMatch(Check::done);
        assertThat(overview.lastChange().action()).isEqualTo("SERVICE_ADDED");
        assertThat(overview.lastChange().actorName()).isEqualTo("Asha");
        assertThat(overview.lastChange().after()).containsEntry("name", "Dressing");
        assertThat(overview.lastChange().before()).isNull();
    }

    @Test
    void whatIsMissingLowersThePercentAndIsListedUndone() {
        profile("12 MG Road", null, null);
        when(holidayRepository.countFrom(any(), any())).thenReturn(0);
        when(opdSetupStatus.statusFor(TENANT_ID)).thenReturn(new OpdSetupStatus.Status(false, true, false));
        when(auditEventRepository.findLatest(TENANT_ID, 1)).thenReturn(List.of());

        Overview overview = service.overview(TENANT_ID, OWNER_USER);

        assertThat(overview.percent()).isEqualTo(17);
        assertThat(done(overview, "profile")).isFalse();
        assertThat(done(overview, "holidays")).isFalse();
        assertThat(done(overview, "specialty")).isFalse();
        assertThat(done(overview, "services")).isFalse();
        assertThat(done(overview, "fees")).isTrue();
        assertThat(done(overview, "paper")).isFalse();
        assertThat(overview.lastChange()).isNull();
    }

    @Test
    void thePinLineIsOptionalAndDoneOnlyWhenEveryActiveMemberHasAPin() {
        profile("12 MG Road", "9876543210", "gp");
        when(holidayRepository.countFrom(any(), any())).thenReturn(2);
        when(opdSetupStatus.statusFor(TENANT_ID)).thenReturn(new OpdSetupStatus.Status(true, true, true));
        when(auditEventRepository.findLatest(TENANT_ID, 1)).thenReturn(List.of());
        when(staffPinRepository.countActiveMembersWithoutPin(TENANT_ID)).thenReturn(2);

        Overview overview = service.overview(TENANT_ID, OWNER_USER);

        Check pin = overview.checks().stream().filter(c -> c.key().equals("pin")).findFirst().orElseThrow();
        assertThat(pin.label()).isEqualTo("Own PIN for each staff member");
        assertThat(pin.why()).isEqualTo("So medicine checks show who did them");
        assertThat(pin.done()).isFalse();
        assertThat(pin.optional()).isTrue();
        assertThat(overview.percent()).as("an optional line never changes the percent").isEqualTo(100);

        when(staffPinRepository.countActiveMembersWithoutPin(TENANT_ID)).thenReturn(0);
        assertThat(done(service.overview(TENANT_ID, OWNER_USER), "pin")).isTrue();
    }

    @Test
    void holidaysAreCountedFromTodayInIndia() {
        profile("a", "b", "gp");
        when(opdSetupStatus.statusFor(TENANT_ID)).thenReturn(new OpdSetupStatus.Status(true, true, true));
        when(auditEventRepository.findLatest(eq(TENANT_ID), anyInt())).thenReturn(List.of());

        service.overview(TENANT_ID, OWNER_USER);

        verify(holidayRepository).countFrom(TENANT_ID, LocalDate.of(2026, 11, 2));
    }

    @Test
    void aNonOwnerGetsNeitherTheOverviewNorTheHistory() {
        doThrow(new AccessDeniedException("no")).when(ownerGuard).requireOwner(TENANT_ID, OWNER_USER);

        assertThatThrownBy(() -> service.overview(TENANT_ID, OWNER_USER)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.auditEvents(TENANT_ID, OWNER_USER, 20))
                .isInstanceOf(AccessDeniedException.class);
        verify(auditEventRepository, never()).findLatest(any(), anyInt());
    }

    @Test
    void theHistoryLimitDefaultsTo20AndMustBeFrom1To100() {
        when(auditEventRepository.findLatest(TENANT_ID, 20)).thenReturn(List.of());

        assertThat(service.auditEvents(TENANT_ID, OWNER_USER, null)).isEmpty();
        verify(auditEventRepository).findLatest(TENANT_ID, 20);
        assertThatThrownBy(() -> service.auditEvents(TENANT_ID, OWNER_USER, 0))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.auditEvents(TENANT_ID, OWNER_USER, 101))
                .isInstanceOf(InvalidInputException.class);
    }
}
