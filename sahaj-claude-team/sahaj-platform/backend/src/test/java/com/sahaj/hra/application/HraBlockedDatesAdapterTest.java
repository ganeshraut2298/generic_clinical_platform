package com.sahaj.hra.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.hra.application.LeaveRepository.LeaveRow;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-21: approved leave closes booking, only while the HRA add-on is on. */
class HraBlockedDatesAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID DOCTOR = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 11, 10);

    private LeaveRepository repository;
    private ModuleEntitlementPort entitlements;
    private HraBlockedDatesAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(LeaveRepository.class);
        entitlements = mock(ModuleEntitlementPort.class);
        adapter = new HraBlockedDatesAdapter(repository, entitlements);
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", true, 25));
    }

    private static LeaveRow row(LocalDate from, LocalDate to) {
        return new LeaveRow(UUID.randomUUID(), DOCTOR, "CL", from, to, null, "approved", Instant.EPOCH, Instant.EPOCH,
                UUID.randomUUID());
    }

    @Test
    void returnsEveryApprovedLeaveDayClippedToTheAskedRange() {
        LocalDate asked = FROM.plusDays(1);
        when(repository.findApprovedOverlappingForMember(TENANT, DOCTOR, asked, asked.plusDays(3)))
                .thenReturn(List.of(row(FROM, FROM.plusDays(2)), row(asked.plusDays(3), asked.plusDays(9))));

        assertThat(adapter.blockedDates(TENANT, DOCTOR, asked, asked.plusDays(3)))
                .containsExactlyInAnyOrder(asked, asked.plusDays(1), asked.plusDays(3));
    }

    @Test
    void returnsNothingWhenHraIsOffAndDoesNotReadLeave() {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement("off", false, 25));

        assertThat(adapter.blockedDates(TENANT, DOCTOR, FROM, FROM.plusDays(5))).isEmpty();
        verify(repository, never()).findApprovedOverlappingForMember(TENANT, DOCTOR, FROM, FROM.plusDays(5));
    }
}
