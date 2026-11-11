package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahaj.ipd.application.PackageRepository.PackageRow;
import com.sahaj.ipd.application.PackageViews.IpdPackage;
import com.sahaj.ipd.application.PackageViews.PackageList;
import com.sahaj.platform.api.ModuleEntitlementPort;
import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.api.ModuleOffException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SAH-29 (AC1, AC6) the Packages list: module state, order as stored, one query, nothing written. */
class PackageServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final ModuleEntitlementPort entitlements = mock(ModuleEntitlementPort.class);
    private final PackageRepository repository = mock(PackageRepository.class);
    private final PackageService service = new PackageService(
            new IpdGate(entitlements, Clock.fixed(Instant.parse("2026-11-10T05:30:00Z"), ZoneOffset.UTC)), repository);

    private void ipd(String state) {
        when(entitlements.forTenant(TENANT)).thenReturn(new ModuleEntitlement(state, false, 25));
    }

    @Test
    void theListIsTheStoredRowsInTheOrderTheRepositoryGivesThem() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ipd("on");
        when(repository.findActive(TENANT)).thenReturn(List.of(
                new PackageRow(first, "Appendicectomy", 4_500_000L, 3),
                new PackageRow(second, "Cataract surgery", 2_500_000L, 0)));

        PackageList list = service.list(TENANT);

        assertThat(list.items()).extracting(IpdPackage::id, IpdPackage::name, IpdPackage::pricePaise,
                IpdPackage::includedDays).containsExactly(
                        tuple(first, "Appendicectomy", 4_500_000L, 3),
                        tuple(second, "Cataract surgery", 2_500_000L, 0));
    }

    @Test
    void aClinicWithNoPackageGetsAnEmptyList() {
        ipd("trial");
        when(repository.findActive(TENANT)).thenReturn(List.of());

        assertThat(service.list(TENANT).items()).isEmpty();
    }

    @Test
    void trialOnAndReadOnlyAllRead() {
        when(repository.findActive(TENANT)).thenReturn(List.of());
        for (String state : new String[] {"trial", "on", "readonly"}) {
            ipd(state);
            assertThat(service.list(TENANT).items()).isEmpty();
        }
    }

    @Test
    void moduleOffAnswersModuleOffAndReadsNothing() {
        for (String state : new String[] {"off", "none"}) {
            ipd(state);
            assertThatThrownBy(() -> service.list(TENANT)).isInstanceOf(ModuleOffException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void theClinicComesFromTheCallerAndOnlyTheReadIsUsed() {
        ipd("on");
        when(repository.findActive(TENANT)).thenReturn(List.of());

        service.list(TENANT);

        verify(repository).findActive(TENANT);
        verifyNoMoreInteractions(repository);
    }
}
