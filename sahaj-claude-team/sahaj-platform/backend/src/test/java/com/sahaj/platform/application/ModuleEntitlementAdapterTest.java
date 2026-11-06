package com.sahaj.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahaj.platform.api.ModuleEntitlementPort.ModuleEntitlement;
import com.sahaj.platform.application.ModuleEntitlementRepository.EntitlementRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SAH-19 port for other modules: defaults, stored values, and expired trial as readonly. */
class ModuleEntitlementAdapterTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private ModuleEntitlementRepository repository;
    private ModuleEntitlementAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = mock(ModuleEntitlementRepository.class);
        adapter = new ModuleEntitlementAdapter(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.find(TENANT_ID)).thenReturn(Optional.empty());
    }

    @Test
    void noRowGivesTheDefaults() {
        assertThat(adapter.forTenant(TENANT_ID)).isEqualTo(new ModuleEntitlement("off", false, 25));
    }

    @Test
    void storedValuesAreReturned() {
        when(repository.find(TENANT_ID)).thenReturn(Optional.of(new EntitlementRow("on", null, true, 40, null)));

        assertThat(adapter.forTenant(TENANT_ID)).isEqualTo(new ModuleEntitlement("on", true, 40));
    }

    @Test
    void anExpiredTrialIsReadonlyAndALiveTrialIsTrial() {
        when(repository.find(TENANT_ID))
                .thenReturn(Optional.of(new EntitlementRow("trial", NOW.minusSeconds(5), false, 25, null)));
        assertThat(adapter.forTenant(TENANT_ID).ipdState()).isEqualTo("readonly");

        when(repository.find(TENANT_ID))
                .thenReturn(Optional.of(new EntitlementRow("trial", NOW.plusSeconds(5), false, 25, null)));
        assertThat(adapter.forTenant(TENANT_ID).ipdState()).isEqualTo("trial");
    }
}
