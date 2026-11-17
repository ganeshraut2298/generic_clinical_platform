package com.sahaj.ipd.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sahaj.platform.api.AuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SAH-76 (ADR-027 D11) every IPD audit row carries the device label in its {@code after} map and nothing else changes. */
class IpdAuditTest {

    private final AuditLog auditLog = mock(AuditLog.class);
    private final IpdAudit audit = new IpdAudit(auditLog, new AuditDevice());

    @Test
    void theDeviceIsAddedToACopyAndTheOriginalMapIsLeftAlone() {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("orderId", UUID.randomUUID());

        Map<String, Object> with = audit.with(after);

        assertThat(with).containsKeys("orderId", "device");
        assertThat(after).doesNotContainKey("device");
        assertThat(with.get("device")).isEqualTo(AuditDevice.label(null, null));
    }

    @Test
    void aNullAfterBecomesAMapHoldingOnlyTheDevice() {
        assertThat(audit.with(null)).containsOnlyKeys("device");
    }

    @Test
    void recordPassesEverythingThroughWithTheDeviceInAfter() {
        UUID id = UUID.randomUUID();
        Map<String, Object> before = Map.of("undoSeconds", 10);

        audit.record("IPD_NURSING_SETTINGS_CHANGED", "IPD_CONFIG", id, before, Map.of("undoSeconds", 7));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(auditLog).record(eq("IPD_NURSING_SETTINGS_CHANGED"), eq("IPD_CONFIG"), eq(id), eq(before), after.capture());
        assertThat(after.getValue()).containsEntry("undoSeconds", 7).containsKey("device");
    }

    @Test
    void anOrderRowWithNoBeforeKeepsItNull() {
        audit.record("IPD_DOSE_GIVEN", "IPD_DOSE", UUID.randomUUID(), null, Map.of("outcome", "GIVEN"));

        verify(auditLog).record(eq("IPD_DOSE_GIVEN"), eq("IPD_DOSE"), any(), isNull(), any());
    }
}
