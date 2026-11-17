package com.sahaj.ipd.application;

import com.sahaj.platform.api.AuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SAH-76 (ADR-027 D11): every IPD write of this set records its audit row through here, so the {@code after} map
 * carries {@code device: {id, agent}}. Ids, keys, numbers and times only: never clinical text.
 */
@Component
public class IpdAudit {

    private final AuditLog auditLog;
    private final AuditDevice device;

    IpdAudit(AuditLog auditLog, AuditDevice device) {
        this.auditLog = auditLog;
        this.device = device;
    }

    /**
     * Copy of {@code after} plus {@code device}. A null {@code after} (no IPD write removes a record) becomes a map
     * holding only the device.
     */
    public Map<String, Object> with(Map<String, Object> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (after != null) {
            out.putAll(after);
        }
        out.put("device", device.label());
        return out;
    }

    /** {@link AuditLog#record} with the device label added to {@code after}. */
    public void record(String action, String entityType, UUID entityId, Map<String, Object> before,
                       Map<String, Object> after) {
        auditLog.record(action, entityType, entityId, before, with(after));
    }
}
