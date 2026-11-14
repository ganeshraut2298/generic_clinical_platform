package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.AdmissionRepository.AdmissionRow;
import com.sahaj.ipd.application.DischargeRepository.FinalBillRow;
import com.sahaj.opd.api.CourseAdmissionSource.CourseAdmission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-58: the status, IP number and bill total of the admissions linked to course sittings. */
class CourseAdmissionSourceAdapterTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ADMITTED = UUID.randomUUID();
    private static final UUID DISCHARGED = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");

    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final DischargeRepository discharges = mock(DischargeRepository.class);
    private final IpdBilling billing = mock(IpdBilling.class);
    private final IpdBilling.Batch batch = mock(IpdBilling.Batch.class);
    private final IpdCatalog catalog = mock(IpdCatalog.class);
    private final IpdGate gate = mock(IpdGate.class);
    private CourseAdmissionSourceAdapter adapter;

    private static AdmissionRow row(UUID id, String ip, String status, Instant dischargedAt) {
        return new AdmissionRow(id, ip, UUID.randomUUID(), UUID.randomUUID(), "101", "gen", "General", UUID.randomUUID(),
                "planned", "Self", "reason", status, Instant.parse("2026-11-09T05:00:00Z"), 0, dischargedAt);
    }

    @BeforeEach
    void setUp() {
        adapter = new CourseAdmissionSourceAdapter(admissions, discharges, billing, catalog, gate);
        when(gate.now()).thenReturn(NOW);
        when(catalog.depositRules(TENANT)).thenReturn(IpdSettingsRepository.DepositRules.DEFAULTS);
        when(billing.forOne(any(), any(), any(), anyInt())).thenReturn(batch);
    }

    @Test
    void anAdmittedPatientShowsTheRunningBillTotal() {
        AdmissionRow row = row(ADMITTED, "IP/26-27/0001", "ADMITTED", null);
        when(admissions.find(TENANT, ADMITTED)).thenReturn(Optional.of(row));
        when(batch.bill(row, "")).thenReturn(new RunningBill(3, "", "General", BigDecimal.ONE, List.of(), 450_000, 0,
                null, 80));

        Map<UUID, CourseAdmission> result = adapter.admissions(TENANT, List.of(ADMITTED));

        assertThat(result.get(ADMITTED)).isEqualTo(new CourseAdmission(ADMITTED, "IP/26-27/0001", "ADMITTED", 450_000));
        verify(discharges, never()).findFinalBill(any(), any());
    }

    @Test
    void aDischargedPatientShowsTheFrozenFinalBillTotalNotARecomputedOne() {
        AdmissionRow row = row(DISCHARGED, "IP/26-27/0002", "DISCHARGED", Instant.parse("2026-11-11T05:00:00Z"));
        when(admissions.find(TENANT, DISCHARGED)).thenReturn(Optional.of(row));
        when(discharges.findFinalBill(TENANT, DISCHARGED)).thenReturn(Optional.of(new FinalBillRow(UUID.randomUUID(),
                DISCHARGED, 300_000, 100_000, 200_000, "CASH", UUID.randomUUID(), NOW, 2, "NOON", "gen",
                BigDecimal.ONE)));

        Map<UUID, CourseAdmission> result = adapter.admissions(TENANT, List.of(DISCHARGED));

        assertThat(result.get(DISCHARGED).status()).isEqualTo("DISCHARGED");
        assertThat(result.get(DISCHARGED).billTotalPaise()).isEqualTo(300_000);
        verify(billing, never()).forOne(any(), any(), any(), anyInt());
    }

    @Test
    void anAdmissionOfAnotherClinicOrAnUnknownOneIsAbsent() {
        when(admissions.find(TENANT, ADMITTED)).thenReturn(Optional.empty());

        assertThat(adapter.admissions(TENANT, List.of(ADMITTED))).isEmpty();
    }
}
